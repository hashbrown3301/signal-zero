package com.itantra.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.comm.Language
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** CPU-only, cached greedy generation for the pinned quantized NLLB model pack. */
class NllbRuntime(
    modelDir: File,
    customOpsLibrary: String,
    threads: Int = 2,
) : AutoCloseable {
    // OrtEnvironment is process-wide. Closing a runtime must not close other users' environment.
    private val environment = OrtEnvironment.getEnvironment()
    private val monitor = Any()
    private var closed = false
    private val sessions = load(modelDir, customOpsLibrary, threads)

    /** The caller dispatches this synchronous native work to a worker thread. */
    suspend fun translate(text: String, source: Language, target: Language): String {
        val context = coroutineContext
        context.ensureActive()
        return synchronized(monitor) {
            check(!closed) { "The translation engine has been closed." }
            if (source == target) return@synchronized text
            require(text.isNotBlank()) { "There is no recognized text to translate." }
            translateBlocking(text, source, target, context)
        }
    }

    private fun translateBlocking(
        text: String,
        source: Language,
        target: Language,
        context: CoroutineContext,
    ): String {
        val tokens = OnnxTensor.createTensor(environment, arrayOf(text)).use { input ->
            sessions.tokenizer.run(mapOf("text" to input)).use { result ->
                val buffer = result.tensor("tokens").intBuffer
                IntArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
        val ids = NllbTokens.encoderInput(tokens, source)
        context.ensureActive()
        return longTensor(ids, longArrayOf(1, ids.size.toLong())).use { inputIds ->
            longTensor(LongArray(ids.size) { 1 }, longArrayOf(1, ids.size.toLong())).use { mask ->
                sessions.encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).use { encoder ->
                    context.ensureActive()
                    val generated = generate(encoder.tensor("last_hidden_state"), mask, target, context)
                    context.ensureActive()
                    longTensor(generated, longArrayOf(generated.size.toLong())).use { outputIds ->
                        sessions.detokenizer.run(mapOf("ids" to outputIds)).use { result ->
                            context.ensureActive()
                            @Suppress("UNCHECKED_CAST")
                            val output = (result.tensor("text").value as Array<String>).single().trim()
                            check(output.isNotBlank()) { "Translation produced no text. Please try a shorter sentence." }
                            output
                        }
                    }
                }
            }
        }
    }

    private fun generate(
        hidden: OnnxTensor,
        mask: OnnxTensor,
        target: Language,
        context: CoroutineContext,
    ): LongArray {
        // On the first pass the decoder computes encoder K/V tensors. Subsequent cached-branch
        // encoder outputs are empty placeholders, so keep these first-pass tensors alive to the end.
        var first: OrtSession.Result? = null
        var previous: OrtSession.Result? = null
        val output = ArrayList<Int>()
        try {
            OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 16, 0, 64)).use { empty ->
                val inputs = decoderInputs(hidden, mask)
                CACHE_SUFFIXES.forEach { inputs["past_key_values.$it"] = empty }
                longTensor(longArrayOf(NllbTokens.EOS.toLong()), longArrayOf(1, 1)).use { start ->
                    OnnxTensor.createTensor(environment, booleanArrayOf(false)).use { branch ->
                        inputs["input_ids"] = start
                        inputs["use_cache_branch"] = branch
                        context.ensureActive()
                        first = sessions.decoder.run(inputs)
                        previous = first
                    }
                }
            }
            // The decoder-start token is EOS; NLLB's first generated token must be the target tag.
            var next = NllbTokens.languageId(target)
            OnnxTensor.createTensor(environment, booleanArrayOf(true)).use { branch ->
                repeat(NllbTokens.MAX_GENERATED_TOKENS) {
                    context.ensureActive()
                    val inputs = decoderInputs(hidden, mask)
                    inputs["use_cache_branch"] = branch
                    CACHE_SUFFIXES.forEach { suffix ->
                        val owner = if (".encoder." in suffix) first!! else previous!!
                        inputs["past_key_values.$suffix"] = owner.tensor("present.$suffix")
                    }
                    val result = longTensor(longArrayOf(next.toLong()), longArrayOf(1, 1)).use { token ->
                        inputs["input_ids"] = token
                        sessions.decoder.run(inputs)
                    }
                    val obsolete = previous
                    previous = result
                    if (obsolete !== first) obsolete?.close()
                    context.ensureActive()
                    next = NllbTokens.greedy(result.tensor("logits").floatBuffer)
                    if (next == NllbTokens.EOS) return NllbTokens.contentIds(output)
                    output.add(next)
                }
            }
            error("The sentence is too long to translate safely. Please hold to talk for a shorter sentence.")
        } finally {
            if (previous !== first) previous?.close()
            first?.close()
        }
    }

    private fun decoderInputs(hidden: OnnxTensor, mask: OnnxTensor) = linkedMapOf(
        "encoder_attention_mask" to mask,
        "encoder_hidden_states" to hidden,
    )

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

    private fun load(modelDir: File, customOpsLibrary: String, threads: Int): Sessions {
        require(threads in 1..8) { "Translation thread count must be between 1 and 8." }
        val encoder = File(modelDir, "encoder_model_quantized.onnx")
        val originalDecoder = File(modelDir, "decoder_model_merged_quantized.onnx")
        val vocabulary = File(modelDir, "sentencepiece.bpe.model")
        require(listOf(encoder, originalDecoder, vocabulary).all { it.isFile }) {
            "Install the offline translation model before translating."
        }
        // Preserve the verified public model pack. This fixed, hash-checked streaming patch removes
        // the projection's full-vocabulary float dequantization without copying weights into Java.
        val decoder = RuntimeModelPatch.prepare(
            originalDecoder, File(modelDir, "decoder-lmhead-q8.onnx"), NllbDecoderPatch.spec,
        )
        val opened = ArrayList<OrtSession>()
        fun remember(session: OrtSession) = session.also { opened.add(it) }
        try {
            val sentencePiece = vocabulary.readBytes()
            val tokenizer: OrtSession
            val detokenizer: OrtSession
            options(1).use { options ->
                options.registerCustomOpLibrary(customOpsLibrary)
                tokenizer = remember(environment.createSession(SentencePieceModels.encoder(sentencePiece), options))
                detokenizer = remember(environment.createSession(SentencePieceModels.decoder(sentencePiece), options))
            }
            options(threads).use { options ->
                val encoderSession = remember(environment.createSession(encoder.absolutePath, options))
                val decoderSession = remember(environment.createSession(decoder.absolutePath, options))
                return Sessions(tokenizer, detokenizer, encoderSession, decoderSession)
            }
        } catch (error: Throwable) {
            opened.asReversed().forEach { session ->
                runCatching { session.close() }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun options(threads: Int): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // Varying sentence/cache lengths otherwise leave large intermediate allocations resident.
            // Native CPU work is synchronous, so free temporary buffers after each run instead.
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            // Packing the very large vocabulary projection duplicates hundreds of MB. Running directly
            // from its quantized weights costs some CPU time but keeps the measured engine below 2 GiB.
            options.addConfigEntry("session.disable_prepacking", "1")
            // The exported weights are already quantized. Basic optimization avoids large weight copies
            // and lets this engine coexist with the app's recognition and speech models.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            return options
        } catch (error: Throwable) {
            runCatching { options.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun close() = synchronized(monitor) {
        if (!closed) {
            closed = true
            var failure: Throwable? = null
            listOf(sessions.decoder, sessions.encoder, sessions.detokenizer, sessions.tokenizer).forEach {
                try {
                    it.close()
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    private data class Sessions(
        val tokenizer: OrtSession,
        val detokenizer: OrtSession,
        val encoder: OrtSession,
        val decoder: OrtSession,
    )

    private fun OrtSession.Result.tensor(name: String): OnnxTensor =
        get(name).orElseThrow { IllegalStateException("Translation model is missing output '$name'.") } as OnnxTensor

    companion object {
        private val CACHE_SUFFIXES = (0 until 12).flatMap { layer ->
            listOf("$layer.decoder.key", "$layer.decoder.value", "$layer.encoder.key", "$layer.encoder.value")
        }
    }
}

/** Pure generation rules kept independently testable from the 900 MB native model. */
internal object NllbTokens {
    const val EOS = 2
    const val MAX_SOURCE_TOKENS = 256
    const val MAX_GENERATED_TOKENS = 256

    fun languageId(language: Language): Int = when (language) {
        Language.ENGLISH -> 256047
        Language.HINDI -> 256068
        Language.MARATHI -> 256116
        Language.GUJARATI -> 256064
        Language.BENGALI -> 256026
        Language.TAMIL -> 256170
        Language.TELUGU -> 256172
        Language.KANNADA -> 256083
        Language.MALAYALAM -> 256115
        Language.ODIA -> 256136
    }

    fun encoderInput(tokens: IntArray, source: Language): LongArray {
        require(tokens.size + 2 <= MAX_SOURCE_TOKENS) {
            "The sentence is too long to translate safely. Please hold to talk for a shorter sentence."
        }
        return LongArray(tokens.size + 2) { index ->
            when (index) {
                0 -> languageId(source).toLong()
                tokens.size + 1 -> EOS.toLong()
                else -> tokens[index - 1].toLong()
            }
        }
    }

    // Unknown (3) remains part of the text; silently deleting it could change the meaning.
    fun contentIds(tokens: List<Int>): LongArray = tokens.filter { it in 3..256000 }.map { it.toLong() }.toLongArray()

    fun greedy(logits: FloatBuffer): Int {
        require(logits.remaining() == 256206) { "Unexpected translation vocabulary size." }
        var maximum = Float.NEGATIVE_INFINITY
        var chosen = -1
        val start = logits.position()
        for (index in 0 until logits.remaining()) {
            val score = logits.get(start + index)
            check(score.isFinite()) { "Translation produced invalid scores. Please retry." }
            if (score > maximum) {
                maximum = score
                chosen = index
            }
        }
        check(chosen >= 0) { "Translation produced no token." }
        return chosen
    }
}

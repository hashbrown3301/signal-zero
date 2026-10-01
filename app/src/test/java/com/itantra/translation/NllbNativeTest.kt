package com.itantra.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.itantra.comm.Language
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.LongBuffer

/** Opt-in real CPU inference: no mocked tokenizer, weights, or translator. See validation docs. */
class NllbNativeTest {
    private fun modelDir(): File {
        val path = System.getenv("ITANTRA_NLLB_DIR")
        assumeTrue("Set ITANTRA_NLLB_DIR to the verified model folder for native tests", !path.isNullOrBlank())
        return File(checkNotNull(path))
    }
    private fun customLibrary(): String = checkNotNull(System.getenv("ITANTRA_ORTX_LIBRARY")) {
        "ITANTRA_ORTX_LIBRARY must point to the desktop ONNX Runtime Extensions library"
    }

    @Test fun nativeTokenizerMatchesReferenceAcrossAllTenScripts() {
        val vocabulary = File(modelDir(), "sentencepiece.bpe.model").readBytes()
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.registerCustomOpLibrary(customLibrary())
            env.createSession(SentencePieceModels.encoder(vocabulary), options).use { encode ->
                env.createSession(SentencePieceModels.decoder(vocabulary), options).use { decode ->
                    val cases = javaClass.getResourceAsStream("/nllb-tokenizer-golden.json")!!.bufferedReader().use {
                        Json.parseToJsonElement(it.readText()).jsonArray
                    }
                    assertEquals(10, cases.size)
                    for (element in cases) {
                        val case = element.jsonObject
                        val original = case.getValue("text").jsonPrimitive.content
                        val expected = case.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                        val ids = OnnxTensor.createTensor(env, arrayOf(original)).use { input ->
                            encode.run(mapOf("text" to input)).use { result ->
                                val buffer = (result.get("tokens").get() as OnnxTensor).intBuffer
                                IntArray(buffer.remaining()).also { buffer.get(it) }
                            }
                        }
                        assertArrayEquals(case.getValue("iso").jsonPrimitive.content, expected, ids)
                        OnnxTensor.createTensor(env, LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), longArrayOf(ids.size.toLong())).use { input ->
                            decode.run(mapOf("ids" to input)).use { result ->
                                @Suppress("UNCHECKED_CAST")
                                val actual = ((result.get("text").get() as OnnxTensor).value as Array<String>).single()
                                assertEquals(case.getValue("decoded").jsonPrimitive.content, actual)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun actualModelTranslatesAllTenLanguages() = runBlocking {
        val directory = modelDir()
        val start = System.nanoTime()
        NllbRuntime(directory, customLibrary()).use { runtime ->
            println("NLLB cold load ms=${(System.nanoTime() - start) / 1_000_000}")
            val phrases = mapOf(
                Language.ENGLISH to "Please bring water.", Language.HINDI to "कृपया पानी लाओ।",
                Language.MARATHI to "कृपया पाणी आणा.", Language.GUJARATI to "કૃપા કરીને પાણી લાવો.",
                Language.BENGALI to "দয়া করে জল আনুন।", Language.TAMIL to "தண்ணீர் கொண்டு வாருங்கள்.",
                Language.TELUGU to "దయచేసి నీరు తీసుకురండి.", Language.KANNADA to "ದಯವಿಟ್ಟು ನೀರನ್ನು ತನ್ನಿ.",
                Language.MALAYALAM to "ദയവായി വെള്ളം കൊണ്ടുവരിക.", Language.ODIA to "ଦୟାକରି ପାଣି ଆଣନ୍ତୁ।",
            )
            val directions = if (System.getenv("ITANTRA_NLLB_ALL_PAIRS") == "1") {
                Language.entries.flatMap { from -> Language.entries.filter { it != from }.map { from to it } }
            } else Language.entries.filter { it != Language.ENGLISH }.flatMap {
                listOf(Language.ENGLISH to it, it to Language.ENGLISH)
            }
            for ((source, target) in directions) {
                val before = System.nanoTime()
                val result = runtime.translate(phrases.getValue(source), source, target)
                val millis = (System.nanoTime() - before) / 1_000_000
                assertTrue("${source.iso}→${target.iso} produced no text", result.isNotBlank())
                assertFalse("Special language token leaked", "__" in result || "<s>" in result)
                println("NLLB ${source.iso}->${target.iso} ms=$millis output=$result")
            }
            val quantities = runtime.translate("Please bring 25 bottles, not 50.", Language.ENGLISH, Language.HINDI)
            val normalizedDigits = quantities.map { char ->
                Character.digit(char, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() } ?: char
            }.joinToString("")
            assertTrue("Quantity 25 was lost: $quantities", "25" in normalizedDigits)
            assertTrue("Quantity 50 was lost: $quantities", "50" in normalizedDigits)
            assertTrue("Negation was lost: $quantities", "नहीं" in quantities || "मत" in quantities || "ना" in quantities)
            val prohibition = runtime.translate("Do not open the door.", Language.ENGLISH, Language.HINDI)
            assertTrue("Negative instruction was lost: $prohibition", "मत" in prohibition || "नहीं" in prohibition || "ना" in prohibition)
            println("NLLB quantity/negation smoke: $quantities | $prohibition")
        }
    }
}

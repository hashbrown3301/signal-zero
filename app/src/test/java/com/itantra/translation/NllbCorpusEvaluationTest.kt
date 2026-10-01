package com.itantra.translation

import ai.onnxruntime.OrtEnvironment
import com.itantra.comm.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/** Opt-in public-reference measurements through the actual Kotlin engine, with no phone claims. */
class NllbCorpusEvaluationTest {
    @Test fun evaluateFixedHumanReferences() = runBlocking {
        assumeTrue("Set ITANTRA_RUN_NLLB_EVALUATION=1 to run public-reference inference",
            System.getenv("ITANTRA_RUN_NLLB_EVALUATION") == "1")
        val root = projectRoot()
        val corpusFile = File(System.getenv("ITANTRA_EVAL_CORPUS") ?: File(root, "dist/evaluation/corpus.json").path)
        val output = File(System.getenv("ITANTRA_EVAL_OUTPUT") ?: File(root, "dist/evaluation/results.json").path)
        val modelDir = File(checkNotNull(System.getenv("ITANTRA_NLLB_DIR")))
        val customLibrary = checkNotNull(System.getenv("ITANTRA_ORTX_LIBRARY"))
        val corpusBytes = corpusFile.readBytes()
        val corpusHash = hash(corpusBytes)
        val corpus = Json.parseToJsonElement(corpusBytes.decodeToString()).jsonObject
        require(corpus.getValue("schema_version").jsonPrimitive.int == 1)
        val checksum = File(corpusFile.path + ".sha256")
        require(checksum.isFile && checksum.readText().trim() == corpusHash) { "Corpus checksum is absent or changed" }
        require(corpus.getValue("tokenizer").jsonObject.getValue("source_limit").jsonPrimitive.int == NllbTokens.MAX_SOURCE_TOKENS)
        val cases = corpus.getValue("cases").jsonArray.map { it.jsonObject }
        val ids = cases.map { it.getValue("id").jsonPrimitive.content }
        require(ids.toSet().size == ids.size) { "Duplicate corpus IDs" }
        val expectedPerPair = corpus.getValue("examples_per_pair").jsonPrimitive.int
        val counts = cases.groupingBy { "${it.string("source")}->${it.string("target")}" }.eachCount()
        val expectedPairs = Language.entries.flatMap { from ->
            Language.entries.filter { it != from }.map { "${from.iso}->${it.iso}" }
        }.toSet()
        require(counts.keys == expectedPairs && counts.values.all { it == expectedPerPair }) { "Incomplete corpus directional coverage" }

        val verificationStart = System.nanoTime()
        val modelFiles = TranslationModelSpec.files.map { spec ->
            val file = File(modelDir, spec.name)
            require(file.isFile && file.length() == spec.size) { "Model file size mismatch: ${spec.name}" }
            val digest = file.inputStream().use(::hash)
            require(digest == spec.sha256) { "Model file checksum mismatch: ${spec.name}" }
            buildJsonObject { put("name", spec.name); put("sha256", digest); put("bytes", file.length()) }
        }
        val verificationMs = elapsed(verificationStart)
        val configuration = buildJsonObject {
            put("model_id", TranslationModelSpec.id)
            put("model_revision", TranslationModelSpec.revision)
            put("model_files", JsonArray(modelFiles))
            put("derived_decoder_sha256", NllbDecoderPatch.spec.targetSha256)
            put("onnx_runtime", OrtEnvironment.getEnvironment().version)
            put("custom_ops_sha256", File(customLibrary).inputStream().use(::hash))
            put("app_commit", System.getenv("ITANTRA_EVAL_COMMIT") ?: git(root, "rev-parse", "HEAD"))
            put("working_tree_dirty", git(root, "status", "--porcelain").isNotBlank())
            put("source_tree_sha256", sourceHash(root))
            put("runtime_class_sha256", NllbRuntime::class.java.getResourceAsStream("NllbRuntime.class")!!.use(::hash))
            put("evaluation_harness_sha256", File(root, "app/src/test/java/com/itantra/translation/NllbCorpusEvaluationTest.kt").inputStream().use(::hash))
            put("intra_op_threads", 2); put("inter_op_threads", 1)
            put("execution", "CPU sequential"); put("optimization", "BASIC_OPT")
            put("cpu_arena", false); put("memory_pattern", false); put("prepacking", false)
            put("translation_result_cache", false); put("generation", "cached greedy")
            put("source_token_limit", NllbTokens.MAX_SOURCE_TOKENS)
            put("generated_token_limit", NllbTokens.MAX_GENERATED_TOKENS)
            put("per_case_cooperative_timeout_ms", 60_000)
            put("environment", buildJsonObject {
                put("os", System.getProperty("os.name")); put("architecture", System.getProperty("os.arch"))
                put("java_version", System.getProperty("java.version"))
                put("available_processors", Runtime.getRuntime().availableProcessors())
                File("/sys/fs/cgroup/cpu.max").takeIf { it.isFile }?.let { put("cgroup_cpu_max", it.readText().trim()) }
                File("/sys/fs/cgroup/memory.max").takeIf { it.isFile }?.let { put("cgroup_memory_max", it.readText().trim()) }
            })
        }
        val configurationJson = configuration.toString()
        val configurationHash = hash(configurationJson.encodeToByteArray())
        val rows = mutableListOf<JsonObject>()
        if (output.isFile) {
            val previous = Json.parseToJsonElement(output.readText()).jsonObject
            require(previous.string("corpus_sha256") == corpusHash && previous.string("configuration_sha256") == configurationHash) {
                "Existing results have a different corpus/runtime configuration; choose a new output path"
            }
            rows.addAll(previous.getValue("results").jsonArray.map { it.jsonObject })
            val completed = rows.map { it.string("id") }
            require(completed.toSet().size == completed.size && completed.all { it in ids }) { "Invalid checkpoint IDs" }
            rows.forEach { row ->
                val case = cases.first { it.string("id") == row.string("id") }
                require(listOf("source", "target", "source_text", "reference", "source_tokens").all { row[it] == case[it] }) {
                    "Checkpoint case differs from the corpus"
                }
            }
        }
        val resumedCases = rows.size
        val started = Instant.now().toString()
        var complete = false
        var loadMs: Double? = null
        var fatal: String? = null
        fun persist() {
            val report = buildJsonObject {
                put("schema_version", 1); put("started_at_utc", started); put("updated_at_utc", Instant.now().toString())
                put("complete", complete); put("corpus_sha256", corpusHash)
                put("configuration", configuration); put("configuration_json", configurationJson)
                put("configuration_sha256", configurationHash)
                put("model_verification_ms", verificationMs)
                loadMs?.let { put("cold_engine_load_ms", it) }
                fatal?.let { put("fatal_error", it) }
                put("measurement_scope", "Cloud JVM CPU, actual Kotlin text translation; references are human FLORES-200")
                put("reference_provenance", corpus.getValue("provenance"))
                put("resumed_cases", resumedCases)
                put("limitations", "No phone, audio or human assessment; two items per direction; cooperative timeout cannot interrupt an in-progress native operator")
                put("results", JsonArray(rows))
            }
            output.parentFile?.mkdirs()
            val temporary = File(output.parentFile, ".${output.name}.partial")
            temporary.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report) + "\n")
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        persist()
        try {
            val beforeLoad = System.nanoTime()
            NllbRuntime(modelDir, customLibrary, threads = 2).use { runtime ->
                loadMs = elapsed(beforeLoad)
                val completed = rows.map { it.string("id") }.toSet()
                for (case in cases) {
                    if (case.string("id") in completed) continue
                    val before = System.nanoTime()
                    var outcome = "success"
                    var error: String? = null
                    var hypothesis = ""
                    if (!case.getValue("source_supported").jsonPrimitive.boolean) {
                        outcome = "source_limit"
                        error = "Source has ${case["source_tokens"]} tokens, above ${NllbTokens.MAX_SOURCE_TOKENS}; fixed case retained"
                    } else {
                        try {
                            hypothesis = withTimeout(60_000) {
                                withContext(Dispatchers.Default) {
                                    runtime.translate(case.string("source_text"), language(case.string("source")), language(case.string("target")))
                                }
                            }
                            check(hypothesis.isNotBlank()) { "Empty translated output" }
                        } catch (failure: Exception) {
                            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                            hypothesis = ""
                            outcome = when {
                                failure is TimeoutCancellationException -> "timeout"
                                failure.message?.contains("too long", ignoreCase = true) == true -> "generation_limit"
                                else -> "runtime_error"
                            }
                            error = "${failure.javaClass.simpleName}: ${failure.message}"
                        }
                    }
                    val row = buildJsonObject {
                        for (key in listOf("id", "source", "target", "sentence_id", "source_text", "reference", "source_tokens")) put(key, case.getValue(key))
                        put("hypothesis", hypothesis); put("outcome", outcome); put("translation_ms", elapsed(before))
                        error?.let { put("error", it) }
                    }
                    rows.add(row); persist()
                    println("FLORES_EVAL ${rows.size}/${cases.size} ${case.string("id")} outcome=$outcome ms=${row["translation_ms"]}")
                }
            }
            complete = true
        } catch (failure: Exception) {
            fatal = "${failure.javaClass.simpleName}: ${failure.message}"
            throw failure
        } finally {
            persist()
        }
        assertEquals("Every selected result, including failures, must be retained", cases.size, rows.size)
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun language(iso: String) = Language.entries.single { it.iso == iso }
    private fun projectRoot() = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000.0
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sourceHash(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(root, "app/src/main/java/com/itantra/translation").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach {
                digest.update(it.relativeTo(root).path.encodeToByteArray()); digest.update(0.toByte()); digest.update(it.readBytes())
            }
        return digest.digest().hex()
    }
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "Cannot record Git provenance: $output" }
        return output
    }
}

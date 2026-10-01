package com.itantra.translation

import ai.onnxruntime.OrtEnvironment
import com.itantra.comm.Language
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.math.ceil

/** Opt-in desktop measurements of the app's real runtime; never labeled as Android phone results. */
class NllbBenchmarkTest {
    @Test fun benchmarkCurrentOfflineRuntime() = runBlocking {
        assumeTrue("Set ITANTRA_RUN_NLLB_BENCHMARK=1 to run the native benchmark",
            System.getenv("ITANTRA_RUN_NLLB_BENCHMARK") == "1")
        val directory = File(checkNotNull(System.getenv("ITANTRA_NLLB_DIR")) {
            "ITANTRA_NLLB_DIR must point to the verified original model pack"
        })
        val library = checkNotNull(System.getenv("ITANTRA_ORTX_LIBRARY")) {
            "ITANTRA_ORTX_LIBRARY must point to the desktop ONNX Runtime Extensions library"
        }
        val repeats = (System.getenv("ITANTRA_BENCH_REPEATS") ?: "5").toInt().also {
            require(it in 1..20) { "ITANTRA_BENCH_REPEATS must be between 1 and 20" }
        }
        val output = System.getenv("ITANTRA_BENCH_OUTPUT")?.let(::File)
            ?: File(projectRoot(), "dist/benchmarks/current-translation.json")
        val startedAt = Instant.now().toString()
        val started = System.nanoTime()
        val memoryBeforeLoad = memory()
        val derivedAlreadyPresent = File(directory, "decoder-lmhead-q8.onnx").isFile
        val cases = benchmarkCases()
        val rows = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        var coldLoadMs: Double? = null
        var memoryAfterLoad: JsonObject? = null
        var firstTranslation: JsonObject? = null
        var complete = false

        fun persist() {
            val report = buildJsonObject {
                put("schema_version", 1)
                put("started_at_utc", startedAt)
                put("updated_at_utc", Instant.now().toString())
                put("complete", complete)
                put("successful", complete && errors.isEmpty())
                put("elapsed_ms", elapsedMs(started))
                put("measurement_scope", "Desktop cloud CPU: actual Kotlin translation stage only")
                put("limitations", JsonArray(listOf(
                    "No Android phone, emulator, recognition, synthesis, audio, or network timing is measured.",
                    "Warm samples execute inference each time; OfflineTranslator's text-result cache is bypassed.",
                    "Cold load creates a new engine; operating-system file caches are not flushed.",
                    "VmHWM is cumulative process peak RSS, including the JVM and test libraries.",
                    "Percentiles use empirical nearest rank; five samples cannot characterize a reliable tail.",
                    "Numeric checks and raw outputs are regression evidence, not a multilingual accuracy score.",
                ).map(::JsonPrimitive)))
                put("environment", environment())
                put("model", buildJsonObject {
                    put("id", TranslationModelSpec.id)
                    put("revision", TranslationModelSpec.revision)
                    put("derived_decoder_sha256", NllbDecoderPatch.spec.targetSha256)
                    put("derived_decoder_already_present", derivedAlreadyPresent)
                    put("configured_source_files", JsonArray(TranslationModelSpec.files.map { file ->
                        buildJsonObject {
                            put("name", file.name)
                            put("bytes", file.size)
                            put("sha256", file.sha256)
                        }
                    }))
                    put("license", "CC BY-NC 4.0")
                    put("license_url", "https://creativecommons.org/licenses/by-nc/4.0/")
                    put("model_card_url", "https://huggingface.co/facebook/nllb-200-distilled-600M")
                })
                put("configuration", buildJsonObject {
                    put("intra_op_threads", 2)
                    put("inter_op_threads", 1)
                    put("execution", "CPU sequential")
                    put("optimization", "BASIC_OPT")
                    put("cpu_arena", false)
                    put("memory_pattern", false)
                    put("prepacking", false)
                    put("translation_result_cache", false)
                    put("source_token_limit", NllbTokens.MAX_SOURCE_TOKENS)
                    put("generated_token_limit", NllbTokens.MAX_GENERATED_TOKENS)
                    put("repeats_per_case", repeats)
                    put("warmups_per_case", 1)
                    put("case_count", cases.size)
                    put("percentile_method", "nearest_rank")
                })
                put("memory_before_load", memoryBeforeLoad)
                coldLoadMs?.let { put("cold_load_ms", it) }
                memoryAfterLoad?.let { put("memory_after_load", it) }
                firstTranslation?.let { put("first_translation", it) }
                put("cases", JsonArray(rows))
                put("errors", JsonArray(errors.map(::JsonPrimitive)))
                put("final_process_memory", memory())
            }
            output.parentFile?.mkdirs()
            val temporary = File(output.parentFile, ".${output.name}.partial")
            temporary.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report) + "\n")
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        try {
            val loadStarted = System.nanoTime()
            NllbRuntime(directory, library, threads = 2).use { runtime ->
                coldLoadMs = elapsedMs(loadStarted)
                memoryAfterLoad = memory()
                val initial = cases.first()
                val beforeFirst = System.nanoTime()
                val firstOutput = runtime.translate(initial.text, initial.source, initial.target)
                firstTranslation = buildJsonObject {
                    put("case_id", initial.id)
                    put("translation_ms", elapsedMs(beforeFirst))
                    put("output", firstOutput)
                    put("memory_after", memory())
                }
                persist()
                println("NLLB_BENCH cold_load_ms=$coldLoadMs first_translation=$firstTranslation")
                for (case in cases) {
                    val beforeCase = memory()
                    val samples = mutableListOf<JsonObject>()
                    val timings = mutableListOf<Double>()
                    var failure: String? = null
                    var warmupMs: Double? = null
                    try {
                        val warmupStarted = System.nanoTime()
                        val warmup = runtime.translate(case.text, case.source, case.target)
                        check(warmup.isNotBlank()) { "Warmup returned no translated text" }
                        warmupMs = elapsedMs(warmupStarted)
                        repeat(repeats) { index ->
                            val before = System.nanoTime()
                            val translated = runtime.translate(case.text, case.source, case.target)
                            val millis = elapsedMs(before)
                            check(translated.isNotBlank()) { "Sample returned no translated text" }
                            timings.add(millis)
                            samples.add(buildJsonObject {
                                put("sample", index + 1)
                                put("translation_ms", millis)
                                put("output", translated)
                                put("output_codepoints", translated.codePointCount(0, translated.length))
                                put("numeric_tokens", JsonArray(numericTokens(translated).map(::JsonPrimitive)))
                                put("numeric_tokens_preserved", numericTokens(case.text).sorted() == numericTokens(translated).sorted())
                                put("memory_after", memory())
                            })
                        }
                    } catch (error: Exception) {
                        failure = "${error.javaClass.simpleName}: ${error.message}"
                        errors.add("${case.id}: $failure")
                    }
                    val row = buildJsonObject {
                        put("id", case.id)
                        put("source", case.source.iso)
                        put("target", case.target.iso)
                        put("length", case.length)
                        put("input", case.text)
                        put("input_codepoints", case.text.codePointCount(0, case.text.length))
                        put("input_whitespace_words", case.text.trim().split(Regex("\\s+")).size)
                        put("input_numeric_tokens", JsonArray(numericTokens(case.text).map(::JsonPrimitive)))
                        put("quality_review_tags", JsonArray(case.reviewTags.map(::JsonPrimitive)))
                        put("memory_before", beforeCase)
                        put("memory_after", memory())
                        warmupMs?.let { put("warmup_ms", it) }
                        put("samples", JsonArray(samples))
                        put("completed_samples", timings.size)
                        if (timings.isNotEmpty()) {
                            put("p50_ms", percentile(timings, 0.50))
                            put("p90_ms", percentile(timings, 0.90))
                            put("p95_ms", percentile(timings, 0.95))
                            put("minimum_ms", timings.min())
                            put("maximum_ms", timings.max())
                            put("mean_ms", timings.average())
                        }
                        failure?.let { put("error", it) }
                    }
                    rows.add(row)
                    persist()
                    println("NLLB_BENCH ${case.id} samples=${timings.size} p50_ms=${timings.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) }} p95_ms=${timings.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) }} error=$failure")
                }
            }
            complete = true
        } catch (error: Exception) {
            errors.add("Benchmark failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            persist()
            println("NLLB_BENCH report=${output.absolutePath}")
        }
        assertEquals("Some cases failed; the report preserves input, partial samples, and explicit errors", emptyList<String>(), errors)
    }

    private data class Case(
        val source: Language, val target: Language, val length: String,
        val text: String, val reviewTags: List<String>,
    ) {
        val id = "${source.iso}-${target.iso}-$length"
    }

    private fun benchmarkCases(): List<Case> {
        val english = linkedMapOf(
            "short" to "Please bring water.",
            "medium" to "Please bring 25 bottles of water to the station tomorrow morning, not 50. Do not open the door before I arrive at 10:30.",
            "long" to "My name is Rohan. Please bring 25 bottles of water to the station tomorrow morning, not 50. The train arrives at 10:30. Do not open the door before I arrive. We will meet near the main entrance and check the tickets together before the train leaves.",
        )
        val hindi = mapOf(
            "short" to "कृपया पानी लाओ।",
            "medium" to "कृपया कल सुबह स्टेशन पर पानी की 25 बोतलें लाओ, 50 नहीं। मेरे 10:30 पर आने से पहले दरवाज़ा मत खोलो।",
            "long" to "मेरा नाम रोहन है। कृपया कल सुबह स्टेशन पर पानी की 25 बोतलें लाओ, 50 नहीं। ट्रेन 10:30 पर आती है। मेरे आने से पहले दरवाज़ा मत खोलो। हम मुख्य प्रवेश द्वार के पास मिलेंगे और ट्रेन के जाने से पहले साथ में टिकटों की जाँच करेंगे।",
        )
        val directions = listOf(
            Language.ENGLISH to Language.HINDI, Language.HINDI to Language.ENGLISH,
            Language.ENGLISH to Language.TELUGU, Language.ENGLISH to Language.ODIA,
            Language.ENGLISH to Language.TAMIL,
        )
        return directions.flatMap { (source, target) ->
            english.keys.map { length ->
                val tags = when (length) {
                    "short" -> listOf("simple_request")
                    "medium" -> listOf("quantities", "negation", "time")
                    else -> listOf("quantities", "negation", "time", "named_entity", "multiple_sentences")
                }
                Case(source, target, length, (if (source == Language.ENGLISH) english else hindi).getValue(length), tags)
            }
        }
    }

    private fun percentile(samples: List<Double>, fraction: Double): Double =
        samples.sorted()[(ceil(fraction * samples.size).toInt() - 1).coerceIn(0, samples.lastIndex)]

    private fun numericTokens(text: String): List<String> {
        val normalized = text.map { character ->
            Character.digit(character, 10).takeIf { it >= 0 }?.let { ('0'.code + it).toChar() } ?: character
        }.joinToString("")
        return Regex("[0-9]+").findAll(normalized).map { it.value }.toList()
    }

    private fun memory(): JsonObject = buildJsonObject {
        val status = File("/proc/self/status").takeIf { it.isFile }?.readLines().orEmpty()
        for ((key, field) in listOf("VmRSS:" to "process_rss_bytes", "VmHWM:" to "process_peak_rss_bytes")) {
            status.firstOrNull { it.startsWith(key) }?.substringAfter(':')?.trim()
                ?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.let { put(field, it * 1024) }
        }
        val runtime = Runtime.getRuntime()
        put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
        put("java_heap_committed_bytes", runtime.totalMemory())
        put("java_heap_limit_bytes", runtime.maxMemory())
    }

    private fun environment(): JsonObject = buildJsonObject {
        put("os", System.getProperty("os.name"))
        put("architecture", System.getProperty("os.arch"))
        put("java_version", System.getProperty("java.version"))
        put("app_commit", System.getenv("ITANTRA_BENCH_COMMIT"))
        put("onnx_runtime", OrtEnvironment.getEnvironment().version)
        put("jvm_available_processors", Runtime.getRuntime().availableProcessors())
        File("/sys/fs/cgroup/cpu.max").takeIf { it.isFile }?.readText()?.trim()?.let { put("cgroup_cpu_max", it) }
        File("/sys/fs/cgroup/memory.max").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            ?.let { put("cgroup_memory_limit_bytes", it) }
    }

    private fun projectRoot(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile } ?: File(System.getProperty("user.dir"))

    private fun elapsedMs(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0
}

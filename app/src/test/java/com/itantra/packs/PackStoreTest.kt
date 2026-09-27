package com.itantra.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val model = ByteArray(5_000) { (it % 251).toByte() }
    private val tokens = "அ 0\nஆ 1\n".encodeToByteArray()

    private fun manifest(id: String = "ta-listen", files: Map<String, ByteArray> = mapOf("model.onnx" to model, "tokens.txt" to tokens),
                         extra: String = "") = """
        {"format": 1, "id": "$id", "lang": "ta", "packet_code": 6, "name": "Tamil", "native": "தமிழ்",
         "kind": "listen", "engine": {"type": "mms", "model": "model.onnx", "tokens": "tokens.txt", "sample_rate": 16000},
         "files": [${files.entries.joinToString { (p, b) -> """{"path": "$p", "size": ${b.size}, "sha256": "${sha(b)}"}""" }}],
         "size": ${files.values.sumOf { it.size }}, "sources": [{"url": "https://example", "licence": "CC-BY-NC-4.0"}],
         "future_field": 1 $extra}
    """.trimIndent()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun goodZip(id: String = "ta-listen") = zip(
        mapOf("$id/pack.json" to manifest(id).encodeToByteArray(), "$id/model.onnx" to model, "$id/tokens.txt" to tokens)
    )

    private fun store() = PackStore(tmp.newFolder("packs"))

    @Test
    fun installsListsAndDeletes() {
        val store = store()
        val m = store.install(ByteArrayInputStream(goodZip()))
        assertEquals("ta-listen", m.id)
        assertEquals(6, m.packetCode)
        assertEquals("தமிழ்", m.native)
        assertTrue(m.isListen)
        assertEquals(listOf("ta-listen"), store.list().map { it.id })
        assertEquals(model.toList(), store.dir("ta-listen").resolve("model.onnx").readBytes().toList())

        assertTrue(store.delete("ta-listen"))
        assertTrue(store.list().isEmpty())
        assertFalse(store.delete("ta-listen"))
    }

    @Test
    fun tamperedFileIsRejectedAndNothingIsInstalled() {
        val store = store()
        val bad = model.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val zip = zip(mapOf("ta-listen/pack.json" to manifest().encodeToByteArray(), "ta-listen/model.onnx" to bad,
                            "ta-listen/tokens.txt" to tokens))
        val e = assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(zip)) }
        assertTrue(e.message, e.message!!.contains("SHA-256"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun failedReinstallKeepsTheWorkingPack() {
        val store = store()
        store.install(ByteArrayInputStream(goodZip()))
        val truncated = zip(mapOf("ta-listen/pack.json" to manifest().encodeToByteArray(), "ta-listen/model.onnx" to model.copyOf(10),
                                  "ta-listen/tokens.txt" to tokens))
        assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(truncated)) }
        assertEquals(model.size.toLong(), store.dir("ta-listen").resolve("model.onnx").length())
        assertEquals(listOf("ta-listen"), store.list().map { it.id })
    }

    @Test
    fun reinstallReplaces() {
        val store = store()
        store.install(ByteArrayInputStream(goodZip()))
        store.install(ByteArrayInputStream(goodZip()))
        assertEquals(1, store.list().size)
    }

    @Test
    fun zipSlipIsRejected() {
        val store = store()
        val evil = zip(mapOf("ta-listen/pack.json" to manifest().encodeToByteArray(), "../../evil.txt" to "x".encodeToByteArray()))
        assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(evil)) }
        assertFalse(tmp.root.resolve("evil.txt").exists())
    }

    @Test
    fun missingOrUnexpectedFilesAreRejected() {
        val store = store()
        val missing = zip(mapOf("ta-listen/pack.json" to manifest().encodeToByteArray(), "ta-listen/model.onnx" to model))
        assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(missing)) }
        val extra = zip(mapOf("ta-listen/pack.json" to manifest().encodeToByteArray(), "ta-listen/model.onnx" to model,
                              "ta-listen/tokens.txt" to tokens, "ta-listen/run-me.sh" to "x".encodeToByteArray()))
        assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(extra)) }
    }

    @Test
    fun folderMustMatchPackId() {
        val store = store()
        val renamed = zip(mapOf("hi-listen/pack.json" to manifest("ta-listen").encodeToByteArray(),
                                "hi-listen/model.onnx" to model, "hi-listen/tokens.txt" to tokens))
        assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(renamed)) }
    }

    @Test
    fun newerPackFormatIsRejected() {
        val store = store()
        val newer = manifest().replace("\"format\": 1", "\"format\": 99")
        val zip = zip(mapOf("ta-listen/pack.json" to newer.encodeToByteArray(), "ta-listen/model.onnx" to model,
                            "ta-listen/tokens.txt" to tokens))
        val e = assertThrows(PackException::class.java) { store.install(ByteArrayInputStream(zip)) }
        assertTrue(e.message, e.message!!.contains("newer"))
        assertNull(store.get("ta-listen"))
    }

    @Test
    fun parsesRealBuilderManifest() {
        // Shape written by scripts/packs/build_pack.py (ml-listen before step 3), incl. keys the app doesn't use.
        val real = """{"format": 1, "id": "ml-listen", "lang": "ml", "packet_code": 9, "name": "Malayalam", "native": "മലയാളം",
            "kind": "listen", "engine": {"type": "piper", "model": "model.int8.onnx", "tokens": "tokens.txt", "needs": ["espeak-ng-data"]},
            "files": [{"path": "model.int8.onnx", "size": 18343827, "sha256": "f48eb6ab"}], "size": 18345103,
            "sources": [{"url": "https://github.com/x", "file": "m.onnx", "licence": "see MODEL_CARD"}],
            "built": "2026-09-27T19:20:51Z", "builder": "scripts/packs/build_pack.py @ 2b3485b"}"""
        val m = PackManifest.parse(real)
        assertEquals(listOf("espeak-ng-data"), m.engine.needs)
        assertEquals(9, m.packetCode)
    }
}

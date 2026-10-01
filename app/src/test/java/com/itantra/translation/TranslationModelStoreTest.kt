package com.itantra.translation

import com.itantra.packs.PackDownloader
import com.itantra.packs.PackException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class TranslationModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payloads = linkedMapOf(
        "encoder.bin" to "verified encoder".toByteArray(),
        "decoder.bin" to "verified decoder".toByteArray(),
        "tokens.bin" to "verified tokenizer".toByteArray(),
    )
    private val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val serverThread = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) { runCatching { socket.use { serve(it) } } }
        }
    }

    @After fun stop() = server.close()

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = reader.readLine()?.split(' ')?.getOrNull(1)?.removePrefix("/") ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        requests += path to range
        val data = payloads.getValue(path)
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        val body = data.copyOfRange(start, data.size)
        val status = if (range == null) "200 OK" else "206 Partial Content"
        val header = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
    }

    private fun spec(): List<ModelFile> = payloads.map { (name, bytes) ->
        ModelFile(name, "http://127.0.0.1:${server.localPort}/$name", bytes.size.toLong(), sha(bytes))
    }

    private fun store(spec: List<ModelFile> = spec()): TranslationModelStore =
        TranslationModelStore(tmp.newFolder("models"), tmp.newFolder("downloads"), spec)

    private fun zip(entries: List<Pair<String, ByteArray>> = payloads.toList()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun manifest(files: List<ModelFile> = spec(), revision: String = TranslationModelSpec.revision): ByteArray {
        val records = files.joinToString(",") { "{\"name\":\"${it.name}\",\"size\":${it.size},\"sha256\":\"${it.sha256}\"}" }
        return "{\"format\":1,\"id\":\"${TranslationModelSpec.id}\",\"revision\":\"$revision\",\"files\":[$records],\"attribution\":\"upstream authors\",\"extra_provenance\":true}".toByteArray()
    }

    @Test fun pinsEveryPublicModelArtifactToARevisionAndChecksum() {
        assertEquals(899_478_308L, TranslationModelSpec.totalBytes)
        assertEquals(3, TranslationModelSpec.files.size)
        assertTrue(TranslationModelSpec.files.all { it.url.contains("/resolve/${TranslationModelSpec.revision}/") })
        assertTrue(TranslationModelSpec.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals("CC-BY-NC-4.0", TranslationModelSpec.license)
    }

    @Test fun importsVerifiedModelWithOptionalProvenanceAndWorksInAnotherStoreInstance() {
        val root = tmp.newFolder("models")
        val work = tmp.newFolder("downloads")
        val expected = spec()
        val store = TranslationModelStore(root, work, expected)
        assertFalse(store.ready())
        assertNull(store.modelDir)
        store.importZip(ByteArrayInputStream(zip(payloads.toList() + listOf(
            "model.json" to manifest(), "LICENSE" to "CC-BY-NC-4.0".toByteArray(), "README.md" to "Source model".toByteArray(),
        ))))
        assertTrue(store.ready())
        payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, File(store.modelDir, name).readBytes()) }
        assertTrue(File(store.modelDir, "model.json").readText().contains("CC-BY-NC-4.0"))
        assertTrue(TranslationModelStore(root, work, expected).ready())
        assertTrue(requests.isEmpty())
    }

    @Test fun invalidReplacementDoesNotModifyAnExistingReadyInstallation() {
        val store = store()
        store.importZip(ByteArrayInputStream(zip()))
        val original = store.modelDir
        val invalidArchives = listOf(
            zip(payloads.toList().dropLast(1)),
            zip(payloads.toList().map { if (it.first == "encoder.bin") it.first to ByteArray(it.second.size) else it }),
            zip(payloads.toList().map { if (it.first == "encoder.bin") it.first to it.second.copyOf(it.second.size - 1) else it }),
            zip(payloads.toList() + ("model.json" to manifest(revision = "untrusted-revision"))),
            zip(payloads.toList() + ("unknown.bin" to byteArrayOf(1))),
            zip(payloads.toList() + ("README.md" to ByteArray(65_537))),
        )
        invalidArchives.forEach { archive ->
            assertThrows(PackException::class.java) { store.importZip(ByteArrayInputStream(archive)) }
            assertTrue(store.ready())
            assertEquals(original, store.modelDir)
            payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, File(original, name).readBytes()) }
        }
    }

    @Test fun rejectsTraversalAbsoluteAndNestedPathsWithoutWritingOutsideStaging() {
        val store = store()
        listOf("../escaped.bin", "/escaped.bin", "models/encoder.bin", "..\\escaped.bin", "folder/").forEach { path ->
            assertThrows(PackException::class.java) {
                store.importZip(ByteArrayInputStream(zip(listOf(path to byteArrayOf(1)))))
            }
            assertFalse(store.ready())
        }
        assertFalse(File(tmp.root, "escaped.bin").exists())
        assertTrue(File(tmp.root, "models").listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsDuplicateZipEntries() {
        val store = store()
        val archive = zip(payloads.toList() + ("encoder.ban" to payloads.getValue("encoder.bin")))
        val source = "encoder.ban".toByteArray()
        val replacement = "encoder.bin".toByteArray()
        for (offset in 0..archive.size - source.size) {
            if (source.indices.all { archive[offset + it] == source[it] }) replacement.copyInto(archive, offset)
        }
        val error = assertThrows(PackException::class.java) { store.importZip(ByteArrayInputStream(archive)) }
        assertTrue(error.message!!.contains("Duplicate"))
        assertFalse(store.ready())
    }

    @Test fun limitsUnpackedModelBytesEvenForHighlyCompressedInput() {
        val store = store()
        val archive = zip(listOf("encoder.bin" to ByteArray(2_000_000)))
        assertTrue(archive.size < 10_000)
        val error = assertThrows(PackException::class.java) { store.importZip(ByteArrayInputStream(archive)) }
        assertTrue(error.message!!.contains("larger"))
        assertFalse(store.ready())
        assertTrue(File(tmp.root, "models").listFiles().orEmpty().isEmpty())
    }

    @Test fun detectsMissingTruncatedAndUntrustedInstallationManifestOffline() {
        val store = store()
        store.importZip(ByteArrayInputStream(zip()))
        File(store.modelDir, "tokens.bin").writeBytes(byteArrayOf(1))
        assertFalse(store.ready())
        assertNull(store.modelDir)
        store.importZip(ByteArrayInputStream(zip()))
        File(store.modelDir, "tokens.bin").delete()
        assertFalse(store.ready())
        store.importZip(ByteArrayInputStream(zip()))
        File(store.modelDir, "model.json").writeBytes(manifest(revision = "wrong"))
        assertFalse(store.ready())
        assertTrue(requests.isEmpty())
    }

    @Test fun repeatedImportsKeepTheExistingVerifiedModel() {
        val store = store()
        store.importZip(ByteArrayInputStream(zip()))
        val original = store.modelDir
        store.importZip(ByteArrayInputStream(zip()))
        assertEquals(original, store.modelDir)
        assertEquals(1, File(tmp.root, "models").listFiles().orEmpty().count { it.isDirectory })
    }

    @Test fun downloadsAllFilesBeforeActivatingAndReportsCombinedProgress() {
        val store = store()
        val total = payloads.values.sumOf { it.size.toLong() }
        var last = -1L
        store.download { bytes, expected ->
            assertEquals(total, expected)
            assertTrue(bytes >= last)
            assertTrue(bytes in 0..total)
            last = bytes
            true
        }
        assertEquals(total, last)
        assertTrue(store.ready())
        assertEquals(payloads.keys, requests.map { it.first }.toSet())
        payloads.forEach { (name, bytes) -> assertArrayEquals(bytes, File(store.modelDir, name).readBytes()) }
        requests.clear()
        store.download { bytes, expected -> assertEquals(expected, bytes); true }
        assertTrue(requests.isEmpty())
    }

    @Test fun damagedDownloadNeverBecomesReady() {
        val expected = spec()
        payloads["decoder.bin"] = ByteArray(payloads.getValue("decoder.bin").size)
        val store = store(expected)
        assertThrows(PackException::class.java) { store.download { _, _ -> true } }
        assertFalse(store.ready())
        assertNull(store.modelDir)
        assertFalse(File(tmp.root, "downloads/${TranslationModelSpec.id}/decoder.bin.part").exists())
    }

    @Test fun cancelledDownloadResumesWithoutLosingEarlierVerifiedFiles() {
        payloads["decoder.bin"] = ByteArray(1_000_000) { (it % 251).toByte() }
        val store = store()
        val firstSize = payloads.getValue("encoder.bin").size.toLong()
        assertThrows(PackDownloader.Cancelled::class.java) {
            store.download { bytes, _ -> bytes < firstSize + 400_000 }
        }
        assertFalse(store.ready())
        assertNotNull(File(tmp.root, "downloads/${TranslationModelSpec.id}/encoder.bin").takeIf { it.isFile })
        assertTrue(File(tmp.root, "downloads/${TranslationModelSpec.id}/decoder.bin.part").length() in 400_000..999_999)
        requests.clear()
        store.download { _, _ -> true }
        assertTrue(store.ready())
        assertFalse(requests.any { it.first == "encoder.bin" })
        assertTrue(requests.first { it.first == "decoder.bin" }.second!!.startsWith("bytes="))
    }

    @Test fun cancellationBeforeDownloadingMakesNoNetworkRequests() {
        val store = store()
        assertThrows(PackDownloader.Cancelled::class.java) { store.download { _, _ -> false } }
        assertFalse(store.ready())
        assertTrue(requests.isEmpty())
    }

    @Test fun cancellingZipImportDuringCopyLeavesNoInstallationAndPreservesAnExistingOne() {
        payloads["encoder.bin"] = ByteArray(150_000) { (it % 251).toByte() }
        val archive = zip()
        val root = tmp.newFolder("cancelled-models")
        val store = TranslationModelStore(root, tmp.newFolder("cancelled-downloads"), spec())
        var checks = 0
        assertThrows(PackDownloader.Cancelled::class.java) {
            store.importZip(ByteArrayInputStream(archive)) { ++checks < 4 }
        }
        assertFalse(store.ready())
        assertTrue(root.listFiles().orEmpty().isEmpty())

        store.importZip(ByteArrayInputStream(archive))
        val active = store.modelDir
        checks = 0
        assertThrows(PackDownloader.Cancelled::class.java) {
            store.importZip(ByteArrayInputStream(archive)) { ++checks < 4 }
        }
        assertTrue(store.ready())
        assertEquals(active, store.modelDir)
        assertArrayEquals(payloads.getValue("encoder.bin"), File(active, "encoder.bin").readBytes())
        assertEquals(2, root.listFiles().orEmpty().size) // active directory + atomic pointer
        assertTrue(requests.isEmpty())
    }
}

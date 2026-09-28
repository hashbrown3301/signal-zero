package com.itantra.packs

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import kotlin.concurrent.thread

class PackDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val data = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Serves [data] at /pack.zip with Range support; can cut the first response short to simulate a dropped link. */
    @Volatile private var cutFirstResponseAt = -1
    private val requests: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val serverThread = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: Exception) { break }
            // One thread per connection: a cancelled client may leave a response half-sent.
            thread(isDaemon = true) { runCatching { socket.use { serve(it) } } }
        }
    }
    private val url = "http://127.0.0.1:${server.localPort}/pack.zip"

    /** Minimal HTTP/1.1: one request per connection, GET with an optional "Range: bytes=N-". */
    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        reader.readLine() ?: return  // request line
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
        }
        requests += range
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        val body = data.copyOfRange(from, data.size)
        val out = socket.getOutputStream()
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val crlf = "\r\n"
        val head = listOf("HTTP/1.1 $status", "Content-Type: application/zip", "Content-Length: ${body.size}", "Connection: close")
        out.write((head.joinToString(crlf) + crlf + crlf).toByteArray())
        val cut = cutFirstResponseAt
        if (cut > 0) {
            cutFirstResponseAt = -1
            out.write(body, 0, cut)  // then the connection closes: a dropped link
        } else {
            out.write(body)
        }
        out.flush()
    }

    @After
    fun stop() = server.close()

    private fun downloader() = PackDownloader(tmp.newFolder("dl"))

    @Test
    fun downloadsAndVerifies() {
        var lastProgress = 0L
        val file = downloader().download(url, "pack.zip", data.size.toLong(), sha) { done, total ->
            lastProgress = done
            assertEquals(data.size.toLong(), total)
            true
        }
        assertArrayEquals(data, file.readBytes())
        assertEquals(data.size.toLong(), lastProgress)
    }

    @Test
    fun resumesAfterADroppedConnection() {
        cutFirstResponseAt = 300_000
        val file = downloader().download(url, "pack.zip", data.size.toLong(), sha) { _, _ -> true }
        assertArrayEquals(data, file.readBytes())
        assertEquals(null, requests.first())                   // first request: whole file
        assertTrue(requests.last()!!.startsWith("bytes="))     // retry: only the rest
        assertTrue(requests.last()!!.removePrefix("bytes=").removeSuffix("-").toInt() > 0)
    }

    @Test
    fun cancelKeepsThePartialForLater() {
        val dir = tmp.newFolder("dl2")
        val d = PackDownloader(dir)
        assertThrows(PackDownloader.Cancelled::class.java) {
            d.download(url, "pack.zip", data.size.toLong(), sha) { done, _ -> done < 400_000 }
        }
        val part = File(dir, "pack.zip.part")
        assertTrue(part.exists() && part.length() in 400_000 until data.size)

        requests.clear()
        val file = d.download(url, "pack.zip", data.size.toLong(), sha) { _, _ -> true }
        assertArrayEquals(data, file.readBytes())
        assertTrue(requests.single()!!.startsWith("bytes="))   // resumed, not restarted
    }

    @Test
    fun checksumMismatchIsRejectedAndDiscarded() {
        val dir = tmp.newFolder("dl3")
        val e = assertThrows(PackException::class.java) {
            PackDownloader(dir).download(url, "pack.zip", data.size.toLong(), "00".repeat(32)) { _, _ -> true }
        }
        assertTrue(e.message!!.contains("SHA-256"))
        assertFalse(File(dir, "pack.zip.part").exists())
        assertFalse(File(dir, "pack.zip").exists())
    }

    @Test
    fun parsesTheBundledCatalogue() {
        val text = File("src/main/assets/catalog/index.json").readText()
        val catalog = PackCatalog.parse(text)
        assertEquals(20, catalog.packs.size)
        assertEquals(setOf("hi", "en", "mr", "gu", "bn", "ta", "te", "kn", "ml", "or"), catalog.packs.values.map { it.lang }.toSet())
        assertEquals("ta-listen.zip", catalog.packs.getValue("ta-listen").zip)
        assertEquals(PackCatalog.baseUrl + "ta-listen.zip", catalog.url("ta-listen"))
        assertEquals(1, catalog.sorted.first().second.packetCode)
    }
}

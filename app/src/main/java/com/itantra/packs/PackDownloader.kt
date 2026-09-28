package com.itantra.packs

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a pack zip with resume support, then checks its SHA-256 against the catalogue before anything
 * is installed. Plain JVM code (HttpURLConnection), unit-tested on the PC against a local HTTP server.
 *
 * A partial download is kept as `<name>.part` in [workDir]; the next attempt asks the server for the rest
 * (HTTP Range), so a dropped connection doesn't mean starting 100 MB over.
 */
class PackDownloader(private val workDir: File) {

    fun interface Progress {
        /** Called while downloading; return false to cancel. */
        fun update(downloaded: Long, total: Long): Boolean
    }

    class Cancelled : IOException("Download cancelled")

    init {
        workDir.mkdirs()
    }

    /** Downloads [url] and returns the verified file. Throws [PackException] on a checksum mismatch. */
    fun download(url: String, fileName: String, expectedSize: Long, expectedSha256: String, progress: Progress): File {
        val done = File(workDir, fileName)
        val part = File(workDir, "$fileName.part")
        if (done.exists()) done.delete()

        var attempt = 0
        while (true) {
            attempt++
            try {
                fetch(url, part, expectedSize, progress)
                break
            } catch (e: Cancelled) {
                throw e  // keep the .part file so a later try resumes
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }

        val sha = sha256(part)
        if (!sha.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw PackException("$fileName: download is damaged (SHA-256 mismatch), please try again")
        }
        if (!part.renameTo(done)) throw IOException("Couldn't finish $fileName")
        return done
    }

    fun discardPartial(fileName: String) {
        File(workDir, "$fileName.part").delete()
    }

    private fun fetch(url: String, part: File, expectedSize: Long, progress: Progress) {
        var have = if (part.exists()) part.length() else 0L
        if (have > expectedSize) {
            part.delete()
            have = 0
        }
        if (have == expectedSize && expectedSize > 0) return

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true  // GitHub release assets redirect to their storage host
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false  // server ignored Range: start over
                416 -> { part.delete(); throw IOException("Server rejected the resume range") }
                else -> throw IOException("HTTP $code for $url")
            }
            if (!append) have = 0
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var sinceReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        sinceReport += n
                        if (sinceReport >= REPORT_EVERY || have == expectedSize) {
                            sinceReport = 0
                            if (!progress.update(have, expectedSize)) throw Cancelled()
                        }
                    }
                }
            }
            if (!progress.update(have, expectedSize)) throw Cancelled()
            if (have != expectedSize) throw IOException("Connection ended early ($have of $expectedSize bytes)")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
        private const val REPORT_EVERY = 256 * 1024L

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

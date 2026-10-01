package com.itantra.translation

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class RuntimeModelPatchTest {
    @get:Rule val folder = TemporaryFolder()
    private val original = "0123456789".toByteArray()
    private val changed = "begin0123AB789end".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun spec() = ModelPatchSpec(original.size.toLong(), hash(original), changed.size.toLong(), hash(changed), listOf(
        ModelEdit(0, 0, "begin".toByteArray()), ModelEdit(4, 3, "AB".toByteArray()), ModelEdit(10, 0, "end".toByteArray()),
    ))

    @Test fun appliesMultipleEditsAndReusesOnlyVerifiedDerivedFile() {
        val source = folder.newFile("original").apply { writeBytes(original) }
        val target = File(folder.root, "derived")
        assertEquals(target, RuntimeModelPatch.prepare(source, target, spec()))
        assertArrayEquals(changed, target.readBytes())
        assertArrayEquals(original, source.readBytes())
        // A valid cached derived model needs no input rewrite or extra allocation.
        source.delete()
        assertEquals(target, RuntimeModelPatch.prepare(source, target, spec()))
    }

    @Test fun corruptBytesInsideDeletedRangeStillRejectSourceAndPreserveOldTarget() {
        val source = folder.newFile("original").apply { writeBytes(original.clone().apply { this[5] = 88 }) }
        val target = folder.newFile("derived").apply { writeText("previous model") }
        assertThrows(IllegalStateException::class.java) { RuntimeModelPatch.prepare(source, target, spec()) }
        assertEquals("previous model", target.readText())
        assertFalse(folder.root.listFiles()!!.any { it.name.endsWith(".partial") })
    }

    @Test fun incorrectDerivedHashNeverActivatesOutput() {
        val source = folder.newFile("original").apply { writeBytes(original) }
        val target = File(folder.root, "derived")
        assertThrows(IllegalStateException::class.java) {
            RuntimeModelPatch.prepare(source, target, spec().copy(targetSha256 = "0".repeat(64)))
        }
        assertFalse(target.exists())
        assertFalse(folder.root.listFiles()!!.any { it.name.endsWith(".partial") })
    }

    @Test fun damagedCacheIsRepairedFromOriginal() {
        val source = folder.newFile("original").apply { writeBytes(original) }
        val target = folder.newFile("derived").apply { writeBytes(ByteArray(changed.size)) }
        RuntimeModelPatch.prepare(source, target, spec())
        assertArrayEquals(changed, target.readBytes())
    }

    @Test fun clearsAbandonedTemporaryDecoderBeforePreparation() {
        val source = folder.newFile("original").apply { writeBytes(original) }
        val partial = folder.newFile(".translation-abandoned.partial").apply { writeText("interrupted copy") }
        val target = File(folder.root, "derived")
        RuntimeModelPatch.prepare(source, target, spec())
        assertFalse(partial.exists())
        assertArrayEquals(changed, target.readBytes())
    }

    @Test fun rejectsOverlappingOrWrongSizeEditsAndInPlaceReplacement() {
        val source = folder.newFile("original").apply { writeBytes(original) }
        val target = File(folder.root, "derived")
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeModelPatch.prepare(source, target, spec().copy(edits = listOf(ModelEdit(5, 4, byteArrayOf()), ModelEdit(6, 0, byteArrayOf()))))
        }
        assertThrows(IllegalArgumentException::class.java) { RuntimeModelPatch.prepare(source, target, spec().copy(targetSize = 100)) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeModelPatch.prepare(source, source, spec()) }
        assertArrayEquals(original, source.readBytes())
    }
}

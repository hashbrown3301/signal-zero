package com.itantra.translation

import com.itantra.comm.Language
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhrasebookStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String = "reviewed.json") = File(tmp.root, name)
    private fun entry(id: String = "phrase-1", source: String = "en", target: String = "hi", text: String = "Bring water") =
        ReviewedPhrase(id, source, target, text, "पानी लाओ", 100)
    private fun document(entries: List<ReviewedPhrase>, revision: Long = 1, format: Int = 1): ByteArray =
        "{\"format\":$format,\"revision\":$revision,\"entries\":[${entries.joinToString(",") { Json.encodeToString(ReviewedPhrase.serializer(), it) }}]}".toByteArray()

    @Test fun constructionAndMissingLookupNeedNoFilesOrNativeModels() {
        val file = file()
        val store = PhrasebookStore(file)
        assertFalse(file.exists())
        assertTrue(store.list().isEmpty())
        assertEquals(0L, store.revision)
        assertNull(store.find("Bring water", Language.ENGLISH, Language.HINDI))
        assertFalse(file.exists())
    }

    @Test fun reviewedUnicodePairsSurviveReloadForEveryLanguage() {
        val file = file()
        val store = PhrasebookStore(file) { 100 }
        val examples = listOf("नमस्ते −१२", "Hello −12", "नमस्कार −१२", "નમસ્તે −૧૨", "নমস্কার −১২",
            "வணக்கம் −௧௨", "నమస్కారం −౧౨", "ನಮಸ್ಕಾರ −೧೨", "നമസ്കാരം −൧൨", "ନମସ୍କାର −୧୨")
        val entries = Language.entries.zip(examples).map { (source, text) ->
            val target = if (source == Language.ENGLISH) Language.HINDI else Language.ENGLISH
            store.upsert(source, target, text, "Reviewed: $text 👋")
        }
        val reloaded = PhrasebookStore(file)
        assertEquals(10L, reloaded.revision)
        assertEquals(entries.toSet(), reloaded.list().toSet())
        entries.forEach { phrase ->
            assertEquals(phrase, reloaded.find(phrase.sourceText, Language.fromIso(phrase.sourceIso)!!, Language.fromIso(phrase.targetIso)!!))
        }
    }

    @Test fun matchingChangesWhitespaceOnlyAndPreservesMeaningBearingCharacters() {
        val store = PhrasebookStore(file())
        val phrase = store.upsert(Language.ENGLISH, Language.HINDI, "Hello −12.5% at 10:30!", "समीक्षित अनुवाद")
        assertEquals(phrase, store.find(" \tHello\u00a0−12.5%\n at 10:30!  ", Language.ENGLISH, Language.HINDI))
        listOf("hello −12.5% at 10:30!", "Hello 12.5% at 10:30!", "Hello -12.5% at 10:30!",
            "Hello −12.5% at 10:30", "Hello −१२.५% at 10:30!", "Hello −12.5% at 11:30!").forEach {
            assertNull(store.find(it, Language.ENGLISH, Language.HINDI))
        }
        store.upsert(Language.ENGLISH, Language.HINDI, "Cafe\u0301", "समीक्षित")
        assertNull(store.find("Café", Language.ENGLISH, Language.HINDI))
        store.upsert(Language.HINDI, Language.ENGLISH, "क्\u200dष", "Reviewed")
        assertNull(store.find("क्ष", Language.HINDI, Language.ENGLISH))
    }

    @Test fun directionsAndLanguagePairsAreIsolatedWithoutAutomaticReverseEntries() {
        val store = PhrasebookStore(file())
        val first = store.upsert(Language.ENGLISH, Language.HINDI, "Hello", "नमस्ते")
        val second = store.upsert(Language.ENGLISH, Language.TAMIL, "Hello", "வணக்கம்")
        assertEquals(first, store.find("Hello", Language.ENGLISH, Language.HINDI))
        assertEquals(second, store.find("Hello", Language.ENGLISH, Language.TAMIL))
        assertNull(store.find("नमस्ते", Language.HINDI, Language.ENGLISH))
        assertNull(store.find("Hello", Language.HINDI, Language.TAMIL))
        assertThrows(PhrasebookException::class.java) { store.upsert(Language.HINDI, Language.HINDI, "नमस्ते", "नमस्ते") }
        assertEquals(2, store.list().size)
    }

    @Test fun updatingAnExactPairPreservesIdAndPublishesTheNewRevisionAndText() {
        var clock = 100L
        val store = PhrasebookStore(file()) { clock }
        val first = store.upsert(Language.ENGLISH, Language.HINDI, "Bring water", "पानी")
        clock = 90
        val updated = store.upsert(Language.ENGLISH, Language.HINDI, " Bring  water ", "पानी लाओ")
        assertEquals(first.id, updated.id)
        assertEquals(100L, updated.updatedAt)
        assertEquals(2L, store.revision)
        assertEquals(1, store.list().size)
        assertEquals("पानी लाओ", store.find("Bring\twater", Language.ENGLISH, Language.HINDI)!!.targetText)
    }

    @Test fun removingAReviewIsImmediateDurableAndDoesNotResurrectDeletedText() {
        val file = file()
        val store = PhrasebookStore(file)
        val first = store.upsert(Language.ENGLISH, Language.HINDI, "Secret phrase", "निजी समीक्षा")
        assertTrue(store.remove(first.id))
        assertEquals(2L, store.revision)
        assertNull(store.find("Secret phrase", Language.ENGLISH, Language.HINDI))
        assertFalse(store.remove(first.id))
        assertEquals(2L, store.revision)
        assertTrue(PhrasebookStore(file).list().isEmpty())
        assertFalse(file.readText().contains("Secret phrase"))
        assertFalse(file.readText().contains("निजी समीक्षा"))
    }

    @Test fun entryLimitAllowsUpdatesButRejectsANewPairWithoutChangingData() {
        val file = file()
        val store = PhrasebookStore(file)
        repeat(PhrasebookStore.MAX_ENTRIES) { store.upsert(Language.ENGLISH, Language.HINDI, "Phrase $it", "समीक्षित $it") }
        val previousBytes = file.readBytes()
        assertThrows(PhrasebookException::class.java) { store.upsert(Language.ENGLISH, Language.HINDI, "One more", "समीक्षित") }
        assertEquals(200L, store.revision)
        assertArrayEquals(previousBytes, file.readBytes())
        store.upsert(Language.ENGLISH, Language.HINDI, "Phrase 0", "Updated")
        assertEquals(200, store.list().size)
        assertEquals(201L, store.revision)
    }

    @Test fun textBoundsBlankControlAndInvalidUnicodeAreRejectedBeforeMutation() {
        val store = PhrasebookStore(file())
        store.upsert(Language.ENGLISH, Language.HINDI, "A".repeat(1000), "👋".repeat(500))
        listOf("", " \t\n", "A".repeat(1001), "bad\u0000text", "bad\u0001text", "bad\ud800", "bad\udc00").forEach { invalid ->
            assertThrows(PhrasebookException::class.java) { store.upsert(Language.ENGLISH, Language.HINDI, invalid, "Reviewed") }
            assertThrows(PhrasebookException::class.java) { store.upsert(Language.ENGLISH, Language.HINDI, "Source", invalid) }
        }
        assertEquals(1L, store.revision)
        assertEquals(1, store.list().size)
        assertNull(store.find("A".repeat(1001), Language.ENGLISH, Language.HINDI))
    }

    @Test fun corruptTruncatedUnsupportedAndInvalidUtf8FilesAreNeverSilentlyOverwritten() {
        val cases = listOf("{".toByteArray(), "".toByteArray(), byteArrayOf(0xff.toByte()), document(listOf(entry()), format = 2))
        cases.forEachIndexed { index, bytes ->
            val file = file("corrupt-$index.json").apply { writeBytes(bytes) }
            val store = PhrasebookStore(file)
            assertThrows(PhrasebookException::class.java) { store.list() }
            assertThrows(PhrasebookException::class.java) { store.upsert(Language.ENGLISH, Language.HINDI, "Source", "Reviewed") }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun invalidSavedRecordsAreRejectedWithoutPublishingAPartialSnapshot() {
        val cases = listOf(
            document(listOf(entry(), entry(text = "Different"))),
            document(listOf(entry(), entry(id = "phrase-2", text = "Bring  water"))),
            document(listOf(entry(source = "unknown"))),
            document(listOf(entry(target = "en"))),
            document(listOf(entry().copy(updatedAt = -1))),
            document(listOf(entry().copy(sourceText = "x".repeat(1001)))),
            document((0..200).map { entry(id = "p-$it", text = "Phrase $it") }),
            document(listOf(entry()), revision = -1),
        )
        cases.forEachIndexed { index, bytes ->
            val file = file("invalid-$index.json").apply { writeBytes(bytes) }
            assertThrows(PhrasebookException::class.java) { PhrasebookStore(file).list() }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun oversizedStorageIsRejectedBeforeParsing() {
        val file = file().apply { writeBytes(ByteArray(PhrasebookStore.MAX_FILE_BYTES + 1)) }
        val error = assertThrows(PhrasebookException::class.java) { PhrasebookStore(file).list() }
        assertTrue(error.message!!.contains("large"))
        assertEquals((PhrasebookStore.MAX_FILE_BYTES + 1).toLong(), file.length())
    }

    @Test fun failedAtomicCommitKeepsTheLastSnapshotAndCleansItsPendingFile() {
        val file = file()
        val store = PhrasebookStore(file)
        val first = store.upsert(Language.ENGLISH, Language.HINDI, "Source", "Reviewed")
        val previousBytes = file.readBytes()
        val backup = file("preserved.json")
        assertTrue(file.renameTo(backup))
        assertTrue(file.mkdir())
        File(file, "blocker").writeText("prevent replacing a nonempty directory")
        assertThrows(PhrasebookException::class.java) { store.upsert(Language.ENGLISH, Language.HINDI, "Source", "Changed") }
        assertEquals(1L, store.revision)
        assertEquals(first, store.find("Source", Language.ENGLISH, Language.HINDI))
        assertArrayEquals(previousBytes, backup.readBytes())
        assertFalse(tmp.root.listFiles().orEmpty().any { it.name.startsWith(".phrases-") })
        file.deleteRecursively()
        assertTrue(backup.renameTo(file))
        store.upsert(Language.ENGLISH, Language.HINDI, "Source", "Changed")
        assertEquals(2L, store.revision)
    }

    @Test fun concurrentMutationsAreSerializedAndDurableWithoutLostEntries() {
        val file = file()
        val store = PhrasebookStore(file)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 32).map { index -> pool.submit<ReviewedPhrase> {
                store.upsert(Language.ENGLISH, Language.HINDI, "Phrase $index", "Reviewed $index")
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(32L, store.revision)
            assertEquals(32, store.list().size)
            assertEquals(store.list().toSet(), PhrasebookStore(file).list().toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun callerCannotMutateTheCommittedSnapshotThroughAList() {
        val store = PhrasebookStore(file())
        val phrase = store.upsert(Language.ENGLISH, Language.HINDI, "Source", "Reviewed")
        // The returned collection may be immutable; either way it must not mutate the store.
        runCatching { (store.list() as MutableList<ReviewedPhrase>).clear() }
        assertEquals(listOf(phrase), store.list())
    }

    @Test fun exhaustedRevisionAndInvalidClockCannotOverwriteExistingData() {
        val file = file().apply { writeBytes(document(listOf(entry()), revision = Long.MAX_VALUE)) }
        val bytes = file.readBytes()
        assertThrows(PhrasebookException::class.java) { PhrasebookStore(file).upsert(Language.ENGLISH, Language.HINDI, "Source", "Reviewed") }
        assertArrayEquals(bytes, file.readBytes())
        val badClock = PhrasebookStore(file("bad-clock.json")) { -1 }
        assertThrows(PhrasebookException::class.java) { badClock.upsert(Language.ENGLISH, Language.HINDI, "Source", "Reviewed") }
        assertEquals(0L, badClock.revision)
    }
}

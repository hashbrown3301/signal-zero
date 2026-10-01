package com.itantra.translation

import com.itantra.comm.Language
import com.itantra.session.Translated
import com.itantra.session.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lazily loaded runtime, serialized native calls, and a small session-independent text cache. */
class OfflineTranslator(
    private val store: TranslationModelStore,
    private val customOpsLibrary: () -> String,
    private val canLoad: () -> Boolean = { true },
) : Translator {
    private val lock = Mutex()
    private var runtime: NllbRuntime? = null
    private data class Key(val text: String, val source: Language, val target: Language)
    private val cache = object : LinkedHashMap<Key, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean = size > 128
    }

    override suspend fun translate(text: String, source: Language, target: Language): Translated =
        withContext(Dispatchers.Default) {
            require(text.isNotBlank()) { "No text to translate" }
            if (source == target) return@withContext Translated(text, 0)
            lock.withLock {
                val key = Key(text, source, target)
                cache[key]?.let { return@withLock Translated(it, 0, cached = true) }
                val start = System.nanoTime()
                if (runtime == null) {
                    check(canLoad()) { "Offline translation needs a 64-bit phone with 4 GB RAM and 2.5 GB free memory. Close other apps and retry." }
                    val directory = store.modelDir ?: error("Install the offline translation pack in Languages first")
                    try {
                        runtime = NllbRuntime(directory, customOpsLibrary())
                    } catch (e: LinkageError) {
                        throw IllegalStateException("The offline runtime could not load on this phone. Check Android/device compatibility.", e)
                    }
                }
                val result = checkNotNull(runtime).translate(text, source, target)
                check(result.isNotBlank()) { "The offline model returned no translation" }
                cache[key] = result
                Translated(result, (System.nanoTime() - start) / 1_000_000)
            }
        }

    /** Native execution cannot be interrupted mid-call; wait for it before releasing sessions. */
    suspend fun release() = withContext(Dispatchers.Default) {
        lock.withLock {
            runtime?.close()
            runtime = null
            cache.clear()
        }
    }
}

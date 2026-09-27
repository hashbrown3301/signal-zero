package com.itantra.speech

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * Offline speech-to-text with a NeMo CTC model (AI4Bharat IndicConformer, or NeMo English), int8.
 * Built by [EngineFactory] from a speak pack: [assets] non-null means [model]/[tokens] are asset paths (built-in
 * pack), null means absolute file paths (installed pack). Metadata comes from scripts/fetch_models.py.
 */
class SttEngine(
    assets: AssetManager?,
    model: String,
    tokens: String,
    featureDim: Int = 80,
    numThreads: Int = 4,
) {

    data class Result(val text: String, val millis: Long)

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = featureDim),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = model),
                tokens = tokens,
                numThreads = numThreads,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    /** [samples] must be 16 kHz mono floats in [-1, 1]. */
    fun transcribe(samples: FloatArray): Result {
        val start = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            return Result(text, SystemClock.elapsedRealtime() - start)
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

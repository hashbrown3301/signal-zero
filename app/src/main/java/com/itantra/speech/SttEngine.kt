package com.itantra.speech

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * Offline Hindi speech-to-text with AI4Bharat IndicConformer (NeMo CTC, int8).
 * The model's sherpa-onnx metadata is added by scripts/fetch_models.py.
 */
class SttEngine(assets: AssetManager, numThreads: Int = 4) {

    data class Result(val text: String, val millis: Long)

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "stt/model.int8.onnx"),
                tokens = "stt/tokens.txt",
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

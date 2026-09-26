"""Desktop sanity check for the prepared models (run after fetch_models.py).

    python scripts/test_models_pc.py              # TTS a Hindi sentence, then STT it back
    python scripts/test_models_pc.py my_clip.wav  # STT a 16-bit mono WAV of your own

Catches a broken model patch in seconds instead of after an Android build.
"""

import sys
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

ASSETS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"
SENTENCE = "नमस्ते, आज मौसम बहुत अच्छा है।"


def make_tts() -> sherpa_onnx.OfflineTts:
    tts_dir = ASSETS / "tts"
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(next(tts_dir.glob("*.onnx"))),
                tokens=str(tts_dir / "tokens.txt"),
                data_dir=str(tts_dir / "espeak-ng-data"),
            ),
            num_threads=2,
        ),
    )
    return sherpa_onnx.OfflineTts(config)


def make_stt() -> sherpa_onnx.OfflineRecognizer:
    return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(ASSETS / "stt" / "model.int8.onnx"),
        tokens=str(ASSETS / "stt" / "tokens.txt"),
        num_threads=2,
        sample_rate=16000,
        feature_dim=80,
        decoding_method="greedy_search",
    )


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path)) as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            sys.exit("Expected a 16-bit mono WAV")
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
        return pcm.astype(np.float32) / 32768, w.getframerate()


def main() -> None:
    if len(sys.argv) > 1:
        samples, sample_rate = read_wav(Path(sys.argv[1]))
        print(f"Input WAV: {len(samples) / sample_rate:.2f} s @ {sample_rate} Hz")
    else:
        t = time.perf_counter()
        tts = make_tts()
        print(f"TTS load: {(time.perf_counter() - t) * 1000:.0f} ms")
        t = time.perf_counter()
        audio = tts.generate(SENTENCE, sid=0, speed=1.0)
        samples, sample_rate = np.array(audio.samples, dtype=np.float32), audio.sample_rate
        print(f"TTS: {(time.perf_counter() - t) * 1000:.0f} ms for {len(samples) / sample_rate:.2f} s of audio")
        print(f"Spoken:     {SENTENCE}")

    t = time.perf_counter()
    stt = make_stt()
    print(f"STT load: {(time.perf_counter() - t) * 1000:.0f} ms")
    t = time.perf_counter()
    stream = stt.create_stream()
    stream.accept_waveform(sample_rate, samples)
    stt.decode_stream(stream)
    print(f"STT: {(time.perf_counter() - t) * 1000:.0f} ms")
    print(f"Transcript: {stream.result.text}")


if __name__ == "__main__":
    main()

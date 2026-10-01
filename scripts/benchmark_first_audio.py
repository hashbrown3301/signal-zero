"""Warm Hindi voice benchmark for the chunked playback path (desktop, not phone timing).

    .venv/bin/python scripts/benchmark_first_audio.py --out dist/latency/first-audio.json

Uses the same first-sentence boundary as ChunkedSpeaker for this fixed passage.
Reports first-audio synthesis separately from all synthesis; optionally decodes
both outputs as an intelligibility smoke check, not a real-speech accuracy score.
"""

import argparse
import json
import platform
import statistics
import time
from pathlib import Path

import numpy as np

from test_models_pc import make_stt, make_tts

CHUNKS = [
    "नमस्ते, कृपया मुझे बताइए कि रेलवे स्टेशन जाने का रास्ता कौन सा है। ",
    "मुझे आज शाम की ट्रेन पकड़नी है और स्टेशन तक जल्दी पहुँचना है। ",
    "अगर रास्ते में कोई परेशानी हो तो कृपया मुझे पहले से बता दीजिए।",
]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--check-recognition", action="store_true")
    args = ap.parse_args()
    if args.runs < 1:
        ap.error("--runs must be positive")
    text = "".join(CHUNKS)
    tts = make_tts()
    tts.generate("नमस्ते", sid=0, speed=1.0)
    full_ms, first_ms, total_ms = [], [], []
    full_audio, chunk_audio = None, []
    for _ in range(args.runs):
        started = time.perf_counter()
        full_audio = tts.generate(text, sid=0, speed=1.0)
        full_ms.append((time.perf_counter() - started) * 1000)
        times, chunk_audio = [], []
        for chunk in CHUNKS:
            started = time.perf_counter()
            chunk_audio.append(tts.generate(chunk, sid=0, speed=1.0))
            times.append((time.perf_counter() - started) * 1000)
        first_ms.append(times[0])
        total_ms.append(sum(times))
    report = {
        "environment": platform.platform(), "runtime": "sherpa-onnx 1.13.7, 2 threads",
        "scope": "Warm desktop synthesis only; excludes translation, network, AudioTrack and model loading.",
        "runs": args.runs, "text": text, "chunks": CHUNKS,
        "full_first_audio_median_ms": round(statistics.median(full_ms), 2),
        "chunked_first_audio_median_ms": round(statistics.median(first_ms), 2),
        "chunked_total_synthesis_median_ms": round(statistics.median(total_ms), 2),
        "first_audio_reduction_percent": round(100 * (1 - statistics.median(first_ms) / statistics.median(full_ms)), 1),
    }
    if args.check_recognition:
        stt = make_stt()
        for key, samples in [
            ("full_audio_transcript", full_audio.samples),
            ("chunked_audio_transcript", np.concatenate([np.asarray(a.samples) for a in chunk_audio])),
        ]:
            stream = stt.create_stream()
            stream.accept_waveform(full_audio.sample_rate, np.asarray(samples, dtype=np.float32))
            stt.decode_stream(stream)
            report[key] = stream.result.text
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

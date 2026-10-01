"""Benchmark current Hindi speech stages on the cloud CPU, not on Android phones.

Inputs are synthetic Piper Hindi clips, so transcripts/CER/WER measure a synthetic
roundtrip intelligibility smoke check rather than real-human recognition accuracy.
The measured pipeline is VAD -> STT -> chunked Hindi TTS (same-language Solo).
Translation, recording/button handling, network, playback, Android dispatch, and
accuracy scoring are excluded. TTS readiness means synthesized samples exist,
not audible first speech.

    .venv/bin/python scripts/benchmark_speech_stages.py --runs 5 --hash-models

Native models load only from main(), so helpers/tests need no model initialization.
"""

import argparse
import hashlib
import json
import math
import os
import platform
import re
import resource
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    from .evaluate_accuracy import recognition_scores
except ImportError:  # Executed directly from scripts/.
    from evaluate_accuracy import recognition_scores

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
SAMPLE_RATE = 16_000
VAD_WINDOW = 512
VAD_PAD = SAMPLE_RATE // 10
PHRASES = {
    "short": "नमस्ते मुझे पानी देना कृपया।",
    "medium": "कृपया मुझे बताइए कि रेलवे स्टेशन जाने का सबसे आसान रास्ता कौन सा है और वहाँ पहुँचने में कितना समय लगेगा।",
    "long": "आज सुबह मुझे अपने परिवार के साथ अस्पताल जाना है, इसलिए कृपया एक गाड़ी बुला दीजिए। रास्ते में दवा की दुकान पर रुकना है और डॉक्टर को पिछली रिपोर्ट भी दिखानी है। यदि गाड़ी देर से आए तो मुझे तुरंत बताइए, ताकि मैं समय पर दूसरा रास्ता चुन सकूँ।",
}


def nearest_rank(values: list[float], quantile: float) -> float:
    """Nearest-rank quantile: small samples deliberately expose the observed tail."""
    if not values or not 0 <= quantile <= 1:
        raise ValueError("A nonempty sample and quantile in [0, 1] are required")
    if any(not math.isfinite(value) for value in values):
        raise ValueError("Samples must be finite")
    ordered = sorted(values)
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)]


def summary(values: list[float]) -> dict:
    if not values:
        return {"count": 0, "p50": None, "p90": None, "p95": None, "max": None}
    return {
        "count": len(values),
        "p50": nearest_rank(values, 0.50),
        "p90": nearest_rank(values, 0.90),
        "p95": nearest_rank(values, 0.95),
        "max": max(values),
    }


def _utf16_units(text: str) -> str:
    encoded = text.encode("utf-16-le", errors="surrogatepass")
    return "".join(chr(encoded[index] | encoded[index + 1] << 8) for index in range(0, len(encoded), 2))


def _from_utf16_units(units: str) -> str:
    return units.encode("utf-16-le", errors="surrogatepass").decode("utf-16-le", errors="surrogatepass")


def speech_chunks(text: str, max_chars: int = 140, min_sentence_chars: int = 40) -> list[str]:
    """Port of session/ChunkedSpeaker.kt, including Kotlin UTF-16 string offsets.

    Java Regex's default \\s lookahead is ASCII whitespace. Character whitespace
    used for word fallback includes Unicode spaces, as Kotlin Char.isWhitespace.
    Separators stay attached to the preceding chunk and words are never divided.
    """
    if max_chars <= 0 or not 1 <= min_sentence_chars <= max_chars:
        raise ValueError("Invalid chunk limits")
    units = _utf16_units(text)
    if len(units) <= max_chars or not units.strip():
        return [text]
    endings = [match.end() for match in re.finditer(r"[.!?।॥。！？]+(?=[ \t\n\x0b\f\r]|$)", units)]
    chunks, start = [], 0
    while start < len(units):
        limit = min(start + max_chars, len(units))
        if limit == len(units):
            chunks.append(_from_utf16_units(units[start:]))
            break
        end = next((end for end in endings if start + min_sentence_chars <= end <= limit), None)
        if end is None:
            end = next((index for index in range(limit, start, -1) if units[index].isspace()), None)
        if end is None:
            end = next((index for index in range(limit, len(units)) if units[index].isspace()), len(units))
        while end < len(units) and units[end].isspace():
            end += 1
        chunks.append(_from_utf16_units(units[start:end]))
        start = end
    return chunks


def merge_ranges(ranges: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Merge sorted/unsorted half-open ranges; adjacency is merged like Kotlin."""
    merged = []
    for start, end in sorted(ranges):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def vad_frames(samples, window: int = VAD_WINDOW):
    if window <= 0:
        raise ValueError("VAD window must be positive")
    for index in range(0, len(samples), window):
        frame = list(samples[index:index + window])
        frame.extend([0.0] * (window - len(frame)))
        yield frame


def trim_with_vad(samples, vad, pad_samples: int = VAD_PAD) -> tuple[list[float], list[tuple[int, int]]]:
    """Current app's reset/feed/drain/zero-pad-tail/flush/pad/clamp/merge sequence."""
    ranges = []

    def drain():
        while not vad.empty():
            segment = vad.front
            ranges.append((max(0, segment.start - pad_samples),
                           min(len(samples), segment.start + len(segment.samples) + pad_samples)))
            vad.pop()

    vad.reset()
    for frame in vad_frames(samples):
        vad.accept_waveform(frame)
        drain()
    vad.flush()
    drain()
    merged = merge_ranges(ranges)
    speech = [sample for start, end in merged for sample in samples[start:end]]
    return speech, merged


def rss_snapshot() -> dict:
    current = None
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                current = int(line.split()[1]) * 1024
                break
    except OSError:
        pass
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return {"rss_bytes": current, "process_peak_rss_bytes": int(peak if sys.platform == "darwin" else peak * 1024)}


def file_info(path: Path, include_hash: bool = False) -> dict:
    info = {"path": str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
            "bytes": path.stat().st_size}
    if include_hash:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            while block := handle.read(1024 * 1024):
                digest.update(block)
        info["sha256"] = digest.hexdigest()
    return info


def read_if_present(path: str):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None


def environment() -> dict:
    cpu_info = read_if_present("/proc/cpuinfo") or ""
    cpu_model = next((line.split(":", 1)[1].strip() for line in cpu_info.splitlines()
                      if line.startswith("model name")), None)
    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        commit = None
    return {
        "platform": platform.platform(), "machine": platform.machine(), "python": platform.python_version(),
        "cpu_model": cpu_model, "logical_cpus": os.cpu_count(),
        "affinity_cpus": len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "cgroup_cpu_max": read_if_present("/sys/fs/cgroup/cpu.max"),
        "cgroup_memory_max": read_if_present("/sys/fs/cgroup/memory.max"),
        "app_commit": commit,
    }


def make_engines(assets: Path):
    import sherpa_onnx

    voices = sorted((assets / "tts").glob("*.onnx"))
    if len(voices) != 1:
        raise ValueError(f"Expected one prepared Hindi TTS ONNX model, found {len(voices)}")
    paths = {"tts_model": voices[0], "tts_tokens": assets / "tts" / "tokens.txt",
             "stt_model": assets / "stt" / "model.int8.onnx", "stt_tokens": assets / "stt" / "tokens.txt",
             "vad_model": assets / "vad" / "silero_vad.onnx"}
    for path in paths.values():
        if not path.is_file():
            raise ValueError(f"Missing prepared speech asset: {path}")
    loads, memory = {}, {"before_model_load": rss_snapshot()}
    started = time.perf_counter()
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(paths["tts_model"]), tokens=str(paths["tts_tokens"]),
                data_dir=str(assets / "tts" / "espeak-ng-data")), num_threads=2)))
    loads["tts_ms"] = (time.perf_counter() - started) * 1000
    memory["after_tts_load"] = rss_snapshot()
    started = time.perf_counter()
    stt = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(paths["stt_model"]), tokens=str(paths["stt_tokens"]), num_threads=2,
        sample_rate=SAMPLE_RATE, feature_dim=80, decoding_method="greedy_search")
    loads["stt_ms"] = (time.perf_counter() - started) * 1000
    memory["after_stt_load"] = rss_snapshot()
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad = sherpa_onnx.SileroVadModelConfig(
        model=str(paths["vad_model"]), threshold=0.5, min_silence_duration=0.25,
        min_speech_duration=0.25, max_speech_duration=30, window_size=VAD_WINDOW)
    config.sample_rate = SAMPLE_RATE
    config.num_threads = 1
    started = time.perf_counter()
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=60)
    loads["vad_ms"] = (time.perf_counter() - started) * 1000
    memory["after_vad_load"] = rss_snapshot()
    return tts, stt, vad, paths, loads, memory


def synthetic_clip(tts, text: str) -> tuple[object, dict]:
    import numpy as np

    started = time.perf_counter()
    audio = tts.generate(text, sid=0, speed=1.0)
    generation_ms = (time.perf_counter() - started) * 1000
    original = np.asarray(audio.samples, dtype=np.float32)
    if audio.sample_rate == SAMPLE_RATE:
        samples = original
    else:
        positions = np.arange(round(len(original) * SAMPLE_RATE / audio.sample_rate)) * audio.sample_rate / SAMPLE_RATE
        samples = np.interp(positions, np.arange(len(original)), original).astype(np.float32)
    return samples, {
        "kind": "synthetic Piper Hindi, not human microphone speech",
        "source_sample_rate": audio.sample_rate, "benchmark_sample_rate": SAMPLE_RATE,
        "resampling": "none" if audio.sample_rate == SAMPLE_RATE else "NumPy linear interpolation; synthetic smoke input only",
        "source_generation_ms_excluded_from_pipeline": generation_ms,
        "input_samples": len(samples), "input_audio_sec": len(samples) / SAMPLE_RATE,
    }


def measure_pipeline(samples, reference: str, stt, tts, vad) -> dict:
    import numpy as np

    pipeline_started = time.perf_counter()
    started = time.perf_counter()
    speech, ranges = trim_with_vad(samples, vad)
    vad_ms = (time.perf_counter() - started) * 1000
    row = {"vad_ms": vad_ms, "vad_segments": len(ranges), "vad_ranges_samples": ranges,
           "speech_audio_sec": len(speech) / SAMPLE_RATE, "stt_ms": None, "stt_rtf": None,
           "transcript": "", "chunk_count": 0, "chunks": [], "tts_chunk_ms": [],
           "tts_first_chunk_ms": None, "tts_total_synthesis_ms": None,
           "output_audio_sec": None, "first_chunk_audio_sec": None,
           "backend_first_audio_ready_ms": None}
    if speech:
        started = time.perf_counter()
        stream = stt.create_stream()
        stream.accept_waveform(SAMPLE_RATE, np.asarray(speech, dtype=np.float32))
        stt.decode_stream(stream)
        row["transcript"] = stream.result.text.strip()
        row["stt_ms"] = (time.perf_counter() - started) * 1000
        row["stt_rtf"] = row["stt_ms"] / 1000 / row["speech_audio_sec"]
        del stream
    if not row["transcript"]:
        row["result"] = "no_speech" if not speech else "empty_transcript"
        row["synthetic_roundtrip_strict_cer_wer"] = recognition_scores(
            [{"reference": reference, "hypothesis": ""}], ignore_punctuation=False)
        row["memory_after_pipeline"] = rss_snapshot()
        return row
    row["chunks"] = speech_chunks(row["transcript"])
    row["chunk_count"] = len(row["chunks"])
    duration = 0.0
    for index, chunk in enumerate(row["chunks"]):
        started = time.perf_counter()
        audio = tts.generate(chunk, sid=0, speed=1.0)
        row["tts_chunk_ms"].append((time.perf_counter() - started) * 1000)
        seconds = len(audio.samples) / audio.sample_rate
        duration += seconds
        if index == 0:
            row["tts_first_chunk_ms"] = row["tts_chunk_ms"][0]
            row["first_chunk_audio_sec"] = seconds
            row["backend_first_audio_ready_ms"] = (time.perf_counter() - pipeline_started) * 1000
        del audio
    row["tts_total_synthesis_ms"] = sum(row["tts_chunk_ms"])
    row["output_audio_sec"] = duration
    row["result"] = "synthesized_samples_ready"
    # Reference scoring belongs to the benchmark, never to the measured app path.
    row["synthetic_roundtrip_strict_cer_wer"] = recognition_scores(
        [{"reference": reference, "hypothesis": row["transcript"]}], ignore_punctuation=False)
    row["memory_after_pipeline"] = rss_snapshot()
    return row


def summarize_runs(rows: list[dict]) -> dict:
    stages = ("vad_ms", "stt_ms", "stt_rtf", "tts_first_chunk_ms", "tts_total_synthesis_ms",
              "backend_first_audio_ready_ms", "speech_audio_sec", "output_audio_sec")
    return {"runs": len(rows),
            "failed_runs": sum(row["result"] != "synthesized_samples_ready" for row in rows),
            "metrics": {stage: summary([row[stage] for row in rows if row.get(stage) is not None]) for stage in stages}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--assets", type=Path, default=ASSETS)
    parser.add_argument("--out", type=Path, default=ROOT / "dist" / "benchmarks" / "current-speech.json")
    parser.add_argument("--hash-models", action="store_true", help="Include SHA-256 of prepared model/token files")
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    import sherpa_onnx

    print("Loading Hindi speech engines on the CPU...", flush=True)
    tts, stt, vad, paths, loads, memory = make_engines(args.assets.resolve())
    report = {
        "schema_version": 1, "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "environment": environment(), "sherpa_onnx_version": sherpa_onnx.__version__,
        "scope": "Cloud Python CPU backend using prepared app Hindi models; not Android engine execution or phone performance.",
        "accuracy_scope": "Synthetic Piper -> VAD -> STT roundtrip smoke check only, not real-human recognition accuracy or translation accuracy.",
        "excludes": ["translation", "microphone capture and stop", "button handling", "network", "Android dispatch", "AudioTrack setup", "audible playback", "accuracy scoring"],
        "measurement": {"clock": "time.perf_counter", "percentiles": "nearest rank",
                        "warmup_pipeline_runs_per_phrase_excluded_from_summary": 1,
                        "model_load_observations": 1, "model_file_cache_state": "unknown",
                        "first_audio_metric": "first synthesized chunk samples ready; not audible speech",
                        "tts_input": "actual STT transcript, matching same-language Solo playback",
                        "chunk_synthesis": "sequential CPU synthesis only; no simulated concurrent audio playback",
                        "strict_scoring": "existing scorer: NFKC/casefold/whitespace, punctuation retained"},
        "parameters": {"cloud_stt_threads": 2, "cloud_tts_threads": 2, "cloud_vad_threads": 1,
                       "android_engine_defaults": {"stt_threads": 4, "tts_threads": 4, "vad_threads": 1},
                       "stt_sample_rate": SAMPLE_RATE, "feature_dim": 80, "decoding": "greedy_search",
                       "vad_threshold": 0.5, "vad_min_silence_sec": 0.25, "vad_min_speech_sec": 0.25,
                       "vad_max_speech_sec": 30, "vad_window_samples": VAD_WINDOW, "vad_pad_samples": VAD_PAD,
                       "tts_sid": 0, "tts_speed": 1.0, "chunk_max_utf16_units": 140, "chunk_min_sentence_utf16_units": 40},
        "files": {name: file_info(path, args.hash_models) for name, path in paths.items()},
        "source_logic": {name: file_info(ROOT / path, True) for name, path in {
            "chunks": "app/src/main/java/com/itantra/session/ChunkedSpeaker.kt",
            "vad": "app/src/main/java/com/itantra/speech/VadTrimmer.kt",
            "stt": "app/src/main/java/com/itantra/speech/SttEngine.kt",
            "tts": "app/src/main/java/com/itantra/speech/TtsEngine.kt"}.items()},
        "model_load_ms": loads, "memory": memory, "cases": {},
    }
    for label, text in PHRASES.items():
        print(f"Measuring {label}: {len(text.split())} whitespace-delimited Hindi words, {args.runs} runs...", flush=True)
        samples, source = synthetic_clip(tts, text)
        warmup = measure_pipeline(samples, text, stt, tts, vad)
        rows = []
        for index in range(args.runs):
            row = measure_pipeline(samples, text, stt, tts, vad)
            row["run"] = index + 1
            rows.append(row)
        report["cases"][label] = {
            "reference": text, "reference_words": len(text.split()), "reference_chars": len(text),
            "input": source, "warmup": warmup, "raw_runs": rows, "summary": summarize_runs(rows),
        }
        del samples
    report["memory"]["after_all_runs"] = rss_snapshot()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.out}; cloud synthetic speech measurements only.", flush=True)


if __name__ == "__main__":
    main()

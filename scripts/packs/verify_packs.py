"""Verify language packs with REAL speech (Phase 3 step 3). Runs in CI (.github/workflows/verify-packs.yml).

    python scripts/packs/verify_packs.py --packs dist/packs --langs "hi ta" --clips 3 \
        --espeak-data path/to/espeak-ng-data --out dist/verify

For each language:
  1. speak pack: transcribe the first N recordings of the Google FLEURS test split (native speakers, CC-BY-4.0).
     Only the start of each language's test.tar.gz is streamed (a few MB), not the whole archive.
     Scores: CER (characters, spaces ignored) and WER against the true transcription.
  2. listen pack: synthesize the first 2 FLEURS sentences (full sentences, not a short phrase) → WAV files to
     listen to, plus a TTS → STT round trip through the speak pack as an intelligibility check.
Writes <out>/report.json and one summary line per language to stdout.
"""

import argparse
import csv
import io
import json
import re
import sys
import tarfile
import time
import urllib.request
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

FLEURS = "https://huggingface.co/datasets/google/fleurs/resolve/main/data"
FLEURS_CODE = {"hi": "hi_in", "en": "en_us", "mr": "mr_in", "gu": "gu_in", "bn": "bn_in", "ta": "ta_in",
               "te": "te_in", "kn": "kn_in", "ml": "ml_in", "or": "or_in"}


# ---------- text scoring ----------

def normalize(text: str) -> str:
    text = text.lower().replace("़", "").replace("ँ", "ं")  # nukta, chandrabindu (Devanagari)
    text = re.sub(r"[।॥,.?!\"'“”‘’:;()\[\]\-–—…/]", " ", text)
    return " ".join(text.split())


def edit_distance(a: list, b: list) -> int:
    d = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, d[0] = d[0], i
        for j, y in enumerate(b, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (x != y))
    return d[len(b)]


def cer(ref: str, hyp: str) -> float:
    r, h = list(normalize(ref).replace(" ", "")), list(normalize(hyp).replace(" ", ""))
    return edit_distance(r, h) / max(len(r), 1)


def wer(ref: str, hyp: str) -> float:
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h) / max(len(r), 1)


# ---------- FLEURS ----------

def read_wav_bytes(data: bytes) -> tuple[np.ndarray, int]:
    """Mono float32 samples from a RIFF WAV: 16-bit PCM (format 1) or 32-bit float (format 3, as FLEURS uses).
    Python's wave module only reads PCM."""
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("not a WAV file")
    pos, fmt, rate, channels, bits, samples = 12, None, None, 1, 16, None
    while pos + 8 <= len(data):
        chunk, size = data[pos:pos + 4], int.from_bytes(data[pos + 4:pos + 8], "little")
        body = data[pos + 8:pos + 8 + size]
        if chunk == b"fmt ":
            fmt = int.from_bytes(body[0:2], "little")
            channels = int.from_bytes(body[2:4], "little")
            rate = int.from_bytes(body[4:8], "little")
            bits = int.from_bytes(body[14:16], "little")
            if fmt == 0xFFFE:  # WAVE_FORMAT_EXTENSIBLE: real format is in the sub-format GUID
                fmt = int.from_bytes(body[24:26], "little")
        elif chunk == b"data":
            if fmt == 1 and bits == 16:
                samples = np.frombuffer(body, dtype="<i2").astype(np.float32) / 32768
            elif fmt == 3 and bits == 32:
                samples = np.frombuffer(body, dtype="<f4").astype(np.float32)
            else:
                raise ValueError(f"unsupported WAV format {fmt}/{bits}-bit")
        pos += 8 + size + (size & 1)
    if samples is None or rate is None:
        raise ValueError("WAV without fmt/data")
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)
    return samples, rate


def get(url: str, attempts: int = 3):
    for i in range(attempts):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "itantra-verify"}), timeout=60)
        except Exception:
            if i == attempts - 1:
                raise
            time.sleep(3 * (i + 1))


def fleurs_clips(lang: str, n: int) -> list[dict]:
    code = FLEURS_CODE[lang]
    with get(f"{FLEURS}/{code}/test.tsv") as r:
        rows = list(csv.reader(io.StringIO(r.read().decode("utf-8")), delimiter="\t", quoting=csv.QUOTE_NONE))
    text = {row[1]: row[2] for row in rows if len(row) > 2}
    clips, seen_ids = [], set()
    with get(f"{FLEURS}/{code}/audio/test.tar.gz") as resp:
        with tarfile.open(fileobj=resp, mode="r|gz") as tar:  # streaming: stop as soon as we have n clips
            for m in tar:
                name = Path(m.name).name
                if not m.isfile() or name not in text:
                    continue
                sentence_id = next(row[0] for row in rows if row[1] == name)
                if sentence_id in seen_ids:  # FLEURS has several speakers per sentence; keep distinct sentences
                    continue
                pcm, rate = read_wav_bytes(tar.extractfile(m).read())
                clips.append({"file": name, "text": text[name], "samples": pcm, "rate": rate})
                seen_ids.add(sentence_id)
                if len(clips) == n:
                    break
    return clips


# ---------- engines ----------

def recognizer(pack_dir: Path):
    pack = json.loads((pack_dir / "pack.json").read_text(encoding="utf-8"))
    e = pack["engine"]
    return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(pack_dir / e["model"]), tokens=str(pack_dir / e["tokens"]), num_threads=2,
        sample_rate=e.get("sample_rate", 16000), feature_dim=e.get("feature_dim", 80), decoding_method="greedy_search")


def transcribe(rec, samples, rate: int) -> tuple[str, float]:
    t = time.perf_counter()
    s = rec.create_stream()
    s.accept_waveform(rate, samples)
    rec.decode_stream(s)
    return s.result.text, time.perf_counter() - t


def voice(pack_dir: Path, espeak: Path | None):
    pack = json.loads((pack_dir / "pack.json").read_text(encoding="utf-8"))
    e = pack["engine"]
    data_dir = str(espeak) if "espeak-ng-data" in e.get("needs", []) and espeak else ""
    return sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(pack_dir / e["model"]), tokens=str(pack_dir / e["tokens"]),
                                                   data_dir=data_dir),
        num_threads=2)))


def write_wav(path: Path, samples, rate: int) -> None:
    pcm = (np.clip(np.asarray(samples), -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


# ---------- main ----------

def verify(lang: str, packs: Path, clips_n: int, espeak: Path | None, out: Path,
           speak_id: str = "{lang}-speak", listen_id: str = "{lang}-listen") -> dict:
    result = {"lang": lang, "speak": speak_id.format(lang=lang), "listen": listen_id.format(lang=lang)}
    clips = fleurs_clips(lang, clips_n)
    rec = recognizer(packs / result["speak"])
    real = []
    for c in clips:
        hyp, secs = transcribe(rec, c["samples"], c["rate"])
        real.append({"file": c["file"], "ref": c["text"], "hyp": hyp, "cer": round(cer(c["text"], hyp), 3),
                     "wer": round(wer(c["text"], hyp), 3), "audio_s": round(len(c["samples"]) / c["rate"], 2),
                     "rtf": round(secs / (len(c["samples"]) / c["rate"]), 3)})
    result["real_speech"] = real
    result["real_cer"] = round(float(np.mean([r["cer"] for r in real])), 3)
    result["real_wer"] = round(float(np.mean([r["wer"] for r in real])), 3)

    tts = voice(packs / result["listen"], espeak)
    samples = []
    for i, c in enumerate(clips[:2], 1):
        t = time.perf_counter()
        audio = tts.generate(c["text"], sid=0, speed=1.0)
        gen = time.perf_counter() - t
        secs = len(audio.samples) / audio.sample_rate
        wav = out / f"{result['listen']}-voice-{i}.wav"
        write_wav(wav, audio.samples, audio.sample_rate)
        back, _ = transcribe(rec, np.asarray(audio.samples, dtype=np.float32), audio.sample_rate)
        samples.append({"wav": wav.name, "text": c["text"], "audio_s": round(secs, 2), "tts_rtf": round(gen / max(secs, 1e-6), 3),
                        "round_trip": back, "round_trip_cer": round(cer(c["text"], back), 3)})
    result["voice"] = samples
    result["voice_round_trip_cer"] = round(float(np.mean([s["round_trip_cer"] for s in samples])), 3) if samples else None
    return result


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--packs", type=Path, required=True)
    ap.add_argument("--langs", default=" ".join(FLEURS_CODE))
    ap.add_argument("--clips", type=int, default=3)
    ap.add_argument("--espeak-data", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--speak-id", default="{lang}-speak", help="pack folder name template (experiments)")
    ap.add_argument("--listen-id", default="{lang}-listen", help="pack folder name template (experiments)")
    ap.add_argument("--report", default="report.json", help="report file name inside --out")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    results, failed = [], []
    for lang in args.langs.split():
        try:
            r = verify(lang, args.packs, args.clips, args.espeak_data, args.out, args.speak_id, args.listen_id)
            tag = "" if (args.speak_id, args.listen_id) == ("{lang}-speak", "{lang}-listen") else                 f" [{r['speak']} + {r['listen']}]"
            print(f"{lang}:{tag} real speech CER {r['real_cer']:.1%} WER {r['real_wer']:.1%} | "
                  f"voice round trip CER {r['voice_round_trip_cer']:.1%} | "
                  f"STT RTF {np.mean([x['rtf'] for x in r['real_speech']]):.2f}, "
                  f"TTS RTF {np.mean([x['tts_rtf'] for x in r['voice']]):.2f}")
        except Exception as e:  # keep going; report at the end
            r = {"lang": lang, "error": f"{type(e).__name__}: {e}"}
            failed.append(lang)
            print(f"{lang}: FAILED {r['error']}")
        results.append(r)
    (args.out / args.report).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    if failed:
        sys.exit(f"failed: {' '.join(failed)}")


if __name__ == "__main__":
    main()

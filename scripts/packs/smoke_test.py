"""Smoke-test built packs with sherpa-onnx (the same engine the app uses).

    python scripts/packs/smoke_test.py dist/packs/ml-listen dist/packs/ta-listen dist/packs/ta-speak \
        --espeak-data path/to/espeak-ng-data --out dist/smoke

listen packs: synthesize the language's test phrase → <out>/<pack>.wav (listen to it in step 3).
speak packs:  decode <out>/<lang>-listen.wav if that voice was built in the same run (a TTS → STT round trip,
              reported as a character error rate), otherwise just load the model and decode 1 s of silence.
Exits non-zero if any pack fails to load or produces no audio. Also checks every file's SHA-256 against pack.json.
"""

import argparse
import hashlib
import json
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parents[2]
PHRASES = json.loads((ROOT / "scripts" / "packs" / "languages.json").read_text(encoding="utf-8"))["test_phrases"]


def cer(ref: str, hyp: str) -> float:
    strip = lambda s: [c for c in s if c.isalnum() or "ऀ" <= c <= "෿"]  # letters/marks of Indic scripts
    r, h = strip(ref.lower()), strip(hyp.lower())
    d = list(range(len(h) + 1))
    for i, a in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, b in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (a != b))
    return d[len(h)] / max(len(r), 1)


def check_files(pack_dir: Path, pack: dict) -> None:
    for f in pack["files"]:
        data = (pack_dir / f["path"]).read_bytes()
        if len(data) != f["size"] or hashlib.sha256(data).hexdigest() != f["sha256"]:
            sys.exit(f"{pack['id']}: {f['path']} does not match pack.json")


def write_wav(path: Path, samples, rate: int) -> None:
    pcm = (np.clip(np.asarray(samples), -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def read_wav(path: Path):
    with wave.open(str(path)) as w:
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
        return pcm.astype(np.float32) / 32768, w.getframerate()


def test_listen(pack_dir: Path, pack: dict, espeak: Path | None, out: Path) -> dict:
    e = pack["engine"]
    data_dir = ""
    if "espeak-ng-data" in e.get("needs", []):
        if not espeak:
            sys.exit(f"{pack['id']}: needs --espeak-data")
        data_dir = str(espeak)
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(pack_dir / e["model"]), tokens=str(pack_dir / e["tokens"]),
                                                   data_dir=data_dir),
        num_threads=2)))
    audio = tts.generate(PHRASES[pack["lang"]], sid=0, speed=1.0)
    secs = len(audio.samples) / audio.sample_rate
    rms = float(np.sqrt(np.mean(np.square(audio.samples)))) if audio.samples else 0.0
    wav = out / f"{pack['id']}.wav"
    write_wav(wav, audio.samples, audio.sample_rate)
    ok = secs >= 0.5 and rms > 0.005
    return {"pack": pack["id"], "ok": ok, "audio_s": round(secs, 2), "rms": round(rms, 4), "wav": wav.name}


def test_speak(pack_dir: Path, pack: dict, out: Path) -> dict:
    e = pack["engine"]
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(pack_dir / e["model"]), tokens=str(pack_dir / e["tokens"]), num_threads=2,
        sample_rate=e.get("sample_rate", 16000), feature_dim=e.get("feature_dim", 80), decoding_method="greedy_search")
    wav = out / f"{pack['lang']}-listen.wav"
    if wav.exists():
        samples, rate = read_wav(wav)
        mode = f"round trip via {wav.name}"
    else:
        samples, rate = np.zeros(16000, dtype=np.float32), 16000
        mode = "load + 1 s silence (no voice built in this run)"
    s = rec.create_stream()
    s.accept_waveform(rate, samples)
    rec.decode_stream(s)
    text = s.result.text
    result = {"pack": pack["id"], "ok": True, "mode": mode, "text": text}
    if wav.exists():
        result["cer_vs_phrase"] = round(cer(PHRASES[pack["lang"]], text), 3)
        result["ok"] = len(text.strip()) > 0
    return result


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("packs", nargs="+", type=Path)
    ap.add_argument("--espeak-data", type=Path)
    ap.add_argument("--out", type=Path, default=ROOT / "dist" / "smoke")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    # Voices first, so speak packs can round-trip their audio.
    dirs = sorted(args.packs, key=lambda p: 0 if p.name.endswith("-listen") else 1)
    results = []
    for d in dirs:
        if not (d / "pack.json").exists():  # a pack whose build failed (already reported by the build step)
            print(json.dumps({"pack": d.name, "ok": False, "error": "not built (no pack.json)"}))
            results.append({"pack": d.name, "ok": False, "error": "not built"})
            continue
        pack = json.loads((d / "pack.json").read_text(encoding="utf-8"))
        check_files(d, pack)
        r = test_listen(d, pack, args.espeak_data, args.out) if pack["kind"] == "listen" else test_speak(d, pack, args.out)
        r["size_mb"] = round(pack["size"] / 1e6, 1)
        print(json.dumps(r, ensure_ascii=False))
        results.append(r)
    (args.out / "report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    if not all(r["ok"] for r in results):
        sys.exit("some packs failed")


if __name__ == "__main__":
    main()

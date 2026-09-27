"""Build one iTantra language pack: dist/packs/<lang>-<kind>/ with pack.json, plus <lang>-<kind>.zip.

    python scripts/packs/build_pack.py ta speak
    python scripts/packs/build_pack.py ml listen
    python scripts/packs/build_pack.py ta listen --vits-dir /path/to/jaywalnut310/vits   # MMS: needs PyTorch (CI)

Kinds (see docs/PHASE3_PLAN.md):
  speak   STT for the language the user speaks (IndicConformer int8 + Phase 0 metadata patch, or a sherpa model)
  listen  int8 TTS voice for a language the phone should be able to hear
          (Piper voices from sherpa-onnx reuse the app's bundled espeak-ng-data instead of shipping their own)

Everything about a language comes from scripts/packs/languages.json; nothing here is language-specific.
"""

import argparse
import datetime
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from fetch_models import STT_METADATA, patch_indicconformer_metadata  # noqa: E402

LANGS = json.loads((ROOT / "scripts" / "packs" / "languages.json").read_text(encoding="utf-8"))
CACHE = ROOT / "scripts" / "downloads"
DIST = ROOT / "dist" / "packs"
GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
PACK_FORMAT = 1


def download(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  get {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    urllib.request.urlretrieve(url, tmp)
    tmp.replace(dest)
    return dest


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract_sherpa_asset(release: str, asset: str) -> Path:
    archive = download(f"{GH}/{release}/{asset}.tar.bz2", CACHE / f"{asset}.tar.bz2")
    out = CACHE / asset
    if not out.exists():
        with tarfile.open(archive, "r:bz2") as tar:
            tar.extractall(CACHE, filter="data")
    return out


def pick_model(folder: Path) -> Path:
    models = sorted(folder.glob("*.onnx"))
    int8 = [m for m in models if "int8" in m.name]
    if not (int8 or models):
        sys.exit(f"no .onnx model in {folder}")
    return (int8 or models)[0]


# ---------- speak (STT) ----------

def build_speak(lang: str, cfg: dict, out: Path) -> tuple[dict, list[dict]]:
    if cfg["source"] == "indicconformer":
        from huggingface_hub import HfApi, hf_hub_download

        repo = f"OpenVoiceOS/ai4bharat-indicconformer-{lang}-onnx"
        commit = HfApi().model_info(repo).sha
        model = Path(hf_hub_download(repo, "model.int8.onnx", revision=commit, cache_dir=CACHE / "hf"))
        vocab = Path(hf_hub_download(repo, "vocab.txt", revision=commit, cache_dir=CACHE / "hf"))
        lines = vocab.read_text(encoding="utf-8").splitlines()
        if len(lines) != int(STT_METADATA["vocab_size"]) or not lines[-1].startswith("<blk>"):
            sys.exit(f"{repo}: unexpected vocab.txt ({len(lines)} lines)")
        patch_indicconformer_metadata(model, out / "model.int8.onnx")
        shutil.copy2(vocab, out / "tokens.txt")
        engine = {"type": "nemo_ctc", "model": "model.int8.onnx", "tokens": "tokens.txt", "sample_rate": 16000,
                  "feature_dim": 80}
        sources = [{"url": f"https://huggingface.co/{repo}", "commit": commit, "licence": "MIT",
                    "note": "metadata added by scripts/fetch_models.py (vocab_size, subsampling_factor, normalize_type)"}]
        return engine, sources

    folder = extract_sherpa_asset("asr-models", cfg["asset"])
    model = pick_model(folder)
    shutil.copy2(model, out / "model.int8.onnx")
    shutil.copy2(folder / "tokens.txt", out / "tokens.txt")
    engine = {"type": cfg["type"], "model": "model.int8.onnx", "tokens": "tokens.txt", "sample_rate": 16000,
              "feature_dim": 80}
    sources = [{"url": f"{GH}/asr-models/{cfg['asset']}.tar.bz2", "file": model.name, "licence": cfg.get("licence", "")}]
    return engine, sources


# ---------- listen (TTS) ----------

def build_listen(lang: str, cfg: dict, out: Path, vits_dir: Path | None) -> tuple[dict, list[dict]]:
    if cfg["type"] == "piper":
        folder = extract_sherpa_asset("tts-models", cfg["asset"])
        model = pick_model(folder)
        shutil.copy2(model, out / "model.int8.onnx")
        shutil.copy2(folder / "tokens.txt", out / "tokens.txt")
        for extra in ("MODEL_CARD", "README.md"):
            if (folder / extra).exists():
                shutil.copy2(folder / extra, out / extra)
        engine = {"type": "piper", "model": "model.int8.onnx", "tokens": "tokens.txt",
                  "needs": ["espeak-ng-data"]}  # shared with the app's bundled copy
        sources = [{"url": f"{GH}/tts-models/{cfg['asset']}.tar.bz2", "file": model.name,
                    "licence": cfg.get("licence", "see MODEL_CARD")}]
        return engine, sources

    if cfg["type"] == "mms":
        if vits_dir is None:
            sys.exit("MMS voices need --vits-dir (a checkout of github.com/jaywalnut310/vits) and PyTorch; "
                     "they are built by .github/workflows/build-packs.yml")
        from export_mms import export  # needs torch; imported only here

        # fp16 weights + fp32 compute: 57.6 MB and RTF 0.27 for Tamil, vs int8 38 MB but RTF 1.0-1.3 (step 3).
        quant = cfg.get("quant", "fp16w")
        info = export(cfg["iso"], LANGS["languages"][lang]["name"], LANGS["test_phrases"][lang], vits_dir, out, CACHE,
                      quant=quant)
        engine = {"type": "mms", "model": info["model_file"], "tokens": "tokens.txt",
                  "sample_rate": info["sample_rate"], "quant": quant}
        vits_commit = subprocess.run(["git", "-C", str(vits_dir), "rev-parse", "HEAD"],
                                     capture_output=True, text=True).stdout.strip()
        sources = [
            {"url": f"https://dl.fbaipublicfiles.com/mms/tts/{cfg['iso']}.tar.gz", "licence": "CC-BY-NC-4.0",
             "note": f"Meta MMS original checkpoint; exported with scripts/packs/export_mms.py (quantization: {quant})"},
            {"url": "https://github.com/jaywalnut310/vits", "commit": vits_commit, "licence": "MIT",
             "note": "model code used for the export"},
        ]
        return engine, sources

    sys.exit(f"unknown listen type {cfg['type']}")


# ---------- pack ----------

def build(lang: str, kind: str, vits_dir: Path | None = None, variant: str = "", override: dict | None = None) -> Path:
    """[variant] + [override] build an alternative (e.g. another model) as <lang>-<kind>-<variant> for comparison."""
    if lang not in LANGS["languages"]:
        sys.exit(f"unknown language {lang}; known: {', '.join(LANGS['languages'])}")
    meta = json.loads(json.dumps(LANGS["languages"][lang]))
    if override:
        meta[kind] = {**meta[kind], **override} if override.get("type") in (None, meta[kind].get("type")) else override
    pack_id = f"{lang}-{kind}" + (f"-{variant}" if variant else "")
    out = DIST / pack_id
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    print(f"[{pack_id}]")

    if kind == "speak":
        engine, sources = build_speak(lang, meta["speak"], out)
    else:
        engine, sources = build_listen(lang, meta["listen"], out, vits_dir)

    files = [{"path": p.name, "size": p.stat().st_size, "sha256": sha256(p)} for p in sorted(out.iterdir()) if p.is_file()]
    builder = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True).stdout.strip()
    pack = {
        "format": PACK_FORMAT,
        "id": pack_id,
        "lang": lang,
        "packet_code": meta["packet_code"],
        "name": meta["name"],
        "native": meta["native"],
        "kind": kind,
        "engine": engine,
        "files": files,
        "size": sum(f["size"] for f in files),
        "sources": sources,
        "built": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "builder": f"scripts/packs/build_pack.py @ {builder}",
    }
    (out / "pack.json").write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    zip_path = DIST / f"{pack_id}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(out.iterdir()):
            z.write(p, f"{pack_id}/{p.name}")
    print(f"  {pack_id}: {pack['size'] / 1e6:.1f} MB unpacked, {zip_path.stat().st_size / 1e6:.1f} MB zipped")
    for f in files:
        print(f"    {f['size'] / 1e6:8.2f} MB  {f['path']}  {f['sha256'][:16]}")
    return out


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("lang")
    ap.add_argument("kind", choices=["speak", "listen"])
    ap.add_argument("--vits-dir", type=Path, help="checkout of github.com/jaywalnut310/vits (MMS voices only)")
    ap.add_argument("--variant", default="", help="build an alternative as <lang>-<kind>-<variant> (experiments)")
    ap.add_argument("--override", default="",
                    help="""JSON merged into the speak/listen config, e.g. {"quant": "no-conv"}""")
    args = ap.parse_args()
    build(args.lang, args.kind, args.vits_dir, args.variant, json.loads(args.override) if args.override else None)


if __name__ == "__main__":
    main()

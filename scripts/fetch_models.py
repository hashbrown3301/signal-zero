"""Download and prepare everything iTantra needs that is too big for git.

Run from the repo root:
    python scripts/fetch_models.py

Produces:
    app/libs/sherpa-onnx-<ver>.aar
    app/src/main/assets/vad/silero_vad.onnx
    app/src/main/assets/stt/model.int8.onnx   (patched with sherpa-onnx metadata)
    app/src/main/assets/stt/tokens.txt
    app/src/main/assets/tts/<voice>.onnx, tokens.txt, espeak-ng-data/
Downloads are cached in scripts/downloads/, so re-running is cheap.
"""

import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

import onnx
from huggingface_hub import hf_hub_download

SHERPA_VERSION = "1.13.7"
STT_REPO = "OpenVoiceOS/ai4bharat-indicconformer-hi-onnx"
TTS_VOICE = "vits-piper-hi_IN-priyamvada-medium"

GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
AAR_URL = f"{GH}/v{SHERPA_VERSION}/sherpa-onnx-{SHERPA_VERSION}.aar"
VAD_URL = f"{GH}/asr-models/silero_vad.onnx"
TTS_URL = f"{GH}/tts-models/{TTS_VOICE}.tar.bz2"

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "scripts" / "downloads"
LIBS = ROOT / "app" / "libs"
ASSETS = ROOT / "app" / "src" / "main" / "assets"

# Values sherpa-onnx's NeMo CTC loader reads from the model metadata.
# vocab_size and subsampling_factor come from the repo's vocab.txt / config.json;
# NeMo Conformer preprocessors normalise each mel bin per utterance.
STT_METADATA = {
    "model_type": "EncDecCTCModelBPE",
    "vocab_size": "257",
    "subsampling_factor": "4",
    "normalize_type": "per_feature",
}


def download(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  cached  {dest.name}")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    print(f"  get     {url}")

    def progress(blocks, block_size, total):
        if total > 0:
            pct = min(100, blocks * block_size * 100 // total)
            print(f"\r          {pct:3d}% of {total / 1e6:.1f} MB", end="", flush=True)

    urllib.request.urlretrieve(url, tmp, progress)
    print()
    tmp.replace(dest)
    return dest


def fetch_aar() -> None:
    print("[1/4] sherpa-onnx AAR")
    aar = download(AAR_URL, CACHE / AAR_URL.rsplit("/", 1)[1])
    LIBS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(aar, LIBS / aar.name)


def fetch_vad() -> None:
    print("[2/4] Silero VAD")
    vad = download(VAD_URL, CACHE / "silero_vad.onnx")
    out = ASSETS / "vad"
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(vad, out / vad.name)


def patch_indicconformer_metadata(src: Path, dst: Path) -> None:
    model = onnx.load(str(src))

    def describe(values):
        for v in values:
            dims = [d.dim_param or d.dim_value for d in v.type.tensor_type.shape.dim]
            print(f"          {v.name}: {dims}")

    print("        inputs:")
    describe(model.graph.input)
    print("        outputs:")
    describe(model.graph.output)

    input_names = [i.name for i in model.graph.input]
    if input_names != ["audio_signal", "length"]:
        sys.exit(f"Unexpected STT inputs {input_names}; sherpa-onnx NeMo CTC expects audio_signal, length")

    existing = {p.key: p for p in model.metadata_props}
    for key, value in STT_METADATA.items():
        prop = existing.get(key) or model.metadata_props.add()
        prop.key, prop.value = key, value
    print("        metadata: " + ", ".join(f"{p.key}={p.value}" for p in model.metadata_props))

    dst.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(dst))


def fetch_stt() -> None:
    print("[3/4] IndicConformer Hindi STT")
    model = Path(hf_hub_download(STT_REPO, "model.int8.onnx", cache_dir=CACHE / "hf"))
    vocab = Path(hf_hub_download(STT_REPO, "vocab.txt", cache_dir=CACHE / "hf"))

    lines = vocab.read_text(encoding="utf-8").splitlines()
    if len(lines) != int(STT_METADATA["vocab_size"]) or not lines[-1].startswith("<blk>"):
        sys.exit(f"vocab.txt changed upstream ({len(lines)} lines); update STT_METADATA")

    out = ASSETS / "stt"
    patch_indicconformer_metadata(model, out / "model.int8.onnx")
    shutil.copy2(vocab, out / "tokens.txt")


def fetch_tts() -> None:
    print(f"[4/4] Piper TTS voice {TTS_VOICE}")
    archive = download(TTS_URL, CACHE / f"{TTS_VOICE}.tar.bz2")
    out = ASSETS / "tts"
    if out.exists():
        shutil.rmtree(out)
    with tarfile.open(archive, "r:bz2") as tar:
        tar.extractall(CACHE, filter="data")
    shutil.copytree(CACHE / TTS_VOICE, out)
    for f in sorted(out.iterdir()):
        print(f"          {f.name}{'/' if f.is_dir() else ''}")


def main() -> None:
    fetch_aar()
    fetch_vad()
    fetch_stt()
    fetch_tts()
    print("Done.")


if __name__ == "__main__":
    main()

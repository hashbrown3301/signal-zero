"""Export a Meta MMS TTS voice to an int8 ONNX model that sherpa-onnx can run (character front-end).

Runs in CI (.github/workflows/build-packs.yml); needs PyTorch and a checkout of the original VITS code
(https://github.com/jaywalnut310/vits, MIT). Follows sherpa-onnx's scripts/vits/export-onnx-ljs.py (Apache-2.0):
same inputs (x, x_length, noise_scale, length_scale, noise_scale_w) and output (y), then dynamic int8 quantization.

MMS checkpoints: https://dl.fbaipublicfiles.com/mms/tts/<iso>.tar.gz (CC-BY-NC-4.0), holding G_100000.pth,
config.json and vocab.txt (one character per line). The voice reads native-script text directly (no uroman).
"""

import json
import sys
import tarfile
import types
import urllib.request
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic


def _load_vits(vits_dir: Path):
    sys.path.insert(0, str(vits_dir))
    # monotonic_align is a Cython extension used only for training; inference never calls it.
    sys.modules.setdefault("monotonic_align", types.ModuleType("monotonic_align"))
    from models import SynthesizerTrn  # noqa: E402

    return SynthesizerTrn


class OnnxModel(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x, x_length, noise_scale, length_scale, noise_scale_w):
        return self.model.infer(x=x, x_lengths=x_length, noise_scale=noise_scale,
                                length_scale=length_scale, noise_scale_w=noise_scale_w)[0]


def _fetch(iso: str, cache: Path) -> Path:
    folder = cache / f"mms-{iso}"
    if (folder / "G_100000.pth").exists():
        return folder
    archive = cache / f"mms-{iso}.tar.gz"
    if not archive.exists():
        url = f"https://dl.fbaipublicfiles.com/mms/tts/{iso}.tar.gz"
        print(f"  get {url}")
        urllib.request.urlretrieve(url, archive)
    folder.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        for m in tar.getmembers():
            name = Path(m.name).name
            if name in ("G_100000.pth", "config.json", "vocab.txt") and m.isfile():
                with tar.extractfile(m) as src, open(folder / name, "wb") as dst:
                    dst.write(src.read())
    return folder


def _ids(text: str, symbols: list[str], add_blank: bool) -> list[int]:
    index = {s: i for i, s in enumerate(symbols)}
    ids = [index[c] for c in text.lower() if c in index]
    if add_blank:
        out = [0] * (2 * len(ids) + 1)
        out[1::2] = ids
        ids = out
    return ids


@torch.no_grad()
def export(iso: str, language: str, test_phrase: str, vits_dir: Path, out: Path, cache: Path) -> dict:
    SynthesizerTrn = _load_vits(vits_dir)
    src = _fetch(iso, cache)
    hps = json.loads((src / "config.json").read_text(encoding="utf-8"))
    symbols = [line.rstrip("\n") for line in (src / "vocab.txt").read_text(encoding="utf-8").splitlines(True)]
    data, model_cfg = hps["data"], hps["model"]

    net_g = SynthesizerTrn(
        len(symbols),
        data["filter_length"] // 2 + 1,
        hps["train"]["segment_size"] // data["hop_length"],
        n_speakers=data.get("n_speakers", 0),
        **model_cfg,
    ).eval()
    ckpt = torch.load(src / "G_100000.pth", map_location="cpu", weights_only=False)
    state = ckpt.get("model", ckpt)
    missing, unexpected = net_g.load_state_dict(state, strict=False)
    if missing:
        sys.exit(f"MMS {iso}: checkpoint is missing {len(missing)} weights, e.g. {missing[:3]}")
    print(f"  loaded MMS {iso}: {len(symbols)} symbols, {data['sampling_rate']} Hz, unused keys: {len(unexpected)}")

    add_blank = bool(data.get("add_blank", True))
    ids = _ids(test_phrase, symbols, add_blank)
    if len(ids) < 5:
        sys.exit(f"MMS {iso}: test phrase maps to almost no symbols; wrong script?")
    x = torch.tensor([ids], dtype=torch.int64)
    args = (x, torch.tensor([x.shape[1]], dtype=torch.int64), torch.tensor([0.667]), torch.tensor([1.0]),
            torch.tensor([0.8]))

    fp32 = out / "model.fp32.onnx"
    export_kwargs = dict(
        opset_version=13,
        input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w"],
        output_names=["y"],
        dynamic_axes={"x": {0: "N", 1: "L"}, "x_length": {0: "N"}, "y": {0: "N", 2: "L"}},
    )
    try:
        torch.onnx.export(OnnxModel(net_g), args, str(fp32), dynamo=False, **export_kwargs)
    except TypeError:  # older torch without the dynamo argument
        torch.onnx.export(OnnxModel(net_g), args, str(fp32), **export_kwargs)

    meta = {
        "model_type": "vits",
        "comment": "mms",
        "language": language,
        "frontend": "characters",
        "add_blank": int(add_blank),
        "blank_id": 0,
        "n_speakers": max(1, int(data.get("n_speakers", 0))),
        "sample_rate": int(data["sampling_rate"]),
        "punctuation": "",
    }
    model = onnx.load(str(fp32))
    for k, v in meta.items():
        p = model.metadata_props.add()
        p.key, p.value = k, str(v)
    onnx.save(model, str(fp32))

    int8 = out / "model.int8.onnx"
    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QUInt8)
    # quantize_dynamic keeps metadata_props on recent onnxruntime; re-add to be sure.
    q = onnx.load(str(int8))
    if not {p.key for p in q.metadata_props} >= set(meta):
        del q.metadata_props[:]
        for k, v in meta.items():
            p = q.metadata_props.add()
            p.key, p.value = k, str(v)
        onnx.save(q, str(int8))

    # tokens.txt in sherpa-onnx format ("<symbol> <id>"; a space symbol is written as a leading space).
    with open(out / "tokens.txt", "w", encoding="utf-8") as f:
        for i, s in enumerate(symbols):
            f.write(f"{s} {i}\n")

    # Sanity check both models on the test phrase with onnxruntime before we throw the fp32 one away.
    feed = {"x": x.numpy(), "x_length": np.array([x.shape[1]], dtype=np.int64),
            "noise_scale": np.array([0.667], dtype=np.float32), "length_scale": np.array([1.0], dtype=np.float32),
            "noise_scale_w": np.array([0.8], dtype=np.float32)}
    for path in (fp32, int8):
        y = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]).run(None, feed)[0]
        secs = y.size / meta["sample_rate"]
        print(f"  {path.name}: {path.stat().st_size / 1e6:.1f} MB, test phrase → {secs:.2f} s audio, "
              f"rms {float(np.sqrt(np.mean(y ** 2))):.4f}")
        if secs < 0.3:
            sys.exit(f"MMS {iso}: {path.name} produced almost no audio")
    fp32.unlink()
    return meta

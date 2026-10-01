"""Fetch the pinned offline translation pack using Python's standard library.

Run from the repository root (no Hugging Face account or token is needed):
    python3 scripts/fetch_translation_model.py
    python3 scripts/fetch_translation_model.py --zip
    python3 scripts/fetch_translation_model.py --output /tmp/nllb --verify-only --zip /tmp/nllb.zip

The default output is dist/translation/nllb. Existing files are checked against
their pinned size and SHA-256 before reuse; interrupted downloads resume from
<filename>.part. --zip optionally writes an Android-importable ZIP containing
the three model files and model.json at its root. Without a ZIP path, the archive
is written next to the output directory as <model-id>.zip. --verify-only forbids
network access and is useful for packaging files downloaded on another machine.

The approximately 899 MB NLLB pack is CC BY-NC 4.0, for noncommercial research
and evaluation. Upstream does not release this model for production deployment.
Its license and attribution are included in model.json, including inside a ZIP.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = ROOT / "dist" / "translation" / "nllb"
MODEL_ID = "nllb-200-distilled-600m-int8-v1"
REPOSITORY = "Xenova/nllb-200-distilled-600M"
REVISION = "261c31d1a5732c67cdd16d80e8d6088507c7ccea"
SOURCE = f"https://huggingface.co/{REPOSITORY}"
LICENSE = "CC-BY-NC-4.0"
LICENSE_URL = "https://creativecommons.org/licenses/by-nc/4.0/"
CHUNK_BYTES = 1024 * 1024


@dataclass(frozen=True)
class Artifact:
    name: str
    remote_path: str
    size: int
    sha256: str

    @property
    def url(self) -> str:
        return f"{SOURCE}/resolve/{REVISION}/{self.remote_path}"


ARTIFACTS = (
    Artifact(
        "encoder_model_quantized.onnx", "onnx/encoder_model_quantized.onnx", 419120483,
        "5cde664eacba07a62f198857ec6c06e09572b1ebb77c8137f1fa99ac604a3a28",
    ),
    Artifact(
        "decoder_model_merged_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx", 475505771,
        "dd66608c2a4194e78f95548fa0e64f24302303698c5b09fa8e1f9e16ec00676b",
    ),
    Artifact(
        "sentencepiece.bpe.model", "sentencepiece.bpe.model", 4852054,
        "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a",
    ),
)


class IntegrityError(ValueError):
    """A local file or server response differs from the pinned artifact."""


def verify_file(path: Path, artifact: Artifact) -> None:
    """Raise on missing files, incorrect size, or incorrect content."""
    if not path.is_file():
        raise IntegrityError(f"Missing model file: {path}")
    actual_size = path.stat().st_size
    if actual_size != artifact.size:
        raise IntegrityError(f"{path.name}: expected {artifact.size} bytes, got {actual_size}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    if digest.hexdigest() != artifact.sha256:
        raise IntegrityError(f"{path.name}: SHA-256 mismatch")


def fetch_file(
    artifact: Artifact,
    output: Path,
    *,
    timeout: float = 30,
    opener=None,
    report: Callable[[str], None] = print,
) -> Path:
    """Resume a public HTTPS download and atomically publish only verified bytes.

    An existing destination remains in place until its replacement passes both
    checks. Network interruptions retain the partial file. Invalid completed
    content is discarded, so a rerun starts from clean bytes.
    """
    destination = output / artifact.name
    if destination.exists():
        try:
            verify_file(destination, artifact)
            report(f"Verified cached {artifact.name}")
            return destination
        except IntegrityError as error:
            report(f"Replacing invalid cached file: {error}")

    output.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    offset = partial.stat().st_size if partial.is_file() else 0
    if offset == artifact.size:
        try:
            verify_file(partial, artifact)
            partial.replace(destination)
            report(f"Verified completed partial {artifact.name}")
            return destination
        except IntegrityError:
            partial.unlink()
            offset = 0
    elif offset > artifact.size:
        partial.unlink()
        offset = 0

    headers = {"User-Agent": "signal-zero-offline-model-fetch/1", "Accept-Encoding": "identity"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(artifact.url, headers=headers)
    report(f"{'Resuming' if offset else 'Downloading'} {artifact.name} ({artifact.size / 1e6:.1f} MB)")
    open_url = opener or urllib.request.urlopen
    with open_url(request, timeout=timeout) as response:
        status = response.status
        if status == 206:
            content_range = response.headers.get("Content-Range", "")
            match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", content_range)
            if not match or tuple(map(int, match.groups())) != (offset, artifact.size - 1, artifact.size):
                raise IntegrityError(f"{artifact.name}: invalid resume response {content_range!r}")
        elif status == 200:
            # Some hosts ignore Range. Their full response must replace, rather
            # than append to, the old partial bytes.
            offset = 0
        else:
            raise IntegrityError(f"{artifact.name}: unexpected HTTP status {status}")

        encoding = response.headers.get("Content-Encoding", "identity").lower()
        if encoding != "identity":
            raise IntegrityError(f"{artifact.name}: unsupported content encoding {encoding}")
        content_length = response.headers.get("Content-Length")
        if content_length is not None and int(content_length) != artifact.size - offset:
            raise IntegrityError(f"{artifact.name}: unexpected download size {content_length}")

        downloaded = offset
        milestone = int(downloaded * 20 / artifact.size)
        with partial.open("ab" if offset else "wb") as target:
            while chunk := response.read(CHUNK_BYTES):
                if downloaded + len(chunk) > artifact.size:
                    raise IntegrityError(f"{artifact.name}: response exceeds pinned size")
                target.write(chunk)
                downloaded += len(chunk)
                next_milestone = int(downloaded * 20 / artifact.size)
                if next_milestone > milestone:
                    report(f"  {artifact.name}: {downloaded * 100 // artifact.size}%")
                    milestone = next_milestone

    # A truncated HTTP response can be resumed, so keep its partial bytes.
    if downloaded != artifact.size:
        raise IntegrityError(f"{artifact.name}: download interrupted at {downloaded} of {artifact.size} bytes; rerun to resume")
    try:
        verify_file(partial, artifact)
    except IntegrityError:
        partial.unlink(missing_ok=True)
        raise
    partial.replace(destination)
    report(f"Verified {artifact.name}")
    return destination


def model_manifest(artifacts: tuple[Artifact, ...] = ARTIFACTS) -> dict:
    return {
        "format": 1,
        "id": MODEL_ID,
        "revision": REVISION,
        "source": SOURCE,
        "license": LICENSE,
        "license_url": LICENSE_URL,
        "attribution": "Meta NLLB-200 distilled 600M; quantized ONNX conversion by Xenova",
        "upstream": "https://huggingface.co/facebook/nllb-200-distilled-600M",
        "intended_use": "Noncommercial research and evaluation. Upstream does not release this model for production deployment.",
        "files": [{"name": item.name, "size": item.size, "sha256": item.sha256} for item in artifacts],
    }


def write_manifest(output: Path, artifacts: tuple[Artifact, ...] = ARTIFACTS) -> Path:
    """Write trusted metadata only after every model file passes verification."""
    for artifact in artifacts:
        verify_file(output / artifact.name, artifact)
    destination = output / "model.json"
    partial = destination.with_name("model.json.part")
    partial.write_text(json.dumps(model_manifest(artifacts), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    partial.replace(destination)
    return destination


def build_zip(output: Path, destination: Path, artifacts: tuple[Artifact, ...] = ARTIFACTS) -> Path:
    """Create the Android import contract: three root files plus model.json."""
    manifest = write_manifest(output, artifacts)
    for artifact in artifacts:
        if destination.resolve() == (output / artifact.name).resolve():
            raise ValueError("ZIP output cannot replace a model file")
    if destination.resolve() == manifest.resolve():
        raise ValueError("ZIP output cannot replace model.json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    try:
        # Storing avoids slow recompression of the large, already quantized data.
        with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            for artifact in artifacts:
                archive.write(output / artifact.name, arcname=artifact.name)
            archive.write(manifest, arcname="model.json")
        partial.replace(destination)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    return destination


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, metavar="DIR", help="Model directory (default: dist/translation/nllb)")
    parser.add_argument("--zip", nargs="?", const="", metavar="PATH", help="Create an import ZIP; omitted path defaults to <output-parent>/<model-id>.zip")
    parser.add_argument("--verify-only", action="store_true", help="Verify existing model files without any network access")
    parser.add_argument("--timeout", type=float, default=30, metavar="SECONDS", help="Per-request timeout (default: 30 seconds)")
    args = parser.parse_args(argv)
    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")
    try:
        if args.verify_only:
            for artifact in ARTIFACTS:
                verify_file(args.output / artifact.name, artifact)
                print(f"Verified {artifact.name}")
        else:
            for artifact in ARTIFACTS:
                fetch_file(artifact, args.output, timeout=args.timeout)
        if args.zip is not None:
            archive = Path(args.zip) if args.zip else args.output.parent / f"{MODEL_ID}.zip"
            build_zip(args.output, archive, ARTIFACTS)
            print(f"Import ZIP: {archive}")
        else:
            print(f"Manifest: {write_manifest(args.output, ARTIFACTS)}")
    except (OSError, ValueError) as error:
        print(f"Translation pack preparation failed: {error}", file=sys.stderr)
        return 1
    print(f"Ready: {args.output} ({sum(item.size for item in ARTIFACTS) / 1e6:.1f} MB)")
    print(f"License: {LICENSE} — noncommercial research and evaluation")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

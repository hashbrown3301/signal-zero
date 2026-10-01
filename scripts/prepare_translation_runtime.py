"""Prepare ORT Java bindings that share the speech runtime, without duplicate .so files.

Run before building, or use ``fetch_models.py --runtime-only``. The original
sherpa-onnx 1.13.7 AAR supplies ORT 1.27.1. Only the redundant base runtime is
removed from Microsoft's ORT 1.27.0 AAR; its Java API, JNI and licenses remain.
"""

import argparse
import hashlib
import shutil
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "scripts" / "downloads"
LIBS = ROOT / "app" / "libs"
MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
ANDROID_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
ORT_VERSION = "1.27.0"
ORT_NAME = f"onnxruntime-android-{ORT_VERSION}.aar"
ORT_SHA256 = "077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb"
EXT_VERSION = "0.13.0"
EXT_NAME = f"onnxruntime-extensions-android-{EXT_VERSION}.aar"
EXT_SHA256 = "cb98c6fbeac1a9707228c4b76cfbb395b4dc4d310900aefaa672561d17fd6d9d"
SHERPA_NAME = "sherpa-onnx-1.13.7.aar"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verified_download(url: str, dest: Path, expected_sha256: str) -> Path:
    if dest.exists():
        if sha256(dest) != expected_sha256:
            raise ValueError(f"Cached artifact has the wrong SHA-256: {dest}. Delete it and retry.")
        print(f"  cached  {dest.name}")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    temporary = dest.with_suffix(dest.suffix + ".part")
    print(f"  get     {url}")
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        if sha256(temporary) != expected_sha256:
            raise ValueError(f"Downloaded artifact has the wrong SHA-256: {dest.name}")
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)
    return dest


def require_entries(archive: zipfile.ZipFile, names: list[str]) -> None:
    missing = set(names) - set(archive.namelist())
    if missing:
        raise ValueError(f"{archive.filename} is missing required files: {', '.join(sorted(missing))}")


def prepare_shared_aar(source: Path, destination: Path) -> None:
    """Preserve Java/JNI/resources while removing every duplicate base runtime."""
    temporary = destination.with_suffix(destination.suffix + ".part")
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        with zipfile.ZipFile(source) as original:
            require_entries(original, ["classes.jar"] + [
                f"jni/{abi}/{library}"
                for abi in ANDROID_ABIS
                for library in ("libonnxruntime.so", "libonnxruntime4j_jni.so")
            ])
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as prepared:
                for entry in sorted(original.infolist(), key=lambda item: item.filename):
                    if entry.filename.startswith("jni/") and entry.filename.endswith("/libonnxruntime.so"):
                        continue
                    prepared.writestr(entry, original.read(entry.filename))
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def verify_sherpa_runtime(path: Path) -> None:
    if not path.exists():
        raise FileNotFoundError(f"Missing {path}; run scripts/fetch_models.py --runtime-only first.")
    with zipfile.ZipFile(path) as archive:
        for abi in ANDROID_ABIS:
            native_path = f"jni/{abi}/libonnxruntime.so"
            require_entries(archive, [native_path])
            if b"1.27.1\x00" not in archive.read(native_path):
                raise ValueError(f"Expected sherpa's pinned ORT 1.27.1 runtime in {native_path}")


def prepare_translation_runtime(maven_base_url: str = MAVEN_CENTRAL) -> None:
    verify_sherpa_runtime(LIBS / SHERPA_NAME)
    base = maven_base_url.rstrip("/") + "/com/microsoft/onnxruntime/"
    ort = verified_download(
        base + f"onnxruntime-android/{ORT_VERSION}/{ORT_NAME}", CACHE / ORT_NAME, ORT_SHA256,
    )
    extensions = verified_download(
        base + f"onnxruntime-extensions-android/{EXT_VERSION}/{EXT_NAME}", CACHE / EXT_NAME, EXT_SHA256,
    )
    LIBS.mkdir(parents=True, exist_ok=True)
    prepare_shared_aar(ort, LIBS / f"onnxruntime-android-{ORT_VERSION}-shared.aar")
    with zipfile.ZipFile(extensions) as archive:
        require_entries(archive, ["classes.jar"] + [
            f"jni/{abi}/{library}"
            for abi in ANDROID_ABIS
            for library in ("libortextensions.so", "libonnxruntime_extensions4j_jni.so")
        ])
    temporary = (LIBS / EXT_NAME).with_suffix(".aar.part")
    try:
        shutil.copyfile(extensions, temporary)
        temporary.replace(LIBS / EXT_NAME)
    finally:
        temporary.unlink(missing_ok=True)
    print("  ready   ORT Java 1.27.0 + extensions 0.13.0; sherpa supplies ORT 1.27.1")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-base-url", default=MAVEN_CENTRAL,
                        help="Maven Central or a mirror; pinned SHA-256 verification always applies")
    args = parser.parse_args()
    prepare_translation_runtime(args.maven_base_url)


if __name__ == "__main__":
    main()

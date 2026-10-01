import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.prepare_translation_runtime import ANDROID_ABIS, prepare_shared_aar, verified_download


class TranslationRuntimePreparationTest(unittest.TestCase):
    def test_shared_aar_keeps_jni_classes_and_license_and_removes_all_base_runtimes(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "original.aar"
            destination = Path(folder) / "shared.aar"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("classes.jar", b"java-api")
                archive.writestr("META-INF/LICENSE", b"license")
                for abi in (*ANDROID_ABIS, "x86"):
                    archive.writestr(f"jni/{abi}/libonnxruntime.so", b"duplicate-runtime")
                    archive.writestr(f"jni/{abi}/libonnxruntime4j_jni.so", b"java-binding")
            prepare_shared_aar(source, destination)
            first = destination.read_bytes()
            prepare_shared_aar(source, destination)
            self.assertEqual(first, destination.read_bytes())
            with zipfile.ZipFile(destination) as archive:
                self.assertEqual(archive.read("classes.jar"), b"java-api")
                self.assertEqual(archive.read("META-INF/LICENSE"), b"license")
                self.assertFalse(any(name.endswith("/libonnxruntime.so") for name in archive.namelist()))
                for abi in ANDROID_ABIS:
                    self.assertEqual(archive.read(f"jni/{abi}/libonnxruntime4j_jni.so"), b"java-binding")

    def test_missing_abi_preserves_previous_good_prepared_file(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "missing-abis.aar"
            destination = Path(folder) / "shared.aar"
            destination.write_bytes(b"previous-good-file")
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("classes.jar", b"java-api")
            with self.assertRaisesRegex(ValueError, "missing required files"):
                prepare_shared_aar(source, destination)
            self.assertEqual(destination.read_bytes(), b"previous-good-file")
            self.assertFalse(destination.with_suffix(".aar.part").exists())

    def test_corrupt_cached_dependency_fails_without_using_network(self):
        with tempfile.TemporaryDirectory() as folder:
            cached = Path(folder) / "runtime.aar"
            cached.write_bytes(b"corrupted")
            with self.assertRaisesRegex(ValueError, "wrong SHA-256"):
                verified_download("https://invalid.example/artifact", cached, "0" * 64)

    def test_download_verifies_checksum_and_installs_atomically(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "remote.aar"
            source.write_bytes(b"artifact")
            dest = Path(folder) / "cache" / "runtime.aar"
            expected = hashlib.sha256(b"artifact").hexdigest()
            self.assertEqual(verified_download(source.as_uri(), dest, expected), dest)
            self.assertEqual(dest.read_bytes(), b"artifact")
            self.assertFalse(dest.with_suffix(".aar.part").exists())

    def test_wrong_download_checksum_keeps_artifact_uninstalled(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "remote.aar"
            source.write_bytes(b"tampered")
            dest = Path(folder) / "cache" / "runtime.aar"
            with self.assertRaisesRegex(ValueError, "wrong SHA-256"):
                verified_download(source.as_uri(), dest, "0" * 64)
            self.assertFalse(dest.exists())
            self.assertFalse(dest.with_suffix(".aar.part").exists())


if __name__ == "__main__":
    unittest.main()

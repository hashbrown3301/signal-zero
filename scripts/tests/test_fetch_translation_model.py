import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

from scripts import fetch_translation_model as fetch


class Response(io.BytesIO):
    def __init__(self, data, status=200, headers=None):
        super().__init__(data)
        self.status = status
        self.headers = headers or {"Content-Length": str(len(data))}


class TranslationModelFetchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / "model"
        self.output.mkdir()
        self.data = b"small verified model bytes"
        self.artifact = self.artifact_for("encoder_model_quantized.onnx", self.data)
        self.report = lambda message: None

    @staticmethod
    def artifact_for(name, data):
        return fetch.Artifact(name, "onnx/" + name, len(data), hashlib.sha256(data).hexdigest())

    def download(self, response, artifact=None):
        opener = Mock(return_value=response)
        destination = fetch.fetch_file(artifact or self.artifact, self.output, opener=opener, report=self.report)
        return destination, opener

    def test_existing_verified_file_is_reused_without_network(self):
        destination = self.output / self.artifact.name
        destination.write_bytes(self.data)
        opener = Mock(side_effect=AssertionError("network must not be used"))
        self.assertEqual(destination, fetch.fetch_file(self.artifact, self.output, opener=opener, report=self.report))
        opener.assert_not_called()

    def test_download_is_pinned_and_uses_no_authorization_header(self):
        destination, opener = self.download(Response(self.data))
        request = opener.call_args.args[0]
        self.assertIn("/resolve/" + fetch.REVISION + "/", request.full_url)
        self.assertIsNone(request.get_header("Authorization"))
        self.assertEqual(self.data, destination.read_bytes())
        self.assertFalse(destination.with_name(destination.name + ".part").exists())

    def test_resume_requests_remaining_bytes_and_appends(self):
        split = 7
        partial = self.output / (self.artifact.name + ".part")
        partial.write_bytes(self.data[:split])
        response = Response(self.data[split:], 206, {
            "Content-Range": f"bytes {split}-{len(self.data) - 1}/{len(self.data)}",
            "Content-Length": str(len(self.data) - split),
        })
        destination, opener = self.download(response)
        self.assertEqual(f"bytes={split}-", opener.call_args.args[0].get_header("Range"))
        self.assertEqual(self.data, destination.read_bytes())

    def test_ignored_range_restarts_instead_of_appending(self):
        (self.output / (self.artifact.name + ".part")).write_bytes(b"stale")
        destination, _ = self.download(Response(self.data))
        self.assertEqual(self.data, destination.read_bytes())

    def test_wrong_range_is_rejected_without_overwriting_partial(self):
        partial = self.output / (self.artifact.name + ".part")
        partial.write_bytes(self.data[:7])
        with self.assertRaisesRegex(fetch.IntegrityError, "resume response"):
            self.download(Response(self.data[7:], 206, {"Content-Range": f"bytes 0-{len(self.data) - 1}/{len(self.data)}"}))
        self.assertEqual(self.data[:7], partial.read_bytes())
        self.assertFalse((self.output / self.artifact.name).exists())

    def test_corrupt_completed_download_is_discarded_and_existing_file_preserved(self):
        destination = self.output / self.artifact.name
        destination.write_bytes(b"previous file")
        with self.assertRaisesRegex(fetch.IntegrityError, "SHA-256"):
            self.download(Response(b"x" * len(self.data)))
        self.assertEqual(b"previous file", destination.read_bytes())
        self.assertFalse((self.output / (self.artifact.name + ".part")).exists())

    def test_truncated_download_retains_partial_for_resume(self):
        with self.assertRaisesRegex(fetch.IntegrityError, "rerun to resume"):
            self.download(Response(self.data[:7], headers={"Content-Length": str(len(self.data))}))
        self.assertEqual(self.data[:7], (self.output / (self.artifact.name + ".part")).read_bytes())

    def test_completed_valid_partial_is_published_without_network(self):
        (self.output / (self.artifact.name + ".part")).write_bytes(self.data)
        opener = Mock(side_effect=AssertionError("network must not be used"))
        destination = fetch.fetch_file(self.artifact, self.output, opener=opener, report=self.report)
        self.assertEqual(self.data, destination.read_bytes())
        opener.assert_not_called()

    def test_bad_complete_partial_starts_fresh(self):
        (self.output / (self.artifact.name + ".part")).write_bytes(b"x" * len(self.data))
        destination, opener = self.download(Response(self.data))
        self.assertIsNone(opener.call_args.args[0].get_header("Range"))
        self.assertEqual(self.data, destination.read_bytes())

    def test_verifier_rejects_incorrect_size_and_same_size_bad_hash(self):
        destination = self.output / self.artifact.name
        for data, reason in [(b"short", "bytes"), (b"x" * len(self.data), "SHA-256")]:
            with self.subTest(reason=reason):
                destination.write_bytes(data)
                with self.assertRaisesRegex(fetch.IntegrityError, reason):
                    fetch.verify_file(destination, self.artifact)

    def test_zip_has_three_root_files_and_license_manifest(self):
        artifacts = []
        contents = {}
        for index, pinned in enumerate(fetch.ARTIFACTS):
            data = f"fixture {index}".encode()
            artifact = self.artifact_for(pinned.name, data)
            artifacts.append(artifact)
            contents[artifact.name] = data
            (self.output / artifact.name).write_bytes(data)
        archive_path = self.output.parent / "translation.zip"
        fetch.build_zip(self.output, archive_path, tuple(artifacts))
        with zipfile.ZipFile(archive_path) as archive:
            self.assertEqual(set(contents) | {"model.json"}, set(archive.namelist()))
            for name, data in contents.items():
                self.assertEqual(data, archive.read(name))
            manifest = json.loads(archive.read("model.json"))
            self.assertEqual(1, manifest["format"])
            self.assertEqual(fetch.MODEL_ID, manifest["id"])
            self.assertEqual(fetch.REVISION, manifest["revision"])
            self.assertEqual("CC-BY-NC-4.0", manifest["license"])
            self.assertIn("production", manifest["intended_use"])
            self.assertEqual([
                {"name": item.name, "size": item.size, "sha256": item.sha256} for item in artifacts
            ], manifest["files"])

    def test_corrupt_file_cannot_replace_previous_zip_or_manifest(self):
        archive_path = self.output.parent / "translation.zip"
        archive_path.write_bytes(b"previous zip")
        (self.output / "model.json").write_text("previous manifest")
        (self.output / self.artifact.name).write_bytes(b"bad")
        with self.assertRaises(fetch.IntegrityError):
            fetch.build_zip(self.output, archive_path, (self.artifact,))
        self.assertEqual(b"previous zip", archive_path.read_bytes())
        self.assertEqual("previous manifest", (self.output / "model.json").read_text())

    def test_verify_only_never_accesses_network(self):
        (self.output / self.artifact.name).write_bytes(self.data)
        with patch.object(fetch, "ARTIFACTS", (self.artifact,)), \
                patch.object(fetch.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")), \
                patch("builtins.print"):
            self.assertEqual(0, fetch.main(["--output", str(self.output), "--verify-only"]))
        self.assertTrue((self.output / "model.json").is_file())


if __name__ == "__main__":
    unittest.main()

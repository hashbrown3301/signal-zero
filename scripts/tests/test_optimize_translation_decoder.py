import base64
import hashlib
import importlib.util
import random
import tempfile
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location(
    "optimize_translation_decoder", Path(__file__).parents[1] / "optimize_translation_decoder.py",
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CompactDecoderPatchTests(unittest.TestCase):
    def test_sparse_graph_changes_keep_large_weights_out_of_patch(self):
        rng = random.Random(3301)
        weights_a, weights_b = rng.randbytes(1_000_000), rng.randbytes(1_000_000)
        header = b"graph-length-old" + rng.randbytes(200)
        node_a, node_b = b"old-first-projection" * 20, b"old-second-projection" * 20
        original = header + node_a + weights_a + node_b + weights_b
        derived = header[:4] + b"new-length" + header[12:] + b"new-first-integer-projection" * 22 + weights_a + b"new-second-integer-projection" * 22 + weights_b
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "original", Path(directory) / "derived"
            source.write_bytes(original)
            target.write_bytes(derived)
            patch = module.compact_patch(source, target)
        position = 0
        reconstructed = bytearray()
        inserted = 0
        for edit in patch["edits"]:
            self.assertGreaterEqual(edit["offset"], position)
            reconstructed.extend(original[position:edit["offset"]])
            block = base64.b64decode(edit["insert_base64"])
            inserted += len(block)
            reconstructed.extend(block)
            position = edit["offset"] + edit["remove"]
        reconstructed.extend(original[position:])
        self.assertEqual(derived, reconstructed)
        self.assertEqual(hashlib.sha256(original).hexdigest(), patch["source_sha256"])
        self.assertEqual(hashlib.sha256(derived).hexdigest(), patch["target_sha256"])
        self.assertLess(inserted, 10_000)

    def test_unchanged_model_has_no_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "original", Path(directory) / "derived"
            source.write_bytes(b"identical model" * 10_000)
            target.write_bytes(source.read_bytes())
            patch = module.compact_patch(source, target)
        self.assertEqual([], patch["edits"])
        self.assertEqual(patch["source_sha256"], patch["target_sha256"])

    def test_changed_weight_blob_is_rejected_instead_of_embedded_in_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "original", Path(directory) / "derived"
            source.write_bytes(b"A" * 100_000)
            target.write_bytes(b"B" * 100_000)
            with self.assertRaisesRegex(ValueError, "too much for a compact patch"):
                module.compact_patch(source, target)


if __name__ == "__main__":
    unittest.main()

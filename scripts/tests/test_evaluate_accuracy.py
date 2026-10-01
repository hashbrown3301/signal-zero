import json
import tempfile
import unittest
from pathlib import Path

from scripts.evaluate_accuracy import LANGUAGES, evaluate, load_rows, normalize, recognition_scores, translation_score


class AccuracyMetricsTest(unittest.TestCase):
    def row(self, reference="hello", hypothesis="hello", **extra):
        return dict(id="sample", task="recognition", source="en", reference=reference,
                    hypothesis=hypothesis, model="test-model@revision", **extra)

    def test_corpus_rates_are_weighted_by_reference_length(self):
        scores = recognition_scores([self.row("a", "b"), self.row("aaaaaaaaa", "aaaaaaaaa")])
        self.assertAlmostEqual(0.1, scores["cer"])
        self.assertEqual(0.5, scores["wer"])

    def test_numeric_errors_and_indic_marks_are_preserved(self):
        for reference, hypothesis in [("1.5", "15"), ("-5", "5"), ("क़ल", "कल"), ("हूँ", "हूं")]:
            with self.subTest(reference=reference):
                self.assertGreater(recognition_scores([self.row(reference, hypothesis)])["cer"], 0)
        self.assertEqual("hello world", normalize("Hello, world!"))

    def test_empty_prediction_is_scored_as_deletion(self):
        self.assertEqual(1, recognition_scores([self.row("hello", "")])["cer"])

    def test_duplicate_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "input.jsonl"
            path.write_text((json.dumps(self.row()) + "\n") * 2)
            with self.assertRaisesRegex(ValueError, "duplicate"):
                load_rows(path)

    def test_missing_languages_are_reported_per_model(self):
        report = evaluate([self.row()])
        self.assertEqual(10, report["coverage"][0]["required"])
        self.assertEqual(1, report["coverage"][0]["covered"])
        self.assertEqual(9, len(report["coverage"][0]["missing"]))

    def test_language_specific_models_can_share_one_system_coverage_gate(self):
        rows = [dict(id="clip", task="recognition", source=lang, reference="a sample", hypothesis="a sample",
                     model=f"recognizer-{lang}@revision", system="app-build@revision") for lang in LANGUAGES]
        report = evaluate(rows)
        self.assertEqual(1, len(report["coverage"]))
        self.assertEqual(10, report["coverage"][0]["covered"])
        self.assertEqual([], report["coverage"][0]["missing"])
        self.assertEqual(10, len(report["results"]))

    def test_translation_uses_chrf_and_all_ninety_directed_pairs(self):
        rows = []
        for source in LANGUAGES:
            for target in LANGUAGES:
                if source != target:
                    rows.append(dict(id="fixture", task="translation", source=source, target=target,
                                     reference="a scoring test sentence", hypothesis="a scoring test sentence",
                                     model="scoring-fixture@revision"))
        report = evaluate(rows)
        self.assertEqual(90, report["coverage"][0]["covered"])
        self.assertEqual([], report["coverage"][0]["missing"])
        self.assertTrue(all(result["chrf"] == 100 for result in report["results"]))
        self.assertLess(translation_score([self.row("the correct meaning", "unrelated words")])["chrf"], 100)


if __name__ == "__main__":
    unittest.main()

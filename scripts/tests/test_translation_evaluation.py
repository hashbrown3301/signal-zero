import copy
import hashlib
import io
import json
import tarfile
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from scripts.prepare_translation_evaluation import (
    ARCHIVE_SHA256, LANGUAGES, SPM_SHA256, atomic_json, build_cases, json_digest,
    load_corpus, read_parallel, validate_corpus,
)
from scripts.summarize_translation_evaluation import summarize


def fixture_corpus():
    # Synthetic fixtures test the harness/scorer; they are never translation gold.
    lines = {iso: [f"{iso} fixture sentence {index}" for index in range(8)] for iso in LANGUAGES}
    metadata = [{"URL": f"https://example.test/{index}", "topic": "fixture"} for index in range(8)]
    cases = build_cases(lines, metadata, "fixture-seed", 2, lambda text: 5)
    return {
        "schema_version": 1, "split": "devtest", "language_tags": LANGUAGES,
        "examples_per_pair": 2, "selection": "test fixture",
        "provenance": {"archive_sha256": ARCHIVE_SHA256},
        "tokenizer": {"sha256": SPM_SHA256}, "cases_sha256": json_digest(cases), "cases": cases,
    }


def fixture_results(corpus):
    configuration = dict(model_revision="fixture", derived_decoder_sha256="fixture", model_files=[],
                         onnx_runtime="fixture", custom_ops_sha256="fixture", source_tree_sha256="fixture",
                         app_commit="fixture", intra_op_threads=2, source_token_limit=256, generated_token_limit=256)
    text = json.dumps(configuration)
    return {
        "schema_version": 1, "corpus_sha256": "fixture-corpus", "complete": True,
        "configuration": configuration, "configuration_json": text,
        "configuration_sha256": hashlib.sha256(text.encode()).hexdigest(),
        "results": [dict(case, hypothesis=case["reference"], outcome="success", translation_ms=1.0)
                    for case in corpus["cases"]],
    }


class TranslationEvaluationTest(unittest.TestCase):
    def test_selection_is_deterministic_aligned_and_covers_all_ninety_pairs(self):
        corpus = fixture_corpus()
        validate_corpus(corpus)
        self.assertEqual(corpus, fixture_corpus())
        self.assertEqual(180, len(corpus["cases"]))
        pairs = Counter((case["source"], case["target"]) for case in corpus["cases"])
        self.assertEqual(90, len(pairs))
        self.assertEqual({2}, set(pairs.values()))
        for case in corpus["cases"]:
            index = case["sentence_id"] - 1
            self.assertEqual(f"{case['source']} fixture sentence {index}", case["source_text"])
            self.assertEqual(f"{case['target']} fixture sentence {index}", case["reference"])

    def test_guard_rejections_remain_selected_cases(self):
        lines = {iso: ["fixture too long", "other fixture"] for iso in LANGUAGES}
        metadata = [{"URL": "https://example.test/1"}, {"URL": "https://example.test/2"}]
        cases = build_cases(lines, metadata, "seed", 2, lambda text: 300)
        self.assertEqual(180, len(cases))
        self.assertTrue(all(case["source_tokens"] == 302 and not case["source_supported"] for case in cases))

    def test_wrong_case_digest_and_incomplete_pair_corpus_fail(self):
        corpus = fixture_corpus()
        corpus["cases"][0]["reference"] = "changed"
        with self.assertRaisesRegex(ValueError, "digest"):
            validate_corpus(corpus)
        corpus = fixture_corpus()
        corpus["cases"].pop()
        corpus["cases_sha256"] = json_digest(corpus["cases"])
        with self.assertRaisesRegex(ValueError, "90 directions"):
            validate_corpus(corpus)

    def test_file_checksum_rejects_unnoticed_corpus_edit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "corpus.json"
            atomic_json(path, fixture_corpus())
            path.with_suffix(".json.sha256").write_text("0" * 64)
            with self.assertRaisesRegex(ValueError, "checksum"):
                load_corpus(path)

    def test_archive_traversal_is_rejected_without_extracting(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsafe.tar.gz"
            with tarfile.open(path, "w:gz") as archive:
                info = tarfile.TarInfo("../escaped"); info.size = 1
                archive.addfile(info, io.BytesIO(b"x"))
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                read_parallel(path, "devtest")
            self.assertFalse((Path(directory).parent / "escaped").exists())

    def test_complete_reference_fixture_scores_chrf_plus_plus_with_sample_limits(self):
        corpus = fixture_corpus()
        summary = summarize(corpus, "fixture-corpus", fixture_results(corpus))
        self.assertAlmostEqual(100, summary["combined"]["chrf_plus_plus"])
        self.assertEqual(90, summary["ordered_pairs"])
        self.assertEqual(180, summary["completed_cases"])
        self.assertTrue(all(pair["examples"] == 2 for pair in summary["pairs"]))
        self.assertIn("nw:2", summary["combined"]["signature"])
        self.assertTrue(any("not percent accuracy" in limit for limit in summary["limits"]))

    def test_failed_output_is_retained_as_deletion_in_scores_and_failure_rate(self):
        corpus = fixture_corpus(); report = fixture_results(corpus)
        report["results"][0].update(hypothesis="", outcome="runtime_error", error="fixture failure")
        summary = summarize(corpus, "fixture-corpus", report)
        self.assertAlmostEqual(1 / 180, summary["failure_rate"])
        self.assertEqual(1, summary["outcomes"]["runtime_error"])
        self.assertLess(summary["combined"]["chrf_plus_plus"], 100)

    def test_missing_duplicate_or_foreign_result_cannot_pass_completeness(self):
        corpus = fixture_corpus()
        for mutation in (lambda rows: rows.pop(), lambda rows: rows.append(copy.deepcopy(rows[0])),
                         lambda rows: rows[0].update(id="foreign-case")):
            report = fixture_results(corpus); mutation(report["results"])
            with self.assertRaises(ValueError): summarize(corpus, "fixture-corpus", report)

    def test_changed_reference_or_runtime_configuration_cannot_be_pooled(self):
        corpus = fixture_corpus()
        report = fixture_results(corpus); report["results"][0]["reference"] = "other reference"
        with self.assertRaisesRegex(ValueError, "immutable corpus"):
            summarize(corpus, "fixture-corpus", report)
        report = fixture_results(corpus); report["configuration"]["intra_op_threads"] = 8
        with self.assertRaisesRegex(ValueError, "configuration digest"):
            summarize(corpus, "fixture-corpus", report)


if __name__ == "__main__":
    unittest.main()

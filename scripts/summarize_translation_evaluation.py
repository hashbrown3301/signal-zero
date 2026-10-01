"""Score a complete fixed corpus against actual runtime outputs using chrF++.

This is lexical reference similarity, not percent accuracy or human assessment.
"""

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

try:
    from scripts.prepare_translation_evaluation import atomic_json, load_corpus
except ModuleNotFoundError:
    from prepare_translation_evaluation import atomic_json, load_corpus


def summarize(corpus: dict, corpus_sha256: str, report: dict) -> dict:
    if report.get("schema_version") != 1 or report.get("corpus_sha256") != corpus_sha256:
        raise ValueError("Results do not identify this exact corpus file")
    configuration_json = report.get("configuration_json")
    if not isinstance(configuration_json, str) or not configuration_json.strip():
        raise ValueError("Missing immutable runtime configuration")
    configuration = json.loads(configuration_json)
    if configuration != report.get("configuration") or hashlib.sha256(configuration_json.encode()).hexdigest() != report.get("configuration_sha256"):
        raise ValueError("Runtime configuration digest mismatch")
    required = ("model_revision", "derived_decoder_sha256", "model_files", "onnx_runtime",
                "custom_ops_sha256", "source_tree_sha256", "app_commit", "intra_op_threads",
                "source_token_limit", "generated_token_limit")
    if any(key not in configuration for key in required):
        raise ValueError("Runtime provenance is incomplete")
    expected = {case["id"]: case for case in corpus["cases"]}
    results, seen = report.get("results"), set()
    if not isinstance(results, list):
        raise ValueError("Missing results")
    groups = defaultdict(list)
    outcomes = Counter()
    for row in results:
        key = row.get("id")
        if key not in expected or key in seen:
            raise ValueError("Unexpected or duplicate result ID")
        seen.add(key)
        case = expected[key]
        for field in ("source", "target", "source_text", "reference", "source_tokens"):
            if row.get(field) != case[field]:
                raise ValueError(f"Result {key} differs from its immutable corpus {field}")
        outcome = row.get("outcome")
        if outcome not in ("success", "source_limit", "generation_limit", "runtime_error", "timeout"):
            raise ValueError("Missing/unsupported structured result outcome")
        if not isinstance(row.get("hypothesis"), str):
            raise ValueError("Missing raw hypothesis (empty string is required for failures)")
        if outcome == "success" and not row["hypothesis"].strip():
            raise ValueError("Empty output cannot count as success")
        if outcome != "success" and (row["hypothesis"] or not row.get("error")):
            raise ValueError("Failed result requires an empty prediction and explicit error")
        elapsed = row.get("translation_ms")
        if not isinstance(elapsed, (int, float)) or isinstance(elapsed, bool) or not 0 <= elapsed < float("inf"):
            raise ValueError("Invalid elapsed time")
        outcomes[outcome] += 1
        groups[(row["source"], row["target"])].append(row)
    if seen != set(expected) or report.get("complete") is not True:
        raise ValueError(f"Incomplete evaluation: {len(seen)}/{len(expected)} expected cases; missing {sorted(set(expected) - seen)}")
    from sacrebleu.metrics import CHRF
    metric = CHRF(word_order=2)

    def score(rows):
        value = metric.corpus_score([row["hypothesis"] for row in rows], [[row["reference"] for row in rows]])
        return {"chrf_plus_plus": value.score, "signature": str(metric.get_signature())}

    pairs = []
    for (source, target), rows in sorted(groups.items()):
        pair = {"source": source, "target": target, "examples": len(rows),
                "successes": sum(row["outcome"] == "success" for row in rows),
                "outcomes": dict(Counter(row["outcome"] for row in rows)), **score(rows)}
        pairs.append(pair)
    return {
        "schema_version": 1, "metric": "sacreBLEU chrF++ (word_order=2)",
        "corpus_sha256": corpus_sha256, "cases_sha256": corpus["cases_sha256"],
        "configuration_sha256": report["configuration_sha256"], "configuration": configuration,
        "provenance": corpus["provenance"], "selection": corpus["selection"],
        "expected_cases": len(expected), "completed_cases": len(results), "ordered_pairs": len(pairs),
        "distinct_sentence_ids": len({case["sentence_id"] for case in corpus["cases"]}),
        "outcomes": dict(outcomes), "failure_rate": 1 - outcomes["success"] / len(results),
        "combined": score(results), "pairs": pairs,
        "limits": [
            "chrF++ is reference similarity, not percent accuracy or human adequacy/fluency.",
            f"Only {corpus['examples_per_pair']} examples per direction: per-pair scores are unstable and no useful tail/quality confidence claim is supported.",
            "Combined score mixes different languages and topics; review per-pair raw outputs and critical details separately.",
            "Every selected failure remains an empty prediction in scores; no hard examples are silently replaced.",
            "FLORES article text is not representative conversational speech; held out from this upgrade, with upstream contamination unknown.",
            "Cloud translation only; no phone, microphone, synthesis, playback, Firebase or human review is measured.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("results", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        corpus, digest = load_corpus(args.corpus)
        summary = summarize(corpus, digest, json.loads(args.results.read_text(encoding="utf-8")))
        atomic_json(args.out, summary)
    except (ValueError, KeyError, TypeError, OSError, ImportError) as error:
        parser.error(str(error))
    print(f"{summary['completed_cases']} cases / {summary['ordered_pairs']} pairs; chrF++ {summary['combined']['chrf_plus_plus']:.2f}")
    print("Reference similarity only; two examples per pair do not establish general accuracy.")


if __name__ == "__main__":
    main()

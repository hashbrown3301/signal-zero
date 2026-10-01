"""Score reference/prediction JSONL without conflating recognition and translation.

Each row: id, task (recognition|translation), source (ISO), reference, hypothesis,
model (exact model/revision). Translation rows also require target (ISO). An
optional system label groups language-specific model revisions in one app build.

    python scripts/evaluate_accuracy.py results.jsonl --out report.json
    python scripts/evaluate_accuracy.py results.jsonl --out report.json --require-full-coverage

Recognition reports corpus-weighted CER/WER and strict CER/WER. Translation uses
standard sacreBLEU chrF on original text. Coverage is reported per system: ten
recognizers or 90 directed pairs. Scores do not substitute for human review.
"""

import argparse
import json
import unicodedata
from collections import defaultdict
from pathlib import Path

LANGUAGES = ("hi", "en", "mr", "gu", "bn", "ta", "te", "kn", "ml", "or")


def normalize(text: str, ignore_punctuation: bool = True) -> str:
    text = unicodedata.normalize("NFKC", text).casefold()
    if ignore_punctuation:
        # A dash immediately before a digit can carry a negative value or range.
        # Canonicalize Unicode numeric dash variants before punctuation removal:
        # en-dash –5 must never become the same reference as positive 5.
        numeric_dashes = "\u2010\u2011\u2012\u2013\u2014\u2212"
        text = "".join("-" if char in numeric_dashes and index + 1 < len(text)
                       and text[index + 1].isdigit() else char for index, char in enumerate(text))
        chars = []
        for index, char in enumerate(text):
            # Keep decimal/group separators and a numeric sign: 1.5 must not score
            # as the same utterance as 15, nor -5 as 5. Never strip Indic marks.
            numeric = (
                char in ".,:" and index > 0 and index + 1 < len(text)
                and text[index - 1].isdigit() and text[index + 1].isdigit()
            ) or (char == "-" and index + 1 < len(text) and text[index + 1].isdigit())
            chars.append(" " if unicodedata.category(char).startswith("P") and not numeric else char)
        text = "".join(chars)
    return " ".join(text.split())


def edit_distance(reference, hypothesis) -> int:
    # O(min(m,n)) memory; transcripts are bounded when loading the JSONL below.
    if len(hypothesis) > len(reference):
        reference, hypothesis = hypothesis, reference
    row = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        previous, row[0] = row[0], i
        for j, right in enumerate(hypothesis, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (left != right))
    return row[-1]


def recognition_scores(rows: list[dict], ignore_punctuation: bool = True) -> dict:
    char_errors = word_errors = characters = words = 0
    for row in rows:
        reference = normalize(row["reference"], ignore_punctuation)
        hypothesis = normalize(row["hypothesis"], ignore_punctuation)
        characters += len(reference)
        words += len(reference.split())
        char_errors += edit_distance(reference, hypothesis)
        word_errors += edit_distance(reference.split(), hypothesis.split())
    return {
        "cer": char_errors / max(characters, 1), "wer": word_errors / max(words, 1),
        "character_errors": char_errors, "reference_characters": characters,
        "word_errors": word_errors, "reference_words": words,
    }


def translation_score(rows: list[dict]) -> dict:
    try:
        from sacrebleu.metrics import CHRF
    except ImportError as error:
        raise ValueError("Translation scoring needs: pip install -r scripts/requirements-validation.txt") from error
    metric = CHRF()
    score = metric.corpus_score([r["hypothesis"] for r in rows], [[r["reference"] for r in rows]])
    return {"chrf": score.score, "signature": str(metric.get_signature())}


def load_rows(path: Path) -> list[dict]:
    rows, seen = [], set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        for field in ("id", "task", "source", "reference", "hypothesis", "model"):
            if not isinstance(row.get(field), str) or (field != "hypothesis" and not row[field].strip()):
                raise ValueError(f"Line {number}: missing/invalid {field}")
        if row["task"] not in ("recognition", "translation") or row["source"] not in LANGUAGES:
            raise ValueError(f"Line {number}: unsupported task or source language")
        if row["task"] == "translation" and row.get("target") not in LANGUAGES:
            raise ValueError(f"Line {number}: unsupported target language")
        if row["task"] == "recognition" and row.get("target") is not None:
            raise ValueError(f"Line {number}: recognition examples must not have a target language")
        if "system" in row and (not isinstance(row["system"], str) or not row["system"].strip()):
            raise ValueError(f"Line {number}: invalid system label")
        if max(len(row["reference"]), len(row["hypothesis"])) > 10_000:
            raise ValueError(f"Line {number}: utterance exceeds 10000 characters")
        key = (row.get("system", row["model"]), row["model"], row["task"], row["source"], row.get("target"), row["id"])
        if key in seen:
            raise ValueError(f"Line {number}: duplicate example id for this model/language pair")
        seen.add(key)
        rows.append(row)
    if not rows:
        raise ValueError("No examples supplied")
    return rows


def evaluate(rows: list[dict]) -> dict:
    groups, coverage = defaultdict(list), defaultdict(set)
    for row in rows:
        task, source, target, model = row["task"], row["source"], row.get("target"), row["model"]
        system = row.get("system", model)
        groups[(task, source, target, model, system)].append(row)
        coverage[(task, system)].add(source if task == "recognition" else f"{source}->{target}")
    results = []
    for (task, source, target, model, system), examples in sorted(groups.items()):
        item = {"task": task, "source": source, "target": target, "model": model, "system": system, "examples": len(examples)}
        if task == "recognition":
            item.update(recognition_scores(examples))
            strict = recognition_scores(examples, ignore_punctuation=False)
            item.update(strict_cer=strict["cer"], strict_wer=strict["wer"])
        else:
            item.update(translation_score(examples))
        results.append(item)
    completeness = []
    for (task, system), present in sorted(coverage.items()):
        expected = set(LANGUAGES) if task == "recognition" else {
            f"{source}->{target}" for source in LANGUAGES for target in LANGUAGES if source != target
        }
        completeness.append({
            "task": task, "system": system, "covered": len(present & expected),
            "required": len(expected), "missing": sorted(expected - present),
        })
    return {
        "normalization": "Recognition: NFKC/casefold/whitespace; punctuation optional, numeric dash signs/separators and Indic marks preserved. Strict rates are punctuation-sensitive, not raw exact-string rates. CER includes spaces. Translation: standard chrF on original strings.",
        "coverage": completeness, "results": results,
        "limits": "Reference-based scores only. Review names, numbers, negation and intended meaning with native speakers; include real noisy speech, not just synthesized audio.",
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--require-full-coverage", action="store_true")
    args = ap.parse_args()
    try:
        report = evaluate(load_rows(args.input))
    except (ValueError, KeyError, TypeError) as error:
        ap.error(str(error))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for entry in report["coverage"]:
        print(f"{entry['task']} ({entry['system']}): {entry['covered']}/{entry['required']} covered")
    if args.require_full_coverage and any(entry["missing"] for entry in report["coverage"]):
        raise SystemExit("Accuracy report written, but required language coverage is incomplete")


if __name__ == "__main__":
    main()

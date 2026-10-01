"""Prepare a fixed human-reference FLORES-200 slice; does not run translation.

The source archive is the original release linked by facebookresearch/flores.
References are CC BY-SA 4.0; keep corpus provenance when redistributing them.
"""

import argparse
import csv
import hashlib
import io
import json
import tarfile
import urllib.request
from collections import Counter
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent.parent
URL = "https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz"
ARCHIVE_BYTES = 25_585_843
ARCHIVE_SHA256 = "b8b0b76783024b85797e5cc75064eb83fc5288b41e9654dabc7be6ae944011f6"
SPM_SHA256 = "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a"
LANGUAGES = dict(zip(
    ("hi", "en", "mr", "gu", "bn", "ta", "te", "kn", "ml", "or"),
    ("hin_Deva", "eng_Latn", "mar_Deva", "guj_Gujr", "ben_Beng", "tam_Taml",
     "tel_Telu", "kan_Knda", "mal_Mlym", "ory_Orya"),
))
SOURCE_LIMIT = 256
SEED = "signal-zero-flores200-devtest-v1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def json_digest(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def atomic_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def fetch_archive(path: Path) -> None:
    if path.exists():
        if path.stat().st_size != ARCHIVE_BYTES or sha256(path) != ARCHIVE_SHA256:
            raise ValueError("Cached FLORES archive does not match the pinned original release")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    try:
        with urllib.request.urlopen(URL, timeout=60) as source, temporary.open("wb") as output:
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > ARCHIVE_BYTES:
                    raise ValueError("FLORES download exceeds its pinned size")
                output.write(block)
        if total != ARCHIVE_BYTES or sha256(temporary) != ARCHIVE_SHA256:
            raise ValueError("FLORES download checksum/size mismatch")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def read_parallel(archive: Path, split: str) -> tuple[dict, list, list]:
    """Read only needed regular files; never extract archive paths to disk."""
    if split not in ("dev", "devtest"):
        raise ValueError("Only public dev/devtest splits are supported")
    expected_lines = 997 if split == "dev" else 1012
    wanted = {f"flores200_dataset/{split}/{tag}.{split}" for tag in LANGUAGES.values()}
    metadata_path = f"flores200_dataset/metadata_{split}.tsv"
    wanted.add(metadata_path)
    contents, files = {}, []
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 1024 or sum(m.size for m in members) > 256 * 1024 * 1024:
            raise ValueError("Unexpected FLORES archive expansion")
        seen = set()
        for member in members:
            path = PurePosixPath(member.name)
            name = str(path)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError(f"Unsafe archive member: {member.name}")
            if name in seen:
                raise ValueError(f"Duplicate archive member: {name}")
            seen.add(name)
            if member.size > 8 * 1024 * 1024:
                raise ValueError(f"Oversized archive member: {name}")
            if name in wanted:
                data = source.extractfile(member).read()
                contents[name] = data.decode("utf-8")
                files.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    if set(contents) != wanted:
        raise ValueError("Missing FLORES language/metadata file")
    lines = {iso: contents[f"flores200_dataset/{split}/{tag}.{split}"].splitlines()
             for iso, tag in LANGUAGES.items()}
    if any(len(values) != expected_lines or any(not line.strip() for line in values) for values in lines.values()):
        raise ValueError("FLORES language files have invalid/nonaligned line counts or empty references")
    metadata = list(csv.DictReader(io.StringIO(contents[metadata_path]), delimiter="\t"))
    if len(metadata) != expected_lines or any(not row.get("URL") for row in metadata):
        raise ValueError("FLORES metadata does not align with sentence IDs")
    return lines, metadata, sorted(files, key=lambda file: file["path"])


def build_cases(lines: dict, metadata: list, seed: str, count: int, token_count) -> list:
    if not seed or not 1 <= count <= 20:
        raise ValueError("A nonempty seed and 1–20 examples per pair are required")
    cases = []
    for source in LANGUAGES:
        for target in LANGUAGES:
            if source == target:
                continue
            order = sorted(range(len(metadata)), key=lambda index: hashlib.sha256(
                f"{seed}|{source}|{target}|{index + 1}".encode()).digest())
            for index in order[:count]:
                text = lines[source][index]
                tokens = token_count(text) + 2  # source-language prefix and EOS
                cases.append({
                    "id": f"{source}-{target}:{index + 1}", "source": source, "target": target,
                    "sentence_id": index + 1, "source_text": text, "reference": lines[target][index],
                    "source_tokens": tokens, "source_supported": tokens <= SOURCE_LIMIT,
                    "article_url": metadata[index]["URL"], "topic": metadata[index].get("topic", ""),
                })
    return cases


def validate_corpus(corpus: dict) -> None:
    if corpus.get("schema_version") != 1 or corpus.get("language_tags") != LANGUAGES:
        raise ValueError("Unsupported corpus schema/languages")
    if corpus.get("provenance", {}).get("archive_sha256") != ARCHIVE_SHA256:
        raise ValueError("Corpus references do not identify the pinned FLORES archive")
    if corpus.get("tokenizer", {}).get("sha256") != SPM_SHA256:
        raise ValueError("Corpus tokenizer differs from the pinned NLLB tokenizer")
    count, cases = corpus.get("examples_per_pair"), corpus.get("cases")
    if type(count) is not int or not 1 <= count <= 20 or not isinstance(cases, list):
        raise ValueError("Invalid corpus case count")
    if corpus.get("cases_sha256") != json_digest(cases):
        raise ValueError("Corpus case digest mismatch")
    seen, pairs = set(), Counter()
    for case in cases:
        source, target = case.get("source"), case.get("target")
        if source not in LANGUAGES or target not in LANGUAGES or source == target:
            raise ValueError("Invalid ordered translation pair")
        if case.get("id") in seen or not isinstance(case.get("id"), str):
            raise ValueError("Duplicate/invalid corpus case ID")
        seen.add(case["id"])
        sentence_id = case.get("sentence_id")
        if type(sentence_id) is not int or sentence_id < 1 or case["id"] != f"{source}-{target}:{sentence_id}":
            raise ValueError("Corpus case ID does not identify its aligned sentence and ordered pair")
        if any(not isinstance(case.get(field), str) or not case[field].strip()
               for field in ("source_text", "reference")):
            raise ValueError("Empty/invalid human source or reference")
        if type(case.get("source_tokens")) is not int or case["source_tokens"] < 2:
            raise ValueError("Missing source token count")
        if type(case.get("source_supported")) is not bool or case["source_supported"] != (case["source_tokens"] <= SOURCE_LIMIT):
            raise ValueError("Incorrect corpus source guard")
        pairs[f"{source}->{target}"] += 1
    expected = {f"{source}->{target}": count for source in LANGUAGES for target in LANGUAGES if source != target}
    if dict(pairs) != expected:
        raise ValueError("Corpus must contain the exact expected number of cases in all 90 directions")


def load_corpus(path: Path) -> tuple[dict, str]:
    corpus = json.loads(path.read_text(encoding="utf-8"))
    validate_corpus(corpus)
    digest = sha256(path)
    checksum = path.with_suffix(path.suffix + ".sha256")
    if checksum.exists() and checksum.read_text().split()[0] != digest:
        raise ValueError("Corpus file checksum mismatch")
    return corpus, digest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ROOT / "scripts/downloads/evaluation/flores200_dataset.tar.gz")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/evaluation/corpus.json")
    parser.add_argument("--split", choices=("dev", "devtest"), default="devtest")
    parser.add_argument("--seed", default=SEED)
    parser.add_argument("--examples-per-pair", type=int, default=2)
    parser.add_argument("--sentencepiece-model", type=Path, default=ROOT / "dist/translation/nllb/sentencepiece.bpe.model")
    args = parser.parse_args()
    try:
        import sentencepiece
        if sha256(args.sentencepiece_model) != SPM_SHA256:
            raise ValueError("SentencePiece model checksum mismatch")
        processor = sentencepiece.SentencePieceProcessor(model_file=str(args.sentencepiece_model))
        fetch_archive(args.archive)
        lines, metadata, files = read_parallel(args.archive, args.split)
        cases = build_cases(lines, metadata, args.seed, args.examples_per_pair,
                            lambda text: len(processor.encode(text)))
        corpus = {
            "schema_version": 1, "corpus_id": f"flores200-{args.split}-signal-zero-v1",
            "split": args.split, "seed": args.seed, "examples_per_pair": args.examples_per_pair,
            "selection": "Pair-specific SHA-256 order of seed|source|target|one-based sentence ID; before inference; no replacements",
            "language_tags": LANGUAGES,
            "provenance": {"url": URL, "archive_bytes": ARCHIVE_BYTES, "archive_sha256": ARCHIVE_SHA256,
                           "publisher": "FLORES-200, Meta AI / NLLB Team", "license": "CC-BY-SA-4.0",
                           "license_url": "https://creativecommons.org/licenses/by-sa/4.0/", "files": files},
            "tokenizer": {"sha256": SPM_SHA256, "version": sentencepiece.__version__, "source_limit": SOURCE_LIMIT,
                          "count_method": "SentencePiece content token count plus two NLLB encoder special tokens"},
            "cases_sha256": json_digest(cases), "cases": cases,
            "limits": "Two examples per pair are a tiny held-out lexical baseline, not conversational/human accuracy; upstream training contamination is unknown.",
        }
        validate_corpus(corpus)
        atomic_json(args.output, corpus)
        args.output.with_suffix(args.output.suffix + ".sha256").write_text(sha256(args.output) + "\n")
    except (ValueError, OSError, ImportError, tarfile.TarError) as error:
        parser.error(str(error))
    print(f"Prepared {len(cases)} cases, 90 directions, {len({case['sentence_id'] for case in cases})} distinct sentence IDs")
    print(f"Source-limit rejections retained: {sum(not case['source_supported'] for case in cases)}")
    print(f"Corpus: {args.output} ({sha256(args.output)})")


if __name__ == "__main__":
    main()

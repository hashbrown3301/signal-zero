# Public human-reference translation evaluation

This evaluates the actual Kotlin `NllbRuntime` on a fixed slice of original
FLORES-200 human parallel text. It measures cloud text translation, with no
phones, Firebase, microphone, speech synthesis or human quality judgment.

The [original publisher](https://github.com/facebookresearch/flores/blob/main/flores200/README.md)
links [this public archive](https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz).
Its pinned size is 25,585,843 bytes and SHA-256 is
`b8b0b76783024b85797e5cc75064eb83fc5288b41e9654dabc7be6ae944011f6`.
References are **CC BY-SA 4.0**, attributed to FLORES-200 / Meta AI / the NLLB
Team. Preserve the corpus manifest's license, publisher, source links and file
hashes when sharing references. These data are separate from the code license
and the model's noncommercial license.

The committed [corpus provenance manifest](evaluation/corpus-provenance.json)
records the exact seed, IDs, checksums, language map and attribution without
claiming an evaluation result. Raw source/reference text remains in the ignored
generated corpus; sharing that text must retain this attribution and CC BY-SA
4.0 notice.

Prepare the corpus after installing the translation model's verified
`sentencepiece.bpe.model`:

```sh
.venv/bin/python -m pip install sentencepiece==0.2.1 -r scripts/requirements-validation.txt
.venv/bin/python scripts/prepare_translation_evaluation.py
```

The script downloads the original archive once into ignored `scripts/downloads/`,
verifies its size/checksum, reads only aligned metadata and the ten requested
language files, and writes ignored `dist/evaluation/corpus.json` plus its checksum.
It never runs translation. Default selection takes two IDs per ordered pair from
the 1,012-item `devtest` split using a published seed and pair-specific SHA-256
ordering. Selection happens before viewing model outputs. Unsupported source
lengths remain selected failures; they are never silently replaced.

The prepared default corpus has **180 cases, all 90 directions, 164 distinct
sentence IDs and 18 cases per source language**. Maximum source length is 120
NLLB tokens; none exceeds the current 256-token source limit. The corpus file
SHA-256 is `3db0cf82b7bb858cf09088b2c5bc96c43c4d2e3427fef7575231f52c55eb300e`
with SentencePiece 0.2.1. Different seeds, splits, counts or tokenizer versions
create a different evaluation identity; preserve the manifest for comparisons.

Run the native evaluation alone, after other builds/native benchmarks finish:

```sh
ITANTRA_RUN_NLLB_EVALUATION=1 \
ITANTRA_NLLB_DIR=/workspace/signal-zero/dist/translation/nllb \
ITANTRA_ORTX_LIBRARY=/workspace/signal-zero/.venv/lib/python3.12/site-packages/onnxruntime_extensions/_extensions_pydll.cpython-312-x86_64-linux-gnu.so \
ITANTRA_EVAL_CORPUS=/workspace/signal-zero/dist/evaluation/corpus.json \
ITANTRA_EVAL_OUTPUT=/workspace/signal-zero/dist/evaluation/results.json \
LD_PRELOAD=/opt/codex/runtimes/codex-primary-runtime/dependencies/python/lib/libpython3.12.so \
python3 /workspace/tools/run-java-proxied.py bash gradlew \
  --init-script /workspace/tools/cloud-maven.init.gradle \
  testDebugUnitTest --tests com.itantra.translation.NllbCorpusEvaluationTest \
  --rerun-tasks --no-daemon --max-workers=2
```

These paths reflect this managed cloud's existing desktop Extensions/Python
library installation. Other hosts must use their compatible desktop custom-op
library; the Android AAR is not a host-native library. The opt-in test skips by
default. It verifies original model bytes, records actual model/runtime/compiled
class/source/commit hashes, loads one engine with two inference threads and
serializes calls. `source_tree_sha256` and a dirty-state flag identify uncommitted
code instead of attributing such a run only to its last commit.

Results retain raw source, human reference, hypothesis, token count, outcome,
errors and elapsed translation time after each case. A 60-second cooperative
deadline is checked by coroutine cancellation between native operations; it
cannot interrupt an operator already executing. Failures get an empty prediction
and remain in the report. Restarting with the identical corpus/configuration
resumes completed IDs; a changed configuration requires a new output path.
Fresh-engine initialization and model-byte verification are reported separately
from per-case translation. File caches are not flushed.

Score only a complete result set:

```sh
.venv/bin/python scripts/summarize_translation_evaluation.py \
  dist/evaluation/corpus.json dist/evaluation/results.json \
  --out dist/evaluation/summary.json
```

The summarizer rejects missing, duplicate or foreign IDs, changed references,
mixed corpus/configuration digests and invalid outcomes. It computes pinned
sacreBLEU **chrF++** (`word_order=2`) per pair and for the combined corpus, retains
failure rates and includes scoring signatures. Every selected failed prediction
stays in the score as empty text. Small synthetic tests verify the harness and
scorer only; they are never used as evaluation references.

chrF++ is lexical reference similarity, **not a percent accuracy**. Two examples
per pair are too few for general accuracy, confidence bounds or conversational
quality claims. The combined score mixes languages and topics. Inspect raw
translations for names, numeric roles, negation, destinations and omissions;
native-speaker review remains separate. FLORES article text is not the app's
complete conversation domain, and upstream training contamination is unknown.
Keep these cases held out from upgrade tuning and use a separate development set
for fixing the already observed errors.

Focused infrastructure checks:

```sh
.venv/bin/python -m unittest scripts.tests.test_translation_evaluation scripts.tests.test_evaluate_accuracy -v
```

Before the native run, full Python discovery completed successfully: **46 tests**
from six files in `scripts/tests` on 1 October 2026. These verify infrastructure,
scoring and regressions; they are not evidence of translation accuracy.

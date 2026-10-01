# Offline translation validation research and implementation plan

Research date: 2026-10-01. Scope is the corrected request: improve the existing offline translation path in the cloud, without phones, Firebase, model replacement, or implementing product changes before the plan. All repository inspection was read-only on branch `Upgradation`. Current cloud policy was verified unrestricted/enforced; no Hugging Face identity is configured. No new model/audio downloads or native benchmarks were run for this research.

## What the existing evidence establishes

- The historical 90-direction native test translated one short request for water per source language. This establishes a limited runtime coverage smoke test, not conversational accuracy.
- The current native timing report has 15 pair/prompt combinations, five directions, six distinct source texts, and 75 timed repeated executions. Repetitions add timing/stability evidence, not semantic coverage. All five repeated outputs within each case are identical.
- Known actual output defects remain: Hindi `रोहन` becomes English `Rohn`; Tamil medium and long outputs omit `to the station`; Telugu short wording is incomplete; the Odia medium quantity/negation relation likely changes `25, not 50` into `50, otherwise 25` and needs native confirmation. Some longer outputs omit/soften smaller details as well.
- Digit-token multiset equality cannot detect swapped desired/rejected quantities, signs, decimals, units, or time-order changes. Of 75 current samples, 25 have empty numeric sets and pass trivially. Even the remaining 50 results are a limited regression check, not semantic numeric preservation.
- Cloud execution cannot establish Android audio startup, acoustic latency, thermals, microphone accuracy, or listener-rated voice quality. Those measurements should remain explicitly outside this upgrade evidence.

## Public human references and access checks

### Original FLORES-200: recommended immediate text-translation reference source

Primary publisher links:

- Official composition/download/evaluation guidance: https://github.com/facebookresearch/flores/blob/main/flores200/README.md
- Official license declaration: https://github.com/facebookresearch/flores#licenses
- Direct original archive: https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz
- The official README's https://tinyurl.com/flores200dataset resolves to that same archive.

Actual access check: HTTPS HEAD returned **200**, content length **25,585,843 bytes**. The archive has not been downloaded in this planning phase. Freeze its SHA-256 after the first authorized download, then make repeat runs verify that same immutable byte identity.

Publisher states FLORES-200 contains professional translations of 3,001 sentences from 842 web articles, with dev/devtest/public and a hidden test split; approximately 21 words per sentence on average. Public dev and devtest contain 997 and 1,012 aligned sentence IDs respectively (2,009 public sentences). Preserve exact line alignment and metadata; never pair independent random rows. All ten required codes appear in the official language list:

| App ISO | FLORES code |
|---|---|
| en | eng_Latn |
| hi | hin_Deva |
| mr | mar_Deva |
| gu | guj_Gujr |
| bn | ben_Beng |
| ta | tam_Taml |
| te | tel_Telu |
| kn | kan_Knda |
| ml | mal_Mlym |
| or | ory_Orya |

**License:** CC BY-SA 4.0. Keep attribution, source/license links, archive/file hashes, dataset split, sentence ID, and derived-subset notices with any redistributed references. Use a separate corpus license notice rather than treating evaluation data as automatically covered by the app's code license.

**Metric convention:** official FLORES guidance primarily uses chrF++ (`sacrebleu -m chrf --chrf-word-order 2`) and additionally spBLEU. The project's current `CHRF()` computes plain chrF with default word order 0. It is valid but must not be compared directly to published chrF++ values. Use pinned sacreBLEU 2.5.1 `CHRF(word_order=2)` and store its signature for the new evaluation. A lexical reference metric still does not prove correct intent.

**Domain limit:** FLORES is largely article text, not a representative hold-to-talk conversation corpus. It supplies independent public human references for a reproducible first measurement, not final-domain certification. Its test examples are well-known; call the chosen subset held out from this upgrade's development, not demonstrably unseen in all upstream model training.

### Current FLORES+ and Hugging Face FLORES: gated in this environment

- https://huggingface.co/datasets/openlanguagedata/flores_plus
- https://huggingface.co/datasets/facebook/flores
- https://github.com/openlanguagedata/flores

Observed metadata: both HF repositories have automatic-approval gating and CC BY-SA 4.0. Unauthenticated actual FLORES+ README fetch returned 401. The OLDI GitHub repository now redirects users to the maintained HF distribution rather than providing the current dataset files. No account credentials are configured, and access has not been accepted. Do not pretend current FLORES+ was fetched. The publisher separately continues to link the independently public original archive above, which is the concrete accessible source for this plan.

Observed HF revisions (metadata only): FLORES+ `5fec6c13f9e5a4db2f745d4ec0d7c9721ddc4f06`; facebook/flores `71abf77d8b7beb5cfef59898d6b24d92ab7654fc`.

### Google FLEURS: accessible human recorded speech, optional separate ASR evaluation

- Official Google dataset card: https://huggingface.co/datasets/google/fleurs
- Paper: https://arxiv.org/abs/2205.12446
- Pinned revision: `70bb2e84b976b7e960aa89f1c648e09c59f894dd`
- Example transcript: https://huggingface.co/datasets/google/fleurs/resolve/70bb2e84b976b7e960aa89f1c648e09c59f894dd/data/en_us/test.tsv

Observed metadata: ungated, dataset card CC BY 4.0; raw TSV files and audio archives exist for all ten configurations (`en_us`, `hi_in`, `mr_in`, `gu_in`, `bn_in`, `ta_in`, `te_in`, `kn_in`, `ml_in`, `or_in`). The card describes 2,009 n-way-parallel FLORES sentences, with human recordings and speakers separated between train and dev/test. It collects one to three recordings per sentence and rebuilds train/dev/test splits. Preserve both original FLORES text provenance and FLEURS audio provenance/license declarations.

Small actual text check: English test.tsv downloaded (367,864 bytes, 647 recordings, 350 distinct IDs); Hindi test.tsv downloaded (473,366 bytes, 418 recordings, 265 distinct IDs). They share 265 sentence IDs; spot checking shared IDs confirms matching human source/target sentences. Example ID 1661 English: `He did not set a figure for the cuts, saying they will be made based on China's economic output.`; Hindi: `यह कहते हुए कि वे चीन के आर्थिक उत्पादन के आधार पर बनाए जाएँगे, उन्होंने कटौती के लिए कोई आँकड़ा निर्धारित नहीं किया.` Multiple audio rows for one ID repeat a reference, so deduplicate sentence IDs for MT and retain recording IDs for ASR. Do not align by recording filename, row position, or assuming all language test splits contain every ID.

Full test audio archives are approximately 249–722 MB **per language**; defer these downloads. Existing real FLEURS audio can support a separate cloud ASR test later. Controlled added noise can be a robustness perturbation of real recordings, with explicitly reported SNR/seed; it is not evidence of real noisy-field performance. Model-generated TTS round trips remain synthetic smoke tests, never human speech gold.

## Concrete current tool weaknesses

- `scripts/evaluate_accuracy.py` checks coverage by row existence. A single row, even an empty hypothesis, can count for a pair. It has no immutable expected corpus IDs, minimum per-pair/category counts, structured failure outcomes or reference provenance.
- Arbitrary `system` labels can union different model/settings records; duplicate identity includes model, allowing the same corpus item to be pooled across incompatible configurations. Require one immutable configuration digest per evaluation.
- Recognition normalization drops some Unicode punctuation signs: a lightweight check confirmed `–5` versus `5` yields normalized CER 0. Preserve/canonicalize numeric signs before punctuation removal. “Strict” CER/WER still NFKC-normalize/casefold/collapse whitespace; label them punctuation-sensitive, not exact raw scoring. Do not strip Indic combining marks.
- The current native smoke's numeric/negation substring checks can pass an inverted instruction. Keep them as narrow engineering assertions and introduce explicit typed-detail anomaly checks plus reference-grounded review.
- Current benchmark repetition cap is 20, while existing validation docs propose at least 30 distinct turns. Five-sample p90/p95 both equal maximum; do not describe this as reliable tail characterization.
- Historical `summarize_benchmarks.py` filters by recognized-output WER threshold and infers nearest reference; this biases recognition accuracy by excluding severe errors. Historical nukta/chandrabindu removal differs from the new scorer. Never pool those old scores into a matched before/after comparison.
- Current first-audio synthetic benchmark uses one fixed passage and always runs full synthesis before chunks. Counterbalance execution ordering and preserve chunk boundaries/prosody/gap observations in any future voice comparison.

## Four evidence pillars for the corrected upgrade

1. **Independent reference quality:** actual app Kotlin runtime translates a locked human-parallel corpus; retain every selected example, raw source/reference/hypothesis, outcome, model hashes, exact settings, commit/dirty state, dataset split and timing. Report chrF++ per pair and descriptive combined score with sample counts. Empty/failing outputs remain outcomes; missing expected examples invalidate completeness. Do not call chrF++ a percent accuracy.
2. **Critical meaning preservation:** explicitly review names, quantity roles, signs, decimal separators, units, times, destinations, and negation scope. Typed extraction flags differences but never auto-certifies intent. Public references provide an independent comparator. User-reviewed exact phrasebook entries are individually verified cases; coverage/unknowns remain visible. Model outputs, back-translations, synthetic references or model agreement are not gold.
3. **Bounded speed/memory:** serialize native inference through one engine on the two-CPU quota; distinguish engine load, first translation, warm unique prompts and text-cache hits. Use fixed seeded order or counterbalanced baseline/candidate order, at least 30 unique timing items for descriptive tails, raw individual observations, abort/failure counts and memory snapshots. OS-warm files are not cold-disk measurements. Enforce realistic source/output/work time bounds and record unsupported/timeout outcomes explicitly.
4. **Offline robustness:** test same request after different previous requests, exact replay and duplicate identity, restart determinism, cancellation, retry, queue pressure, near-token-limit input and explicit over-limit input, whitespace/punctuation/Unicode variants, script/language mismatches and corrupt/missing models. Tests use fully local assets and runtime; no Firebase/account/online fallback is needed. Repeatability is not semantic correctness. Transcript correction and exact phrasebook behavior need explicit original/corrected/reviewed provenance and no silent substitution.

## Proposed small first public-reference evaluation (after root go)

- Keep the current model/artifact hashes and production runtime unchanged.
- Fetch the original official FLORES archive once, verify size and SHA-256, safely read only metadata plus the ten requested language files. Check split line counts, unique IDs, all ten alignments, UTF-8, no unexpected archive paths/oversize expansion, and reference nonemptiness. Keep data in ignored `dist/`, with an attribution manifest.
- Use **two seeded deterministic aligned devtest examples per ordered pair**, all 90 pairs = **180 native calls**. Use a published seed and pair-specific SHA-256 ordering over dataset sentence IDs so the run samples more than two global intents; record actual distinct IDs. Select before looking at outputs. Keep development regression fixtures separate and do not rewrite held-out selections to favor a fix.
- Tokenize selected inputs with the pinned SentencePiece contract and apply the existing source limit. Source guards should produce explicit unsupported/failure outcomes; never silently delete hard examples or pick replacements after inspecting results. Save expected IDs first and preserve the source/reference regardless of outcome.
- Opt-in JVM harness loads the actual `NllbRuntime` once, runs serialized work, saves raw results after each case and records actual configuration/provenance/timing. Checkpoints resume only under an identical corpus/configuration digest. Same-language bypasses are a separate ten-case functional test, excluded from 90-pair metrics.
- Score standard pinned **chrF++** per pair (two references per pair means highly unstable estimates) plus descriptive combined corpus score, actual attempts/successes/failures, unsupported inputs and distinct intent count. Do not add confidence bands that imply useful statistical power from two items per pair. A later larger locked subset can support paired bootstrap intervals over unique intents/articles.
- Compare future baseline/candidate changes only on this same locked corpus/settings, then expand the evaluation before claiming broad quality improvement. These 180 results provide a real held-out lexical baseline and examples for review, not conversational accuracy or human-certified adequacy.

## Suggested file boundaries for later implementation

- `scripts/prepare_translation_evaluation.py`: official archive fetch, bounded extraction, language/ID alignment, deterministic corpus manifest and provenance.
- `app/src/test/java/com/itantra/translation/NllbCorpusEvaluationTest.kt`: opt-in actual native corpus harness; no production translation/runtime behavior changes.
- `scripts/summarize_translation_evaluation.py`: strict corpus/result completeness, chrF++, failure counts, raw provenance and sample limitations.
- `scripts/evaluate_accuracy.py` and `scripts/tests/test_evaluate_accuracy.py`: signed Unicode dash normalization correction with genuine negative/range/decimal regression cases.
- Focused unit tests for corpus construction and summary failures. Avoid production runtime, existing NativeTest, session/UI/phrasebook files owned by other agents.

No implementation has started for this phase. No phones, Firebase, new model, fabricated corpus accuracy, or unreviewed reference translations are prerequisites for the first 180-call public-reference baseline.

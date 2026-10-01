# Upgradation: ten-language voice translation

All implementation stays on `Upgradation`. This plan supersedes the earlier
"no translation" scope in `CLAUDE.md`: the user now requests real translation.

## Intended experience

Choose **I speak** and **I want to hear** independently. A conversation shows the
original transcript and the translated text, then speaks the translation in the
selected listening language. Support Hindi, English, Marathi, Gujarati, Bengali,
Tamil, Telugu, Kannada, Malayalam and Odia. Keep conversations active when changing
tabs, clearly explain missing packs, and retain file import for offline setup.

The user confirmed **fully offline** and **hold-to-talk** on 2026-10-01.
Recognition, translation and synthesis run on the phone. Online downloads are setup
only; importing a verified ZIP works without internet even during installation.

## Implemented offline path

Use the pinned NLLB-200 distilled 600M int8 encoder and cached merged decoder,
with exact native SentencePiece tokenization. All ten language tags are supported;
Indic-to-Indic translation is direct, with no compulsory English pivot. The shared
899,478,308-byte model installs separately from recognition and voice packs.

On release, process the complete utterance once. Preserve the original, translate
into the selected listening language, then synthesize that language in bounded
chunks. Incoming text follows the same receiver-side path. Same-language messages
bypass translation. Missing/damaged models and failed generation retain the
original with an explicit failure and never play it as a translated result.

Model imports/downloads verify sizes and pinned checksums before atomic activation.
Native calls are serialized, cancellation is checked between decoder steps, repeat
phrases use a 128-entry cache, and input/output token limits fail explicitly instead
of silently truncating meaning. The legacy 2 GB phones cannot load this model.
The output-layer optimization lowers memory and translation time; a verified
streaming patch generates its decoder locally, keeping the original model pack
unchanged. See [OFFLINE_TRANSLATION_RESULTS.md](OFFLINE_TRANSLATION_RESULTS.md)
for all 90 actual-model checks, limited output comparisons, and measured CPU/RAM.

The selected model is noncommercial and its upstream intended use is research.
This is an integrated evaluation build; production model licensing, 16 KB native
compatibility, human translation quality, and real-device latency are release gates.
See [OFFLINE_TRANSLATION_VALIDATION.md](OFFLINE_TRANSLATION_VALIDATION.md).

## Implementation order and completion gates

| Stage | Change | Required evidence |
| --- | --- | --- |
| 1. Baseline | Separate VAD, STT, translation, first audio, total synthesis, queue, network and render timing; retain compact text transport. | Reproducible stage timings and current unit/build baseline. |
| 2. Voice latency and delivery | Synthesize long utterances in bounded text chunks; prepare one next chunk during playback; keep short utterances intact. Fix recorder shutdown and VAD tails. Reduce idle PING frequency; match ACK/PONG identities and expire undelivered sends. | Tests for early playback, ordering, cancellation, tail preservation, stale responses, idle traffic and delivery deadlines. No changes to the v2 wire format. |
| 3. Recognition accuracy | Benchmark all ten existing recognizers against reference speech. Compare alternatives only where measured errors justify replacing a model. Include names, numbers, negations, noisy speech and code-mixing. | CER/WER per language and device; no accuracy claims from synthetic round trips alone. |
| 4. Actual translation | Implement source -> selected target language; same-language bypass; preserve originals; load the model once; bound work/cache/memory; show explicit loading and failure states. Use the pinned fully offline model/runtime; retain explicit model-unavailable errors. | Real translated output for every directed language pair (90), tests for cancellation and missing models, and human review of critical phrases. |
| 5. Product UI | Independent speaking/listening selectors, original + translation transcript, clear pack readiness, accessible controls, actionable errors and consistent dark theme. Avoid exposing benchmark tooling in normal setup. | Android screenshots and accessibility checks on small/large screens; session survives tab changes. |
| 6. Integrated validation | Voice -> recognized text -> translated text -> target voice over both transports, reconnect and long-session testing. | Signed-off device tests, reproducible accuracy report and p50/p90/p95 release-to-first-audio timings. |

## Performance and quality criteria

- Keep 16 kHz input speech; lowering microphone quality does not reduce the text
  transport bitrate and can damage recognition accuracy.
- Preserve lossless text packing and the v2 framing/CRC; measure idle control
  traffic independently from bytes per utterance.
- Measure first audio separately from total voice synthesis, including translation
  and model-loading cost. Chunking should lower first-audio delay for long messages;
  actual phone gains must be measured, not inferred from fake-speaker tests.
- Initial device targets: aspirational p90 release-to-first-audio <= 2 seconds on the S25 and
  <= 4 seconds on a supported 64-bit midrange phone, after warmup. These are targets,
  not measured results or promises. Revise them after translation-model trials.
- Do not promise zero lag or lower error rates until device and corpus measurements
  support the claim. Current low-end A03 Core measurements cannot meet these targets.
- Translation quality: report chrF on held-out references and human judgments of
  meaning, names, numbers and negation. Recognition: report CER and WER separately.
- Cover all 90 directed translation pairs, not just each language to English.
- An unavailable model/voice or translation failure must never silently play the
  original text as though it were translated.

## Baseline

Initial branch HEAD: `7aacd62`. Cloud baseline: 102 JVM tests passed, all three debug
APKs built, Hindi synthetic TTS -> STT round trip passed. Physical-device translation,
noise robustness and latency have not been measured in this cloud session.

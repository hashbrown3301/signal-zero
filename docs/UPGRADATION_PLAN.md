# Upgradation: ten-language voice translation

All implementation stays on `Upgradation`. This plan supersedes the earlier
"no translation" scope in `CLAUDE.md`: the user now requests real translation.

## Intended experience

Choose **I speak** and **I want to hear** independently. A conversation shows the
original transcript and the translated text, then speaks the translation in the
selected listening language. Support Hindi, English, Marathi, Gujarati, Bengali,
Tamil, Telugu, Kannada, Malayalam and Odia. Keep conversations active when changing
tabs, clearly explain missing packs, and retain file import for offline setup.

The current app recognizes speech and relays the text in its original language;
it does not yet translate. Translation must be implemented with a real model or
service, rather than marking same-language playback as translation.

## Decisions requested

1. Must translation itself work entirely offline, or is an online service allowed?
2. Start with translation on each hold-to-talk message, or continuous live speech?

The independent performance work below retains the existing offline voice flow.
Do not add a cloud dependency or choose a reduced-coverage translation provider
while the offline requirement is unresolved. A mobile translation candidate must
cover **all ten** languages, including Malayalam and Odia, with explicit licenses,
model sizes, memory use and real-device timing. If download access or credentials
are needed, document them before claiming that translation is ready.

## Implementation order and completion gates

| Stage | Change | Required evidence |
| --- | --- | --- |
| 1. Baseline | Separate VAD, STT, translation, first audio, total synthesis, queue, network and render timing; retain compact text transport. | Reproducible stage timings and current unit/build baseline. |
| 2. Voice latency and delivery | Synthesize long utterances in bounded text chunks; prepare one next chunk during playback; keep short utterances intact. Fix recorder shutdown and VAD tails. Reduce idle PING frequency; match ACK/PONG identities and expire undelivered sends. | Tests for early playback, ordering, cancellation, tail preservation, stale responses, idle traffic and delivery deadlines. No changes to the v2 wire format. |
| 3. Recognition accuracy | Benchmark all ten existing recognizers against reference speech. Compare alternatives only where measured errors justify replacing a model. Include names, numbers, negations, noisy speech and code-mixing. | CER/WER per language and device; no accuracy claims from synthetic round trips alone. |
| 4. Actual translation | Implement source -> selected target language; same-language bypass; preserve originals; preload required model; bound work/cache/memory; show explicit loading and failure states. Select model/runtime or service after the offline decision. | Real translated output for every directed language pair (90), tests for cancellation and missing models, and human review of critical phrases. |
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
- Initial device targets: p90 release-to-first-audio <= 2 seconds on the S25 and
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

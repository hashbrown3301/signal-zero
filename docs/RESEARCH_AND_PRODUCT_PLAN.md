# Offline translation: research and implementation plan

Prepared 1 October 2026 before feature implementation, for `Upgradation` only.
This plan concerns the existing Android translation app, not navigation. Phones
and Firebase Test Lab are unavailable; development and validation continue in
the cloud. Dataset, synthetic, JVM and cloud performance evidence are labelled
separately. No cloud result is a phone benchmark or a promise of zero latency.

## Existing product and architecture

The Kotlin/Compose app provides hold-to-talk Solo, local Wi-Fi and Bluetooth
conversations in Hindi, English, Marathi, Gujarati, Bengali, Tamil, Telugu,
Kannada, Malayalam and Odia. VAD and recognition run locally; text packets,
rather than audio, cross the peer link. The receiving phone translates into its
chosen listening language and synthesizes speech. Speech and voice packs are
per-language; translation uses a shared, pinned NLLB-200 distilled 600M INT8
model. Setup supports downloads and offline ZIP imports. Conversations remain
offline after setup.

`MainViewModel` owns Android engines, packs and session lifecycle.
`SessionManager` coordinates recognition, reliable text delivery and serialized
playback. `OfflineTranslator` serializes native inference and has a bounded
exact-input cache. `NllbRuntime` owns tokenizer, encoder and decoder sessions.
`ChunkedSpeaker` prepares bounded voice chunks while the preceding chunk plays.
Atomic translation-pack activation and checksummed downloads are already useful
and should be retained. Stable wire-v2 packets and language codes must remain
compatible.

## Findings and priorities

| Priority | Actual weakness | Consequence and response |
| --- | --- | --- |
| P0 | Gesture cancellation does not cancel capture; capture has no duration bound, terminal read-error handling or bounded shutdown. | Stop/discard on cancellation and foreground loss; bound capture and report failure instead of silently truncating. |
| P0 | Recognition is automatically sent/spoken without correction. | Add optional review before any send or translation; preserve fast default hold-to-talk behavior. |
| P0 | Translation outputs can preserve all digits while losing names, destinations or conditions. | Add conservative critical-detail warnings and explicit playback review. Never display a made-up confidence percentage. |
| P0 | Playback queue is unbounded; setup can leak an independently scoped transport if cancelled before session ownership transfers. | Bound queued work and make transport ownership explicit. Test cancellation, overload, duplicate and reconnect paths. |
| P0 | Same-length model corruption can survive a verified reimport; speech-pack readiness can outlive missing files. | Let a verified replacement repair a damaged installation; verify required speech assets for readiness. |
| P1 | No typed input, local retry or replay after model/voice failure. | Reuse one validated submission path; retry from preserved source, replay preserved target without another translation. |
| P1 | Native translation consumes roughly 1.9 GiB in the measured JVM process and is retained after leaving a session. | Release idle translation sessions safely; introduce exact reviewed phrase lookup before the native runtime/memory guard. |
| P1 | Production logging includes transcripts and persistent benchmark CSVs. | Gate diagnostic persistence to debug builds and remove conversation text from normal logcat. |
| P1 | Existing accuracy evidence is tiny or synthetic; digit equality misses meaning errors. | Add reproducible public human-reference evaluation, directional coverage and explicit critical-detail fixtures. |
| Release gate | Model is licensed for noncommercial research; an ARM64 extension ELF has 4 KiB LOAD alignment. | Document licensing and Android 16 KiB compatibility as unresolved release requirements. A successful debug build is not commercial-release approval. |

The current cloud report already measures 394–667 ms short-text median inference
in selected directions, roughly 3.5–4.4 seconds for the longer samples and about
5.6 seconds of fresh native initialization. These are x86 cloud measurements,
not end-to-end phone latency. Existing historical phone results predate the new
translation path. Source: [current benchmark report](CURRENT_APP_BENCHMARKS.md).

## Competitor and research conclusions

Google Translate already documents downloaded-language offline translation,
conversation, saved phrases and listening to results. Its supported Pixel live
translation also documents offline operation. RTranslator's stable 2.x product
already implements offline peer translation, typed input, transcript display and
audio replay; its repository currently mixes stable and newer beta material.
Microsoft's current Android listing retires multi-device conversation. Therefore
offline, typing, replay and phrasebook features are not claimed as inventions.

The useful opportunity is a coherent workflow for resource-constrained Indic
conversations: correct recognition first, expose observable translation risks,
use exact translations explicitly reviewed by the user, and recover without
repeating capture or wasting native inference. All 90 ordered cross-language
directions are an architecture/coverage target; identical quality in all 90 has
not been established. Model-paper scores cannot be copied onto this quantized
runtime or its speech pipeline.

Primary references used for this analysis:

- [Google Translate Android help](https://support.google.com/translate/?hl=en): offline language downloads, conversation/live translation, saved phrases and listening.
- [Google Translate developer listing](https://play.google.com/store/apps/details?id=com.google.android.apps.translate): documented offline, conversation and phrasebook features.
- [RTranslator repository and releases](https://github.com/niedev/RTranslator): inspect stable 2.1.5 separately from default-branch 3.x beta claims.
- [Microsoft Translator developer listing](https://play.google.com/store/apps/details?id=com.microsoft.translator): current feature and retirement statements.
- [NLLB research and model card](https://huggingface.co/facebook/nllb-200-distilled-600M): multilingual evaluation, intended use and license.
- [Official FLORES repository](https://github.com/facebookresearch/flores): aligned human-reference translation data; original public FLORES-200 release is CC BY-SA 4.0.
- [FLEURS paper and dataset](https://huggingface.co/datasets/google/fleurs): recorded speech validation, CC BY 4.0; existing small ASR results remain separate from translation scores.
- [Android AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord), [log disclosure guidance](https://developer.android.com/privacy-and-security/risks/log-info-disclosure) and [16 KiB support](https://developer.android.com/guide/practices/page-sizes).

## Four connected product features

### 1. Review and correct before sending

1. **Problem:** an ASR mistake is currently transmitted and translated immediately.
2. **Importance:** editing the actual recognized source addresses a demonstrated upstream accuracy limitation while keeping the original transcript auditable in the session.
3. **Existing alternatives:** competitors display transcripts and support text entry; transcript display alone does not establish a pre-send review contract.
4. **Our approach:** optional explicit source review freezes sending and playback until confirmation; cancellation discards the draft. Default fast hold-to-talk remains available.
5. **Implementation:** a session-owned draft and Reviewing phase; confirm through the same bounded submission path as typed input.
6. **Code changes:** `SessionManager`, state/message metadata, ViewModel callbacks, Talk screen review editor and review preference.
7. **Validation:** deterministic JVM tests prove zero packets/translation before confirmation, corrected text is sent, discard produces no message, repeated taps and session closure cannot send a stale draft.
8. **Risks:** review adds deliberate user time and cannot fix a speaker who does not notice a wrong transcript. Do not describe it as improved model accuracy.

### 2. Critical-detail review before automatic speech

1. **Problem:** translations may omit or alter quantities, signs, times and other meaning-bearing details; model outputs currently autoplay.
2. **Importance:** a visible warning and explicit decision are preferable to confidently speaking an observable mismatch.
3. **Existing alternatives:** competitors offer translation/audio, but reviewed documentation does not establish this exact local numeric-detail gating workflow. Absence from docs is not proof of absence from every product.
4. **Our approach:** compare conservative canonical numeric tokens with multiplicity, preserving Unicode digits, signs, percentages and time notation. Flagged model outputs remain readable and require explicit Play anyway.
5. **Implementation:** a pure checker, warning metadata and Needs review status; keep source and target together. Exact user-reviewed phrases retain their provenance.
6. **Code changes:** new translation checker, session playback/replay gating, warning explanations and explicit consent action in Talk.
7. **Validation:** multilingual fixtures for duplicate quantities, signs, decimal/grouping ambiguity, missing/added values and times; session tests prove no synthesis before explicit consent.
8. **Risks:** lexical checks miss role reversals, negation, names and destination omissions, and can flag valid spelled-out numbers. Never certify an unflagged result as correct; medical/legal decisions still require competent review.

### 3. User-reviewed exact offline phrasebook

1. **Problem:** repeating common phrases wastes inference; the general model cannot run on some low-memory/32-bit phones and is not always accurate.
2. **Importance:** reusable corrected translations offer predictable text for known situations without loading the large model.
3. **Existing alternatives:** saved/starred phrases are common. Saving an uncontrolled model result is not the same as explicitly reviewing its target text.
4. **Our approach:** editable, user-reviewed source/target pairs with exact language-scoped matching and visible provenance. Lookup occurs before native loading and its memory guard.
5. **Implementation:** bounded atomic JSON persistence; trim/collapse whitespace only. Preserve case, scripts, numbers and signs. Misses fall through to the normal translator and its explicit errors.
6. **Code changes:** new phrasebook store and translator decorator, ViewModel load/save/delete, phrase editor/list and local-use action.
7. **Validation:** no-model fake translator tests, language isolation, replacements/deletions, Unicode/exact-match boundaries, reload/corruption/atomic-write tests and bounded persistence.
8. **Risks:** review is by the user, not a certified linguist. Exact matching does not support paraphrases. On a peer session the receiving phone still needs its own matching phrase/model; target text is not silently substituted into source-language packets.

### 4. Capture-free recovery: type, retry and replay

1. **Problem:** microphone failure, ASR failure, missing voice or translation failure currently leaves users without an actionable recovery path.
2. **Importance:** users can continue a conversation and recover installed resources without speaking the same utterance again.
3. **Existing alternatives:** typed translation and replay already exist in other products.
4. **Our approach:** typed input joins the same offline peer/Solo pipeline; retry reuses the retained source, while replay uses retained output without another model call. Warning consent and reviewed-phrase provenance remain intact.
5. **Implementation:** validate nonblank bounded UTF-8 input; serialize bounded playback work; eligible-message retry/replay with clear errors and duplicate suppression.
6. **Code changes:** session APIs and queue accounting, ViewModel callbacks, text composer, contextual message actions and recovered-resource notices.
7. **Validation:** unit/integration replays with fake transports, translators and speakers; missing voice then install/retry, repeated replay presses, failed translation then retry, oversize input and queue overload, cancellation and reconnect/dedup stress.
8. **Risks:** retry can produce a different model result; replay must not imply a new translation. Wire ACK establishes receiver handling, not proof that a human heard the audio. Text-only recovery still needs language-appropriate fonts and packs where applicable.

## Five core reasons to choose the product

| Core feature | User value | Honest boundary |
| --- | --- | --- |
| Offline ten-language Indic conversation | Local recognition, translation and voice after setup, with every ordered pair explicitly covered by tests. | General translation requires supported hardware; coverage is not equal-quality certification. |
| Local peer text transport | Wi-Fi/Bluetooth conversation without uploading speech or requiring an internet translation service. | Local-link behavior and audio timing still require physical-device qualification. |
| User-controlled accuracy workflow | Correct source before send and inspect observable detail mismatches before speech. | User review and lexical warnings are safeguards, not semantic guarantees. |
| Reviewed exact phrases without native translation | Predictable reusable translations and useful limited operation on lower-resource devices. | Only explicitly saved exact pairs bypass the model; no arbitrary low-RAM translation claim. |
| Recoverable, measurable conversation | Type, retry and replay with separate recognition/translation/voice/link timing evidence and preserved original text. | Cloud/dataset/simulation evidence is labelled; no invented phone results or zero-lag promise. |

These are a combined product proposition, not five claims of worldwide novelty.

## Implementation order and dependencies

1. **Complete this audit and plan first.** Preserve current native model, tokenizer, language codes, pack layout and working transports.
2. **Define small shared contracts.** Draft/review state, translation origin, warning list and phrasebook entry fields; keep Android-free logic testable.
3. **In parallel:** implement bounded capture/cleanup; session review/recovery/backpressure; pure critical checker; atomic reviewed phrase store and readiness repair; Talk editors/actions; public-dataset harness.
4. **Integrate centrally:** ViewModel connects persistence, translator decorator, preferences, callbacks and engine lifecycle; Main screen passes actions. Close transport on setup cancellation and avoid production transcript diagnostics.
5. **Verify incrementally:** compile and JVM suite after integration, focused fault/stress tests and Python tooling tests; then opt-in real native corpus inference with no competing CPU-heavy jobs.
6. **Finish the deliverable:** assemble all debug ABI APKs, inspect repository diff, report remaining release/device limits, commit and publish to `Upgradation` only.

## Validation and benchmark strategy

- **Pure tests:** numeric canonicalization, exact reviewed lookup, persistence validation and corruption; input limits and capture bounds.
- **Deterministic integration/replay:** fake mic/translator/voice/transport, virtual or injected clocks where possible, duplicate packet/reconnect, review confirm/discard, typed submission, warning consent, retries, queue saturation and cancellation. These establish state-machine behavior, not radio or microphone performance.
- **Human-reference translation:** pin the official original public FLORES-200 archive and SHA-256, deterministic aligned devtest selections, two examples per ordered pair across all 90 pairs (180 planned inferences). Record every case, hypothesis/reference, failure, native timing, dataset/model/runtime revision, unique sentence count and sample policy. Score chrF++ with a published signature; report per-direction values as tiny smoke samples, never population accuracy.
- **Meaning checks:** retain explicit critical fixtures and known model failures. Numeric equality does not pass a semantic quality gate. Human bilingual review remains required for intent preservation.
- **Existing ASR/TTS evidence:** reuse historical and synthetic reports with their original scope; do not relabel old phone recordings as testing this build. FLEURS audio expansion is optional because downloads and sample review are substantial.
- **Performance:** keep cloud native runs sequential on the two-core allocation. Reviewed phrase tests prove model bypass; any measured wall-clock lookup result is identified as JVM/cloud. Existing uncached native latency remains separately reported. No conversion from desktop speed to phone speed.
- **Build checks:** JVM/Python suites and three ABI debug APK assemblies. Android rendering, physical recording/playback, Bluetooth/Wi-Fi behavior, thermals, battery, accessibility and phone memory remain unverified until hardware is available.

## Risks and completion criteria

The four features must have real callbacks, session behavior and automated
checks, not just UI mockups. Input and queues must be bounded, cancellation must
not send captured text, failures must remain visible, and phrasebook misses must
never fabricate a translation. Keep code additions modular and limit changes to
demonstrated weak points. Model licensing, native 16 KiB compatibility and absent
phone qualification prevent a claim of complete commercial-release readiness.

Implementation outcomes, executed tests, corpus scores and unresolved issues
will be recorded separately after the work, preserving this pre-implementation
plan and its evidence boundaries.

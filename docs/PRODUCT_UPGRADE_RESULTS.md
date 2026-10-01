# Product upgrade results

1 October 2026, branch `Upgradation`. The pre-implementation audit and dependency
plan are preserved in [RESEARCH_AND_PRODUCT_PLAN.md](RESEARCH_AND_PRODUCT_PLAN.md);
the six domain audits and integration review are in [research](research/).
No physical phones, emulator, Firebase run or competitor-app trial was used.

## Four features implemented in the app

| Feature | Working product behavior | Evidence and boundary |
| --- | --- | --- |
| Review and correct source | Optional Review speech creates an editable draft. Confirm submits corrected text; discard/cancel sends nothing. Original recognition is retained when corrected. | Session tests cover no translation/packets before confirm, correction, discard and cancellation. Default fast hold-to-talk remains available; ASR weights are unchanged. |
| Numeric-detail review before speech | Unicode-aware numeric multiset checks preserve signs, decimals, percentages and times. Flagged model outputs stay readable; Play anyway explicitly authorizes audio. | Checker and session tests cover detail variants and zero synthesis before consent. Names, negation, roles, units and spelled-out numbers are outside the checker; no confidence/correctness certificate is shown. |
| User-reviewed exact phrasebook | Editable directional source/target pairs, explicit review acknowledgment, add/update/delete/use, atomic bounded local storage and visible reviewed provenance. Exact lookup runs before model loading or hardware admission. | Tests prove stored target reuse when fallback cannot run, correct language isolation, immediate edits/deletions, corruption reporting and persistence bounds. Only exact saved pairs work without the general model; peer receivers use their own phrasebook/model. |
| Type, retry and replay | Typed input works without microphone/recognition packs; all ten source languages remain selectable. Retry reuses retained source; replay uses retained target without another translation. Idle-session pack installation/repair pauses speech and keeps messages for recovery. | Fault/replay tests cover missing voice, translation failure, deduplication, preserved provenance and pack-maintenance pause/resume. Actual Android dialogs, rendering and microphone/radio behavior still need device qualification. |

Phrase editor state survives Activity recreation. Changing the source of an
existing phrase deliberately creates a separate exact key and leaves the old
entry available for deletion. User review does not imply linguistic certification.
Corrupt phrasebook storage is reported and kept; it is not silently overwritten
or substituted by a model result. A storage repair/reset UI is future work.

## Reliability and code changes

- Capture owns isolated per-recording buffers, rejects utterances beyond 30 seconds, terminates read errors, and discards cancelled capture. Reader join is bounded to 500 ms; platform stop/release still depend on vendor drivers.
- Capture also cancels when the Activity stops. Half-duplex playback waits through recognition, source review and pack maintenance. Typed processing does not pretend to run ASR.
- Queued playback is bounded to 16 plus the active worker; pending outgoing messages are bounded to 32; completed visible history is bounded to 100 while active/pending rows stay visible. Flood tests preserve PONG responsiveness.
- Remote failures acknowledge handled text and retain receipt identity, preventing duplicate playback on resend after local recovery. ACK is not evidence that audio was heard. Receiver timing is labelled as an estimate.
- Session setup and exit close their locally owned transport/session, including cancellation. Speech failures keep the chosen source language truthful instead of silently switching to Hindi.
- Verified translation reimport repairs same-length corruption and releases the native runtime before activating replacement. Ready installs have a Reimport to repair action. Speech readiness validates required sizes and canonical manifest paths; traversal/duplicate/root-extra cases are rejected.
- Installed voice replacements evict cached voices; current recognition replacements reload safely outside capture/session ownership. Source changes and missing packs remain distinct from typed translation availability.
- Reviewed phrase persistence has one process-wide owner; initial lookup/mutations use IO, atomic bounded writes and durable revisions. Native translation/cache state is cleared even if close reports an error.
- Leaving a conversation or Android memory pressure releases lazy native translation sessions under their lock. Production transcript logcat output is removed; diagnostic CSV persistence is debug-only with bounded current/previous retention.

Stable language codes, wire-v2 packets, the pinned model, tokenizer, optimized
decoder and existing voice-chunk pipeline are preserved. New logic is split into
the capture controller, phrase store/decorator, pure checker, session APIs and
product dialogs; the project was not rebuilt around a new framework.

## Executed validation

The final regular JVM suite passed **231 tests**, with **four opt-in native tests
skipped**, zero failures and zero errors. All three debug ABI APKs assembled.
JVM/build results are recorded in
[product-unit-results.json](benchmarks/product-unit-results.json). Python tooling
validation passed **46 tests**. The separate opt-in native evaluation completed
one harness test with **180 actual inference attempts**.

Meaningful deterministic checks include source-review state, typed mic bypass,
warning consent, exact phrase overrides, retry/replay, repeated/reconnected packet
identities, a 200-message flood, pending admission, recording errors/bounds,
durable phrase corruption/write failures and damaged/unsafe pack handling.
Fakes and synthetic fixtures establish these logic contracts; they are not phone
latency, microphone quality or acoustic measurements.

## Public-reference translation baseline

The actual Kotlin `NllbRuntime` evaluated two seeded aligned FLORES-200 devtest
examples per ordered direction: **180 cases, 90 pairs, 164 distinct sentence IDs**.
All selected inputs stayed below the source-token bound. Selection, publisher,
license, archive/member/tokenizer hashes, cases and article links are retained.

| Observation | Measured result |
| --- | --- |
| Nonblank native outputs | 179 of 180 |
| Generation-limit failures | 1, `en-te:1002`; retained as empty prediction |
| Combined corpus chrF++ | **44.47**, sacreBLEU 2.5.1, word order 2 |
| All-attempt median / mean | **2,188 / 2,516 ms** |
| All-attempt interpolated sample p95 | **4,422 ms** |
| Slowest attempt | **17,508 ms**, the generation-limit failure |
| Fresh native engine initialization | **8,584 ms**, separate from translation |
| Model-byte verification | **2,724 ms**, separate from initialization |

These are uncached x86 cloud CPU measurements with two intra-op/one inter-op
threads. File caches were not flushed. They exclude mic, recognition, voice,
UI and transport, and do not include a new memory measurement. This different
corpus cannot establish a latency improvement over prior short-prompt timings.
No phone speed is extrapolated from them.

chrF++ is lexical reference similarity, **not percent accuracy**. Its combined
corpus value pools n-gram statistics; it is not an arithmetic mean of pair scores.
The failed empty hypothesis remains in pooled recall and its pair score. Two
examples per pair are unstable and do not support language rankings or confidence
claims. FLORES article text differs from conversational speech; upstream model
training contamination is unknown. Runtime completion is not semantic correctness.

Raw evidence is in [corpus.json](evaluation/corpus.json),
[results.json](evaluation/results.json), [summary.json](evaluation/summary.json)
and [timing-summary.json](evaluation/timing-summary.json). Attribution is in
[evaluation/README.md](evaluation/README.md); reproduction is in
[FLORES_EVALUATION.md](FLORES_EVALUATION.md). The recorded commit/dirty flag and
source/class hashes identify the measured runtime. Later integration/cleanup
changes do not change NLLB inference, weights or tokenizer; the native-core class
hash is checked against the final build separately.

### Observed discrepancies and reference imperfections

These are reference-relative observations, not a bilingual human adequacy audit:

- `mr-en:109`: a reference describing lying on the ground in an apparently heavily medicated state becomes output containing “a roadside drug gangsta bed.”
- `ta-en:987`: the English reference says the concept came from China and mentions plum blossoms; output says Sinai and loses plum.
- `te-en:298`: the reference has Martelly swearing in the council; output has Martelli being sworn into it. A role difference can survive numeric checks.
- `or-hi:255`: the source and aligned English text describe three further bombs over two hours, while the Hindi reference says two bombs; the model's Hindi output retains three. A reference mismatch here does not establish a model error.
- `or-hi:233`: the source includes Lakha Singh, which its Hindi reference omits; the output retains him.

Scores and references remain unchanged. Numeric gating and reviewed phrases
improve user control and exact known-case reuse; they **do not improve or certify
the general model's measured translation accuracy**. Names, negation, roles,
destinations and reference anomalies need bilingual review and a larger locked
evaluation before changing models/decoding or claiming quality improvement.

## Five core product differentiators

1. Focused ten-language Indic offline conversation, with all 90 directions explicitly exercised.
2. Local Wi-Fi/Bluetooth text transport and receiver-side processing without an online conversation service.
3. User control over recognized source and observable numeric risks before speech.
4. Exact reviewed phrase continuity without loading the large model on resource-limited hardware.
5. Recoverable typed/voice output, retained provenance and independently reproducible evidence.

The coherent combination is the product proposition. Offline conversation,
phrasebooks, typing and replay already exist in competitors; worldwide novelty
or superior general accuracy is not claimed.

## Remaining release and evidence limits

General NLLB translation still requires supported 64-bit hardware, roughly 1.4 GB
model storage and substantial free memory. Its model/license/intended-use remains
noncommercial research; ARM64 Extensions libraries still have 4 KiB ELF alignment
and need replacement/rebuild for 16 KiB Android qualification. These are release
gates, not solved by a debug APK.

Physical-device rendering, accessibility, vendor microphone shutdown, AudioTrack,
Bluetooth/Wi-Fi radios, natural speech quality, battery, thermals and phone memory
have not been tested for this build. Historical phone and synthetic ASR/TTS
benchmarks remain labelled in [CURRENT_APP_BENCHMARKS.md](CURRENT_APP_BENCHMARKS.md).
Unrestricted low-RAM translation, zero lag and commercial-release readiness are
not established. The delivered work strengthens usable offline control,
recovery, reliability and measurement while retaining these explicit boundaries.

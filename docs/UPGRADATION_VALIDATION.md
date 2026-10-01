# Upgradation first implementation: validation

## Scope

Offline performance, recording/delivery handling, language setup, and tools for
measuring recognition and translation quality. A translation engine is **not yet
integrated**. Offline/online mode and hold-to-talk/continuous behavior were requested
from the user and remain unresolved. See [UPGRADATION_PLAN.md](UPGRADATION_PLAN.md)
and [TRANSLATION_MODEL_OPTIONS.md](TRANSLATION_MODEL_OPTIONS.md).

## Changes

- `ChunkedSpeaker` preserves the exact text, prefers sentence boundaries, and
  synthesizes only the first chunk before returning audio readiness. It prepares
  at most one more chunk during playback; cancellation also cancels the prefetch.
  Short utterances retain the previous path. No packet-format change.
- `tts_ms` remains first-audio synthesis wait; new CSV fields `tts_total_ms` and
  `voice_chunks` describe completed local synthesis. Historical CSVs are preserved
  using the existing header-migration behavior.
- PINGs stay frequent during active speech and RTT acquisition, then change from
  every 2 s to every 10 s after 10 s idle. The steady idle PING/PONG budget drops
  from 17 B/s to 3.4 B/s (136 to 27.2 bits/s), an 80% reduction. This calculation
  covers application framing only, excluding IP/TCP/Bluetooth overhead.
- ACKs must match sequence, timestamp and language; PONG timestamps must match the
  outstanding probe. Unconfirmed messages expire even if the socket stays connected.
- Closing a transport during socket registration now closes newly accepted sockets
  and prevents a late join attempt. TCP host setup also covers close during binding;
  the Bluetooth accepted-socket path follows the same lifecycle guard.
- Recording shutdown stops the microphone before joining its reader, and engine
  cleanup covers recorder-stop failures. VAD now zero-pads the last partial
  512-sample frame rather than discarding up to 31.9 ms of speech.
- Languages has native-script fonts, selected-language status and direct selection
  for installed speak packs. Speaking language remains locked during a conversation.
  MainScreen collects state with lifecycle awareness. ACK status reads "delivered"
  because it does not establish that an entire utterance was heard.

## Automated checks

- 112 Android JVM tests pass (102 baseline plus ten new tests), including a repeated
  accept/close TCP race regression. Final `testDebugUnitTest assembleDebug` run
  passed in 1m 18s.
- Debug APKs compile/package for arm64-v8a, armeabi-v7a and x86_64.
- Seven Python accuracy-tool tests pass, including numeric/Indic-mark errors,
  corpus-weighted rates, duplicate rejection and coverage of 90 directed pairs.
  The 90-pair fixtures test **the scorer**, not a translation model.

## Measured cloud voice benchmark

Command:

```sh
.venv/bin/python scripts/benchmark_first_audio.py --runs 5 --check-recognition --out dist/latency/first-audio.json
```

Platform: cloud x86_64 Linux CPU, sherpa-onnx 1.13.7, two threads, warm Piper
Hindi model. One fixed three-sentence Hindi passage; medians of five runs.

| Measurement | Before: whole utterance | After: chunks |
| --- | ---: | ---: |
| Synthesis before first audio is ready | 1119.95 ms | 369.07 ms |
| All synthesis | 1119.95 ms | 1065.17 ms |

First-audio synthesis wait decreased **67.0%** in this desktop experiment. Full
and concatenated chunked audio produced identical Hindi recognizer transcripts.
This is a synthesis/intelligibility smoke check; it does not prove ten-language
accuracy, actual phone playback latency, or translation quality. The experiment
excludes model loading, translation, networking and Android AudioTrack setup.

## Quality evaluation workflow

Install the optional scorer with `pip install -r scripts/requirements-validation.txt`.
Prepare JSONL rows from real predictions with `id`, `task`, `source`, `reference`,
`hypothesis` and exact `model` revision; translation rows also require `target`.
Use the same `system` label (app build/settings revision) across language-specific
models so one ten-language system can satisfy the coverage gate.
Then run:

```sh
.venv/bin/python scripts/evaluate_accuracy.py predictions.jsonl --out dist/accuracy/report.json --require-full-coverage
```

Recognition uses corpus-weighted CER/WER plus strict CER/WER. Normalization
preserves Indic marks, numeric signs and decimal separators. These definitions
differ from the earlier FLEURS verifier, so historical rates must not be compared
without rescoring the same predictions. Translation uses standard sacreBLEU chrF
on original strings, independently from recognition scores. The scorer reports
missing languages/pairs per system and fails the coverage gate after writing the
report. Native-speaker review is still required for meaning, names and negation.

## Pending device checks

No physical phone or emulator is attached to this cloud session. Check microphone
stop/release, trailing speech, chunk transitions and gaps, exit during synthesis,
pack deletion during playback, the new language layout at large font sizes,
idle/reconnect behavior and p50/p90/p95 first-audio latency on the intended phones.
Speech chunking can change prosody; compare real speech outputs across all voices
before a release. Continuous recognition and actual translation are subsequent
stages, not completed functionality.

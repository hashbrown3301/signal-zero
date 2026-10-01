# Offline hold-to-talk translation validation

Protocol date: 2026-10-01. This document describes checks to run on the new
translation path. It contains **no measured translation accuracy, Android
translation latency, or phone memory results**. Historical voice-only benchmarks
and automated fake-translator tests do not establish these results.

Implemented runtime and actual **cloud CPU** checks are recorded separately in
[OFFLINE_TRANSLATION_RESULTS.md](OFFLINE_TRANSLATION_RESULTS.md). They include
all 90 directions, exact ten-script tokenization, limited original/optimized output
comparison, and memory experiments; they do not replace the phone/corpus checks below.

## Model, storage, and release scope

The initial runtime candidate is the quantized ONNX export of
[NLLB-200 distilled 600M](https://huggingface.co/Xenova/nllb-200-distilled-600M).
Record the exact repository revision, file hashes, tokenizer, generation settings,
runtime version, and app commit for every evaluation. Check the installed manifest
for exact sizes; budget **roughly 900 MB for the translation pack alone**. Existing
recognition packs, voices, native libraries, temporary downloads, and installation
staging require additional storage. Disk size is not the model's peak RAM usage.
The generated output-layer decoder adds about 475 MB (about 1.4 GB translation
storage in total). First preparation verifies source and output hashes without
loading weights into the Java heap. Keep space for the import ZIP as well.

The [upstream model card](https://huggingface.co/facebook/nllb-200-distilled-600M)
states a research purpose and says the model was not released for production
deployment. Its [CC BY-NC 4.0 license](https://creativecommons.org/licenses/by-nc/4.0/)
requires attribution and restricts use to noncommercial purposes. A permissive
license for this app's source code or the ONNX runtime does not remove the model's
restrictions. Treat this model path as a noncommercial evaluation candidate.
Commercial distribution requires a suitable model license or a replacement model,
with its own accuracy and device checks. Installing the runtime is not a completed
production release.

The supported language configuration to verify is:

| Language | App ISO code | NLLB language tag |
| --- | --- | --- |
| Hindi | `hi` | `hin_Deva` |
| English | `en` | `eng_Latn` |
| Marathi | `mr` | `mar_Deva` |
| Gujarati | `gu` | `guj_Gujr` |
| Bengali | `bn` | `ben_Beng` |
| Tamil | `ta` | `tam_Taml` |
| Telugu | `te` | `tel_Telu` |
| Kannada | `kn` | `kan_Knda` |
| Malayalam | `ml` | `mal_Mlym` |
| Odia | `or` | `ory_Orya` |

There are **90 directed translation pairs**: ten source languages times nine
different targets. Hindi to Tamil and Tamil to Hindi are separate evaluations.
Test ten same-language bypass paths separately; they do not count toward the 90.

## Accuracy corpus and review

Use five examples per directed pair for an initial 450-example smoke check.
That establishes limited coverage, not conversational accuracy. A fuller proposed
evaluation contains at least 100 native-authored conversational prompts per source
language, with independently reviewed translations into each of the other nine
languages: 1,000 source prompts and 9,000 translation prediction/reference rows.
Record dataset licenses and provenance. Keep a fixed held-out test set; do not
select only sentences on which the model already performs well.

For each source language, include ordinary conversation; prices, dates, decimals,
negative values, and units; negation and questions; names and addresses; directions
and tense; colloquial speech; and multi-sentence or code-switched utterances. Vary
length and script. Include examples close to the runtime's input/output limits so
truncation and incomplete translations are visible. Natural native wording matters:
a corpus containing only literal translations from English can hide real use
failures.

Create three distinct evaluations using the same fixed intents:

1. **Recognition:** Record real speech from at least three native speakers per
   source language, including quiet and noisy settings, and compare recognized
   text with a verified transcript. Report all ten languages separately.
2. **Text translation:** Translate the verified source transcript directly. This
   isolates the translation model from microphone and recognition errors.
3. **Complete speech flow:** Hold to talk, then compare the app's translated text
   and audible target speech with the intended meaning. Identify whether an error
   originated in recognition, translation, or synthesis. Synthetic speech can be
   used for repeatable smoke checks but must be reported separately from people.

Retain original transcription, translated text, expected translation, source and
target codes, exact model revision, app/settings revision, voice/recognition pack
versions, device, example ID, and relevant timings. Failed and empty predictions
stay in the dataset; do not drop them to improve reported scores. Preserve Unicode
marks and numeric punctuation.

The existing scorer accepts JSONL rows with `id`, `task`, `source`, `reference`,
`hypothesis`, `model`, and a shared `system` label. Translation rows additionally
require `target`. Set `hypothesis` to the actual output and `model` to the exact
evaluated artifact/revision. Keep the same `system` label for all models forming
one app/settings configuration. Run from the checkout:

```sh
.venv/bin/python -m pip install -r scripts/requirements-validation.txt
.venv/bin/python scripts/evaluate_accuracy.py translation-predictions.jsonl --out dist/accuracy/offline-translation.json --require-full-coverage
.venv/bin/python scripts/evaluate_accuracy.py recognition-predictions.jsonl --out dist/accuracy/offline-recognition.json --require-full-coverage
```

The coverage gate verifies that rows exist for all required languages or pairs;
it is not an accuracy threshold. Report per-pair chrF with its scoring signature,
example counts, and failures. Recognition reports strict and normalized CER/WER.
Do not compare scores across different corpora or normalization rules as if they
were an improvement measurement.

At least one reviewer fluent in each source/target pair should assess adequacy
and target-language fluency; use a second reviewer to adjudicate disagreements
and critical errors. Record omissions, additions, wrong target language, changed
negation, names, quantities, units, and dates separately. A high corpus chrF can
coexist with an unacceptable changed instruction. Establish release thresholds
with reviewers before seeing the held-out results, and publish each pair's result
instead of relying on one ten-language average.

## Offline and hold-to-talk behavior

Install verified translation, recognition, and target-voice packs before
disconnecting the internet. For Solo, enable airplane mode and test after a fresh
app launch. For peer mode, disable cellular and WAN access while keeping only the
required local Wi-Fi/hotspot or Bluetooth connection available. Confirm that
translation and voice playback still work, with no online fallback or account
dependency. Initial pack installation can require internet; conversation must not.

For every language and all 90 directed pairs, check original and translated text,
the selected target, and audible output. The source language must stay attached to
the original packet; translation happens on the receiving phone or in Solo.
Verify that the output contains a natural sentence in the requested language,
rather than only a plausible script or a few translated words.

Run these functional checks on real phones:

| Scenario | Expected behavior |
| --- | --- |
| Press, speak, release | Capture starts on press; recognition runs after release; translation precedes target voice playback. |
| Short accidental tap or silence | Discard the tap or show a clear no-speech notice; do not invent a translation. |
| Same source and target | Play the original with that voice; do not load or invoke translation unnecessarily. |
| Incoming text while the button is held | Keep it queued without speaking over the user; preserve arrival order after release. |
| Translation pack missing, corrupt, or incomplete | Preserve original text and show translation failure; never play the source text through the requested target voice. |
| Unknown source language from a newer peer | Show an explicit translation failure and keep the original; do not guess a source language. |
| Translation succeeds but target voice is absent | Display translated text with a no-voice state; do not fall back to another language's voice. |
| Leave during recording, model load, decoding, synthesis, or playback | Stop recording/audio, cancel queued work, and return to a usable screen without a crash or late audio. |
| Immediately start another session after leaving | No old text, audio, pending decode, or target selection leaks into the new session. |
| Disconnect and reconnect or resend a packet | Preserve original delivery identity; translate and play a delivered message once, and resend its saved ACK when needed. |
| Very long utterance, queue pressure, or slow translation | Explicitly surface limits/failure; do not silently truncate meaning, replay old messages, or stall indefinitely. |
| Rotate, background, return, deny/regrant mic permission | Preserve understandable state and release resources according to the app lifecycle. |

A delivery ACK confirms receipt of text. It does not prove translation success or
that the entire target utterance was heard. When translation or voice fails, there
is no first-audio event; report it as a failure or unavailable measurement, not a
zero-millisecond success. Include a receiver queue exceeding 64 distinct messages
and a translation/held-talk delay exceeding the configured ACK timeout in stress
testing, since bounded duplicate history and delivery expiry can interact with a
slow model.

## Phone latency, memory, and sustained use

Use the intended phones, including the lowest-RAM supported phone, plus a modern
arm64 phone. Record model/SoC, Android version, physical RAM, ABI, free storage,
battery/temperature, app build, CPU thread settings, model pack hashes, and
recognition/voice packs. Cloud desktop numbers do not predict Android performance.
For a 32-bit device, verify address-space limits and whether the runtime loads at
all; APK packaging alone is not proof of inference compatibility.

Measure three conditions separately:

1. **Cold app process:** Force-stop and relaunch with the pack already installed;
   include native runtime initialization and model loading. State whether the OS
   file cache was already warm. A cold app process is not necessarily a cold disk.
2. **Warm model, new utterance:** Warm the model first, then use unique prompts
   that do not hit the translation cache. This is the primary steady conversation
   measurement.
3. **Cache hit:** Repeat an identical prompt and language pair. Report this
   separately so fast cache hits cannot conceal CPU decoding cost.

For each condition, collect at least 30 short and 30 longer turns per device and
representative directions; expand to every directed pair for release validation.
Report p50, p90, p95, maximum, and failed/aborted counts. Include model-load time,
recorder stop, VAD, recognition, translation, target voice loading, first synthesis,
audio startup, completed synthesis, and total playback. Perform sustained tests
of at least ten minutes with unique utterances to expose thermal throttling,
memory growth, and gaps between synthesized chunks.

The user-visible latency is **button release to first audible target speech**.
Use a monotonic clock on one device and record the actual audio-start event; also
cross-check with a recording that captures the release and speaker output.
`translationMs` alone omits recognition, voice loading, and audio setup. A
synthesis-ready timestamp alone omits playback startup. In a two-phone test, use
one synchronized measurement setup or a shared recording; do not subtract
timestamps from unsynchronized phone clocks.

The current protocol includes translation in the ACK's queue stage. The sender's
ACK-minus-half-RTT calculation is an estimate of receiver readiness, not a direct
acoustic measurement. ACK times saturate at the packet format's limit, and delivery
may expire before a slow receiver finishes. Keep externally measured first-audio
latency alongside the app breakdown and state these limitations in reports.

Capture process memory before model load, with translation/recognition/voice
loaded, during decoding and synthesis, and after leaving/re-entering sessions:

```sh
adb shell dumpsys meminfo com.itantra
adb shell cat /proc/meminfo
adb shell dumpsys battery
adb shell dumpsys activity exit-info com.itantra
```

`exit-info` is available on newer Android versions; use appropriate device logs
on older versions. Process PSS/native memory and whole-device available memory
answer different questions. Record peak values and process deaths; do not infer
them from the approximately 900 MB disk footprint. Test low-storage installation,
download interruption/cancellation, partial-file rejection, and recovery without
re-downloading valid files unnecessarily.

Set achievable first-audio, memory, thermal, and accuracy budgets for each supported
device class before release. A CPU implementation may need a smaller model,
different quantization, shorter utterance limits, or a narrower supported-device
list. Report measured limits plainly. Until these checks pass, ten-language wiring
and successful builds support evaluation, not a claim of a lag-free final product.

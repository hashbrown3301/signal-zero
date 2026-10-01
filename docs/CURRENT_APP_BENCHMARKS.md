# Current app benchmarks and historical phone evidence

Report date: 2026-10-01. Branch: `Upgradation`.

**The current offline translation app has not been benchmarked on an Android
phone.** The user has no phones available for this run. Current measurements below
are therefore cloud CPU stage measurements, with separate historical phone results
from older builds. Cloud translation time is not phone latency or the complete
hold-to-talk pipeline.

Historical phases recognized and replayed or relayed the original language; they
did not translate between languages. Their timings cannot be used as current
translation results. RAM size alone does not determine speed: the recorded phones
also have different processors, operating systems and memory conditions.

## Current Android phone matrix

“Unmeasured” means no current-build measurement exists. It does not mean zero
latency, a passed test or a failed test.

| Device / RAM class | Translation eligibility | Current speech recognition / voice | Current translation latency | Current release-to-first-audio p50 / p90 | Current app peak PSS |
| --- | --- | --- | --- | --- | --- |
| Galaxy A03 Core, reported 1.9 GB, 32-bit | Translation engine unsupported; recognition and same-language playback remain available | Unmeasured | Unavailable on this device | Unmeasured | Unmeasured |
| 64-bit phone with nominal 4 GB RAM | Conditional: at least 2.5 GB free after speech models load | Unmeasured; no device supplied | Unmeasured | Unmeasured | Unmeasured |
| Galaxy M21 2021, 6 GB, arm64 | Resource guard must be checked on the device | Unmeasured | Unmeasured | Unmeasured | Unmeasured |
| 64-bit phone with 8 GB RAM | No device supplied; resource guard still applies | Unmeasured | Unmeasured | Unmeasured | Unmeasured |
| Galaxy S25, 12 GB, arm64 | Resource guard must be checked on the device | Unmeasured | Unmeasured | Unmeasured | Unmeasured |

The intended minimum is a nominal 4 GB phone. The actual allocation gate requires
a 64-bit process, no Android low-RAM flag, observed total memory at least
3,000,000,000 bytes, no low-memory condition, and 2,500,000,000 bytes currently
available after speech models have loaded.
**6 GB or more is recommended pending phone measurements.** This policy is a memory
guard, not a tested support or latency guarantee. A 2 GB / 32-bit phone cannot load
this translation engine. The shared source pack is about 899 MB and the generated
decoder adds about 475 MB, roughly 1.4 GB of translation storage before other
speech packs; preparation also needs free workspace.

See [OFFLINE_TRANSLATION_RESULTS.md](OFFLINE_TRANSLATION_RESULTS.md#device-and-release-limits)
for the resource policy and
[OFFLINE_TRANSLATION_VALIDATION.md](OFFLINE_TRANSLATION_VALIDATION.md) for device
acceptance checks. NLLB is a CC BY-NC 4.0 evaluation model; benchmark coverage does
not establish production licensing or general translation quality.

## Current cloud run: environment and provenance

| Field | Recorded value |
| --- | --- |
| Production app commit | `46e68a6017577cdddc1abb1a6d2c4e6cabbced42` (`46e68a6`); added benchmark code and reports, no production changes in this run |
| Translation run start | `2026-10-01T05:14:25.495955145Z` |
| Speech report timestamp | `2026-10-01T05:18:17.465708+00:00` (after model loading) |
| OS / architecture | Linux 6.18.44, x86_64; JDK 17.0.20.1, Python 3.12.14 |
| CPU / effective quota | AMD EPYC 9V74 host; 3 visible logical CPUs, **2-core CPU quota** (`200000 100000`) |
| RAM limit / workspace disk | **8 GiB** cgroup memory; 32 GiB workspace disk, about 23 GiB free when inspected |
| Translation runtime | Actual app Kotlin `NllbRuntime`, desktop ONNX Runtime 1.27.0, Extensions 0.13.0 |
| Speech runtime | Python sherpa-onnx 1.13.7, prepared Hindi app models; not Android wrapper execution |
| Model revision | NLLB-200 distilled 600M int8, `261c31d1a5732c67cdd16d80e8d6088507c7ccea`; verified generated integer output-layer decoder |
| Translation configuration | 2 intra-op / 1 inter-op threads; sequential CPU; BASIC_OPT; arena, memory patterns and prepacking disabled |
| Speech configuration | 2 recognition / 2 synthesis / 1 VAD threads; Android defaults are 4 / 4 / 1 |
| Sampling | 1 excluded warmup per case, then 5 measured samples; engine stages run sequentially to avoid competing native benchmark jobs |

Raw reports retain exact inputs, outputs, model identifiers/hashes, errors,
timings and process memory:

- [benchmarks/current-translation.json](benchmarks/current-translation.json):
  **15 cases / 75 measured inference calls**, five directions × three lengths.
  The app's text-result cache is bypassed; every sample executes inference.
- [benchmarks/current-speech.json](benchmarks/current-speech.json):
  **3 Hindi cases / 15 measured speech pipelines**, using synthetic input audio.

Five-sample nearest-rank p50 is the third sorted value; **p90 and p95 both equal
the observed maximum**. These are descriptive observations, not reliable tail
estimates. Identical-input repeats measure timing variation and add no independent
accuracy coverage. No current phone, emulator, UI frame, battery or thermal result
is inferred from these runs.

## Current cloud translation stage

| Measurement | Current run |
| --- | --- |
| Fresh engine initialization | **5.58 s** |
| Derived decoder already present | Yes; first-install model preparation is excluded |
| First translation after initialization | **431 ms**, English → Hindi, “Please bring water.” |
| Process RSS before / after model load | 0.08 GiB / 1.67 GiB |
| Peak process RSS | **1.91 GiB** |
| Completed cases / measured samples / execution errors | **15 / 75 / 0** |

Initialization creates a fresh engine but operating-system file caches are not
flushed. Peak RSS includes the JVM and test libraries; it is not Android PSS or
whole-app memory with speech engines. Actual UI, recognition, target voice loading,
synthesis, playback, network and the `OfflineTranslator` result cache are excluded.

| Direction | Length | Source words | Samples | p50 | p90 | p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| English → Hindi | Short | 3 | 5 | 394 ms | 423 ms | 423 ms |
| English → Hindi | Medium | 23 | 5 | 1601 ms | 1808 ms | 1808 ms |
| English → Hindi | Long | 46 | 5 | 3667 ms | 5125 ms | 5125 ms |
| Hindi → English | Short | 3 | 5 | 400 ms | 495 ms | 495 ms |
| Hindi → English | Medium | 21 | 5 | 1630 ms | 1646 ms | 1646 ms |
| Hindi → English | Long | 47 | 5 | 3490 ms | 3695 ms | 3695 ms |
| English → Telugu | Short | 3 | 5 | 530 ms | 637 ms | 637 ms |
| English → Telugu | Medium | 23 | 5 | 2061 ms | 2182 ms | 2182 ms |
| English → Telugu | Long | 46 | 5 | 4029 ms | 4166 ms | 4166 ms |
| English → Odia | Short | 3 | 5 | 575 ms | 635 ms | 635 ms |
| English → Odia | Medium | 23 | 5 | 2324 ms | 2482 ms | 2482 ms |
| English → Odia | Long | 46 | 5 | 4139 ms | 4247 ms | 4247 ms |
| English → Tamil | Short | 3 | 5 | 667 ms | 703 ms | 703 ms |
| English → Tamil | Medium | 23 | 5 | 2122 ms | 2146 ms | 2146 ms |
| English → Tamil | Long | 46 | 5 | 4435 ms | 4960 ms | 4960 ms |

Short requests have p50 **0.39–0.67 s**, medium inputs **1.60–2.32 s**, and long
inputs **3.49–4.43 s** across these five directions. The largest observed sample
was **5.12 s**. These phrases are 3 / 21–23 / 46–47 whitespace words, not equal
token lengths across scripts. Do not add their timings to unrelated speech
samples to manufacture an end-to-end result.

This run uses six distinct source texts (three English and three Hindi), reused
across 15 direction/prompt combinations. The earlier all-90-direction short-water
request smoke pass is separately recorded in
[OFFLINE_TRANSLATION_RESULTS.md](OFFLINE_TRANSLATION_RESULTS.md); it is coverage
evidence rather than a ninety-direction accuracy evaluation.

### Translation output review

The five measured outputs in each case were identical, and every call produced
text without a runtime error. That does not establish correct translation:

- Hindi → English long: `रोहन` becomes **“Rohn”**; “near the main entrance”
  becomes “at the main entrance”.
- English → Tamil medium and long: the outputs appear to omit **“to the station”**.
  The medium output begins `தயவுசெய்து நாளை காலை 50 அல்ல, 25 பாட்டில்கள் தண்ணீர் கொண்டு வந்து விடுங்கள்.`
  Native-speaker review is needed before accepting the destination meaning.
- English → Odia medium: the quantity wording `୫୦ ନହେଲେ ୨୫` appears conditional
  (“50, otherwise 25”), rather than an unambiguous “25, not 50”. Flag for native
  review; the long case uses different wording.
- English → Telugu short: `దయచేసి నీరు తీసుకుని.` appears incomplete for “Please
  bring water”. Native-speaker review is required.

All digit-token multisets match, including trivially empty sets for short inputs.
The check normalizes Unicode digits and compares sorted digit groups: it cannot
detect swapped quantity roles, changed signs/decimals/units or reordered time
components, and can flag valid spelled-out numbers. **It is not a semantic number,
negation or accuracy score.** These observations are engineering review, not a
blind native-speaker evaluation or held-out chrF measurement.

## Current cloud Hindi speech stages

Piper Hindi generated one input clip per phrase, linearly resampled from 22,050
to 16,000 Hz. The same clip was reused for all five measured runs. Input synthesis,
accuracy scoring, microphone/button handling, Android scheduling, AudioTrack,
network, translation and actual playback are excluded. TTS speaks the **actual
recognizer transcript**, matching the same-language Solo backend path.

Recognition uses prepared AI4Bharat Hindi IndicConformer int8, voice uses Piper
`hi_IN-priyamvada-medium`, and VAD uses Silero. Exact artifact SHA-256 values and
ported Kotlin logic hashes are in the raw report.

| Measurement | Current run |
| --- | --- |
| Fresh-process VAD / recognition / voice load | 21 / 1606 / 1297 ms; **2.92 s** summed, one observation |
| Process RSS after all model loads | 0.30 GiB |
| Peak process RSS after all speech runs | **0.99 GiB**, including Python/native allocations |
| Completed cases / measured pipelines / failures | **3 / 15 / 0** |

Model-file cache state is unknown. These Python backend figures are separate from
the translation process; they cannot establish combined Android peak PSS. The
2-thread cloud speech setting differs from the app's 4-thread defaults.

All paired values below are **p50 / p95**, in milliseconds. “Samples ready” is
VAD → recognition → first synthesized chunk, rather than audible first speech.

| Hindi phrase / words | VAD-trimmed audio | VAD | Recognition | First voice chunk | Backend first samples ready |
| --- | ---: | ---: | ---: | ---: | ---: |
| Short / 5 | 2.61 s | 17 / 17 ms | 512 / 565 ms | 181 / 215 ms | 709 / 801 ms |
| Medium / 21 | 8.32 s | 49 / 52 ms | 1417 / 1620 ms | 586 / 701 ms | 2057 / 2393 ms |
| Long / 49 | 18.98 s | 114 / 124 ms | 3158 / 4036 ms | 760 / 1254 ms | 4031 / 5267 ms |

| Hindi phrase | Voice chunks | All-chunk synthesis p50 / p95 | Recognition p50 real-time factor |
| --- | ---: | ---: | ---: |
| Short | 1 | 181 / 215 ms | 0.196 |
| Medium | 1 | 586 / 701 ms | 0.170 |
| Long | 2 | 1291 / 1947 ms | 0.166 |

A recognition factor of 0.166 means about 0.166 seconds of elapsed recognition time per
second of speech. Long-phrase chunking makes the first voice samples available
before all synthesis finishes; these measurements do not simulate overlapping
audio playback or prove uninterrupted playback on a phone.

Synthetic-only strict scores retain punctuation; the short phrase's word error
is the missing final danda, not a lost spoken word. Indic spelling/marks and
actual errors also affect the longer phrases. These are diagnostics, not a
natural-speech accuracy benchmark:

| Synthetic Hindi phrase | Strict CER | Strict WER |
| --- | ---: | ---: |
| Short | 3.6% | 20.0% |
| Medium | 2.9% | 14.3% |
| Long | 5.5% | 20.4% |

This is one-language speech timing, not a fresh ten-language recognizer test.
The natural-speaker FLEURS results below remain separate.

## Historical phone results: original-language speech only

These are preserved evidence from 2026-09-26 to 2026-09-28. They predate the
current translation build and are not a prediction of its performance.

| Device | Recorded hardware / OS | Historical recognition | Historical voice synthesis | Historical loading | Historical app RAM |
| --- | --- | --- | --- | --- | --- |
| Galaxy S25, SM-S931B | 12 GB, arm64, Android 16; SoC recorded only as “Snapdragon” | Phase 0 Hindi: 760 ms for 8.49 s speech. Phase 1 Hindi: 180–246 ms for 1.76–2.60 s speech. Phase 3 Tamil: 169–221 ms for a short sentence, duration not recorded | Phase 0 Hindi: 360 ms for the full test sentence. Phase 1 Hindi: 147–160 ms. Phase 3 Tamil MMS: about 400 ms; Hindi Piper: 140 ms | Phase 0 VAD / STT / TTS: 45 / 573 / 502 ms, about 1.1 s. Phase 3 Hindi/Tamil language load: 0.9–1.1 s | Not recorded |
| Galaxy M21 2021, SM-M215G | 6 GB, Exynos 9611, arm64, Android 13 | Hindi: 689 ms for 1.60 s speech; 3,276 ms for 8.00 s speech | Hindi: 663–757 ms for the received short phrases; audio duration not recorded | VAD / STT / TTS: 132 / 3,447 / 2,811 ms, 6.39 s sum | Not recorded |
| Galaxy A03 Core, SM-A032F | Reported 1.9 GB, 32-bit armeabi-v7a, Android 13 Go; SoC not recorded | Phase 3 Tamil: 13.9 s for 3.9 s speech. Hindi: 6.2 s for 1.7 s speech; recognition takes about 3.6–3.7× the audio duration | Tamil MMS: 27 s per sentence. Hindi Piper: 2.9–4.2 s; phrase and audio duration not recorded | Hindi STT + voice: 43 s (22.7 + 19.9 s) | 326–334 MB PSS; 26–40 MB swapped out noted |

Sources: [BENCHMARKS.md](../BENCHMARKS.md), Phase 0 and Phase 1;
[MODELS.md](MODELS.md#on-device-results-phase-3-steps-56-2026-09-28), Phase 3;
[CLAUDE.md](../CLAUDE.md), device notes and Phase 3 status.

Provenance: Phase 0 was debug commit `a55ed40`, 2026-09-26. The preliminary
Phase 1 exchange was `3cc4fc6`, 2026-09-27. Phase 3 on-device findings were recorded
on 2026-09-28; git history records S25 verification in `1f1a3a8` and low-end
findings in `526e0b9`. The Phase 3 table does not preserve an installed APK hash.
Older Phase 2 A03 Core notes reported approximately 24 s loading and Hindi
recognition around real time; these must remain separate from the slower Phase 3
measurements. Phone memory pressure was explicitly noted.

One live 20-word Hindi sentence on the S25 took **1,167 ms from button release
until playback started**: VAD 47 + STT 760 + TTS 360 ms. Recording lasted 9.40 s;
the latency excludes that hold-to-record interval. This was one original-language
sample, not translation or a percentile. The user informally confirmed its
transcript, without a held-out accuracy corpus.

### Historical four-message Wi-Fi exchange

End-to-end is the sender-clock estimate `(ACK received − release) − RTT/2`, not an
acoustic timestamp. There were two messages per direction, all four delivered,
ACKed and played. No translation stage ran. Values below come from the raw CSVs:

| Direction | Speech duration | VAD | Recognition | Other | RTT | Receiver queue | Receiver voice | End-to-end estimate |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| M21 → S25, short | 1.60 s | 59 ms | 689 ms | 122 ms | 49 ms | 0 ms | 147 ms | 1,041 ms |
| M21 → S25, longer | 8.00 s | 325 ms | 3,276 ms | 358 ms | 68 ms | 1 ms | 160 ms | 4,154 ms |
| S25 → M21, short | 1.76 s | 21 ms | 180 ms | 301 ms | 68 ms | 3 ms | 663 ms | 1,202 ms |
| S25 → M21, short | 2.60 s | 35 ms | 246 ms | 77 ms | 85 ms | 3 ms | 757 ms | 1,160 ms |

Raw sources:
[m21_benchmarks.csv](phase1_results/m21_benchmarks.csv), rows 3 and 5;
[s25_benchmarks.csv](phase1_results/s25_benchmarks.csv), rows 3 and 5.
**Observed raw RTT range: 49–85 ms.** The older prose summary reported 49–68 ms
and omitted the S25 row with 85 ms. Payloads were 65–96 B with the older wire
format; they are not current-app bitrate measurements. The separate Solo row in
the S25 CSV is excluded from this four-message exchange.

Historical A03 Core ↔ S25 Bluetooth notes record 6/6 voice messages delivered,
RTT 40–44 ms and setup about 3.1 s; another transport-only run records 4/4 delivered
and RTT about 50 ms. A successful reconnect attempt took 2.5 s. No A03 Core raw
per-message CSV, full release-to-audio timing or reliable percentile is committed.
Mixed Tamil/Hindi playback in Phase 3 relayed the original language rather than
translating it. Full scripted ten-sentence-each-way transport tests remained
pending in those notes.

## Prior natural-speech recognition accuracy

These are model-level desktop/cloud Google FLEURS results dated 2026-09-28, not
per-phone accuracy and not translation accuracy. Three native-speaker clips per
language were tested. The original report cautions that a roughly five-point
change is noise with this sample size.

| Language | Character error rate | Word error rate |
| --- | --- | --- |
| Hindi | 3.9% | 9.3% |
| English | 14.4% | 18.6% |
| Marathi | 6.3% | 20.6% |
| Gujarati | 11.0% | 28.4% |
| Bengali | 5.7% | 17.5% |
| Tamil | 42.7%* | 57.7%* |
| Telugu | 1.1% | 8.6% |
| Kannada | 5.4% | 6.7% |
| Malayalam | 14.2% | 36.8% |
| Odia | 15.3% | 34.3% |

*Tamil: one speaker read the reference twice; transcription of both copies scored
113% CER against the single reference. The other two clips scored 4.5% and 10.6%
CER. A separate ten-clip evaluation reported 19.8% CER. A separate ten-clip English
conformer-small evaluation reported 10.1% CER and 18.0% WER. Source:
[MODELS.md](MODELS.md#step-3-verdicts-real-speech-verification-2026-09-28).

No held-out multilingual translation chrF score or per-phone translation quality
score has been measured. Names, quantities, time and negation require reference
and human review; producing text in all ten languages alone is not proof of
accuracy. The planned larger recognition and phone spot-check runs remain
separate from the current timing run.

## Reproducing the current cloud stages

Hindi stage benchmark:
[scripts/benchmark_speech_stages.py](../scripts/benchmark_speech_stages.py).
Use the project's Python environment and prepared speech assets:

```sh
.venv/bin/python scripts/benchmark_speech_stages.py --runs 5 --hash-models \
  --out dist/benchmarks/current-speech.json
.venv/bin/python -m unittest discover -s scripts/tests -v
```

The script's `--help` describes model paths, repeat count and output options.
Retain explicit failures rather than discarding them. The current helper and
existing Python suite passed **36 tests**; the opt-in native translation benchmark
passed its one JUnit test with **75 measured calls**. Production code is unchanged,
so this run reused the previously built APKs rather than claiming a new phone run.

Translation stage benchmark:
[NllbBenchmarkTest.kt](../app/src/test/java/com/itantra/translation/NllbBenchmarkTest.kt).
It is opt-in and downloads no model during ordinary test runs. Set
`ITANTRA_RUN_NLLB_BENCHMARK=1`, `ITANTRA_NLLB_DIR` to the verified raw pack,
`ITANTRA_ORTX_LIBRARY` to the desktop Extensions library,
`ITANTRA_BENCH_REPEATS=5` and `ITANTRA_BENCH_OUTPUT` to
`docs/benchmarks/current-translation.json`, then run the test through the project's
configured Gradle environment. Record the benchmarked app commit with
`ITANTRA_BENCH_COMMIT`. Linux JVM loading of the Python-packaged Extensions library
also needs the matching Python shared library preloaded; see
[OFFLINE_TRANSLATION_RESULTS.md](OFFLINE_TRANSLATION_RESULTS.md#reproducing-checks).

On this prepared workspace, run the translation benchmark separately from the
speech benchmark:

```sh
export ITANTRA_RUN_NLLB_BENCHMARK=1
export ITANTRA_BENCH_REPEATS=5
export ITANTRA_NLLB_DIR="$PWD/dist/translation/nllb"
export ITANTRA_ORTX_LIBRARY="$PWD/.venv/lib/python3.12/site-packages/onnxruntime_extensions/_extensions_pydll.cpython-312-x86_64-linux-gnu.so"
export ITANTRA_BENCH_OUTPUT="$PWD/dist/benchmarks/current-translation.json"
export ITANTRA_BENCH_COMMIT="$(git rev-parse HEAD)"
export LD_PRELOAD=/opt/codex/runtimes/codex-primary-runtime/dependencies/python/lib/libpython3.12.so
python3 /workspace/tools/run-java-proxied.py bash gradlew \
  --init-script /workspace/tools/cloud-maven.init.gradle testDebugUnitTest \
  --tests com.itantra.translation.NllbBenchmarkTest --rerun-tasks \
  --no-daemon --max-workers=2
```

The Python library path is specific to this cloud's Python 3.12 installation;
other hosts must use their matching shared library. Benchmark reports record the
actual runtime environment and commit rather than treating host settings as
phone specifications.

## Decisions supported by this evidence

Use short hold-to-talk turns and retain loaded models between turns. Long-input
translation alone can take several seconds in the cloud, before recognition and
voice costs. Keeping recording at 16 kHz preserves recognition input quality;
reducing audio bitrate does not speed up the app's existing text transport.

The current model cannot meet the low-end 2 GB requirement. A smaller, suitably
licensed translator would need its own accuracy and device-memory measurements;
more RAM cannot guarantee fast translation on an older CPU. Prioritize the
name, destination and negation defects above in a held-out reference evaluation
before claiming improved accuracy or a finished translation product.

For a future physical-device comparison, use the same APK/model hashes, source
clips, language pairs and phone-state conditions on every device. Record cold
loads separately, exclude warmups, then gather at least 30 warm turns per case
alongside peak PSS, available memory, errors, frame timing, thermal and battery
observations. Capture actual button-release-to-first-audio timing rather than
adding independent stage medians. Keep low-RAM unsupported rows explicit.

Actual current-phone acceptance still needs microphone/voice playback,
Bluetooth/Wi-Fi, available memory and peak PSS, cold model preparation, warm
release-to-first-audio p50/p90, long utterances, thermal behavior, UI frame timing
and held-out recognition/translation quality. Until those runs exist, keep the
current-phone matrix unmeasured.

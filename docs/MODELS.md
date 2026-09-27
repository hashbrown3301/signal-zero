# iTantra models: availability audit (Phase 3 step 1)

Audited 2026-09-28 from the Hugging Face API and the sherpa-onnx GitHub releases (`asr-models`, `tts-models`).
Only metadata was fetched; nothing large was downloaded. Sizes are download sizes.
**"Verified" here means the files exist and their metadata is right. Whether each model actually works is
checked in step 3** (desktop decode/synthesis with a known clip).

## Summary

| Lang | STT | TTS (recommended first; alternatives to compare in step 3) | Est. pack |
|---|---|---|---|
| hi Hindi | IndicConformer ✓ | Piper `hi_IN-priyamvada-medium` ✓ (bundled today) | bundled |
| en English | NeMo CTC (shortlist below) ✓ | Piper `en_US-*` ✓ | ≈ 100–130 MB |
| mr Marathi | IndicConformer ✓ | MMS `mar` (convert ourselves) ✓ | ≈ 170–250 MB |
| gu Gujarati | IndicConformer ✓ | Mimic3 `gu_IN-cmu-indic_low` ✓ / MMS `guj` ✓ | ≈ 170–250 MB |
| bn Bengali | IndicConformer ✓ | Coqui `bn-custom_female` ✓ / Mimic3 `bn-multi_low` ✓ / MMS `ben` ✓ | ≈ 170–250 MB |
| ta Tamil | IndicConformer ✓ | MMS `tam` (convert ourselves) ✓ | ≈ 170–250 MB |
| te Telugu | IndicConformer ✓ | MMS `tel` (convert ourselves) ✓ | ≈ 170–250 MB |
| kn Kannada | IndicConformer ✓ | MMS `kan` (convert ourselves) ✓ | ≈ 170–250 MB |
| ml Malayalam | IndicConformer ✓ | Piper `ml_IN-arjun-medium` / `ml_IN-meera-medium` ✓ | ≈ 160 MB |
| or Odia | IndicConformer ✓ | MMS `ory` (convert ourselves) ✓ | ≈ 170–250 MB |

**Every language has both an STT model and at least one TTS voice.** Nothing is missing.
Pack estimate = STT int8 (137.7 MB) + TTS (Piper int8 ≈ 21 MB compressed; MMS ≈ 108 MB as ONNX fp32, less once quantized).

## STT: 9 Indic languages, AI4Bharat IndicConformer (ONNX by OpenVoiceOS)

Source: `https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx`. Licence: **MIT** (all 9).
All 9 have the same layout as the Hindi model from Phase 0: `config.json` = `nemo-conformer-ctc`, 80 features,
subsampling 4; `vocab.txt` = 257 tokens with `<blk>` last; `model.int8.onnx` = 137.7 MB (+ fp32 `model.onnx` + `model.onnx_data`).
So **Phase 0's metadata patch works for every language unchanged** (`vocab_size=257`, `subsampling_factor=4`,
`normalize_type=per_feature`).

Mislabeling checks (the plan warns about mislabeled folders elsewhere):
- **9 different int8 files:** no two repos share a SHA-256.
- **Each vocabulary is in the expected script** (counting letters in `vocab.txt`; the 6 Latin letters are `<unk>`/`<blk>`).

| Lang | Repo commit | int8 SHA-256 (first 16) | Vocab script | Sample tokens |
|---|---|---|---|---|
| hi | `8960b8611a` | `c2044177b6d44494` | Devanagari ✓ | ▁क ▁स ▁ह ▁म ▁प |
| mr | `b738dbf822` | `df2d4fb0af1a2b39` | Devanagari ✓ | या ्या ▁क ▁आ ▁प |
| gu | `2042e7df6f` | `a8e5612fac4960c3` | Gujarati ✓ | ▁ક મા ▁પ ▁સ વા |
| bn | `46053d8f1c` | `800f1dd216bc85eb` | Bengali ✓ | য় ার ▁ক ▁স ▁ব |
| ta | `10e43940d1` | `b96af48383b0bd4b` | Tamil ✓ | ்க ்த ம் ல் ▁ப |
| te | `d952dec2a1` | `d615acecbfcffca3` | Telugu ✓ | ▁ప ని ార ▁క ్ర |
| kn | `55b6f618ad` | `40f8b318a22bb2f1` | Kannada ✓ | ▁ಮ ▁ಸ ತ್ ಲ್ ▁ಕ |
| ml | `e3c79c0a33` | `e1d1bfd48ecc92d3` | Malayalam ✓ | ന് ക് ത് ▁പ ന്ന |
| or | `d22557deb8` | `175d16b83013d042` | Odia ✓ | ▁କ ▁ସ ▁ପ ାର ▁ବ |

Not used: `trysem/indicconformer-120m-onnx` (mislabeled folders; see `CLAUDE.md`).

## STT: English (sherpa-onnx `asr-models` release, Apache-2.0 packaging; model licences to confirm in step 3)

| Candidate | Size | Engine type in the app | Notes |
|---|---|---|---|
| `sherpa-onnx-nemo-ctc-en-conformer-small` | 76.5 MB | NeMo CTC (**same as Indic, no new code**) | Smallest CTC option |
| `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8` | 104.3 MB | NeMo CTC (same as Indic) | Newer, likely most accurate |
| `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27` | 29.9 MB | Moonshine (new engine type) | Smallest overall |
| `sherpa-onnx-zipformer-small-en-2023-06-26` | 112.2 MB | Transducer (new engine type) | |
| `sherpa-onnx-whisper-tiny.en` | 118.1 MB | Whisper (new engine type) | |

Recommendation: benchmark the two NeMo CTC models first in step 3. If one is good enough, English needs no new
engine code; Moonshine is the fallback if pack size matters more.

## TTS

### sherpa-onnx prebuilt voices (`tts-models` release)

| Lang | Asset | Size | Type | Licence |
|---|---|---|---|---|
| hi | `vits-piper-hi_IN-priyamvada-medium` (bundled) / `-int8` | 67.2 / 21.1 MB | Piper | dataset CC BY-NC-SA 4.0 (from its MODEL_CARD) |
| hi | `vits-piper-hi_IN-pratham-medium`, `…-rohan-medium` (+ int8) | 67 / 21 MB | Piper | check MODEL_CARD |
| en | `vits-piper-en_US-*` (many; e.g. `ljspeech-medium`, `amy-medium`) (+ int8 ≈ 21 MB) | 67 / 21 MB | Piper | per voice; check MODEL_CARD |
| ml | `vits-piper-ml_IN-arjun-medium`, `…-meera-medium` (+ int8) | 67 / 21 MB | Piper | **unclear**: meera's MODEL_CARD says "License: See URL" (IIT Madras Indic TTS corpus on Kaggle) and it is fine-tuned from `en_US-lessac`. Settle in step 3, or use MMS `mal` instead |
| gu | `vits-mimic3-gu_IN-cmu-indic_low` | 80.0 MB | Mimic3 (VITS) | check MODEL_CARD |
| bn | `vits-coqui-bn-custom_female` | 108.1 MB | Coqui (VITS) | check MODEL_CARD |
| bn | `vits-mimic3-bn-multi_low` | 79.9 MB | Mimic3 (VITS) | check MODEL_CARD |

No prebuilt sherpa-onnx voice was found for **mr, ta, te, kn, or**.

### Meta MMS originals (convert to sherpa-onnx ourselves)

Source: `https://huggingface.co/facebook/mms-tts-<iso>`. Licence: **CC-BY-NC-4.0** (all). Each is ≈ 145 MB of fp32
weights (the repos hold both `.safetensors` and `.bin`, ≈ 291 MB together). sherpa-onnx's own converted MMS voices
(e.g. `vits-mms-eng`) are ≈ 108 MB, which is the expected size after conversion (less with int8).

| Lang | Repo | `is_uroman` | Vocab script |
|---|---|---|---|
| mr | `facebook/mms-tts-mar` | false | Devanagari |
| bn | `facebook/mms-tts-ben` | false | Bengali |
| ta | `facebook/mms-tts-tam` | false | Tamil |
| te | `facebook/mms-tts-tel` | false | Telugu |
| kn | `facebook/mms-tts-kan` | false | Kannada |
| or | `facebook/mms-tts-ory` | false | Odia |
| gu | `facebook/mms-tts-guj` | false | Gujarati |
| ml | `facebook/mms-tts-mal` | false | Malayalam |
| hi | `facebook/mms-tts-hin` | false | Devanagari |
| en | `facebook/mms-tts-eng` | false | Latin |

`is_uroman=false` for all 10: **the voices take native-script text directly**, so no romanization step is needed on the phone.

**Not used:** `sriram09764/itantra-tts-onnx`. Its name suggests another SIH iTantra team's set, and its licence and
provenance are unknown. We convert Meta's originals ourselves with sherpa-onnx's published MMS export script, so
every file traces back to its original source.

## Licence summary (for the README and the pitch)

| Component | Licence | Commercial use |
|---|---|---|
| IndicConformer STT (9 languages) | MIT | yes |
| sherpa-onnx runtime | Apache-2.0 | yes |
| MMS TTS voices (Meta) | CC-BY-NC-4.0 | **no** (attribution required) |
| Piper `hi_IN-priyamvada` | dataset CC BY-NC-SA 4.0 | **no** |
| Piper `en_US-ljspeech` voice | public domain dataset | yes |
| English STT (NVIDIA NeMo conformer-small) | CC-BY-4.0 per NVIDIA's model card | yes, with attribution |
| ~~Piper `en_US-lessac`~~ (not used) | Blizzard 2013: research-only, no redistribution | no |

Fine for SIH (non-commercial). A commercial deployment would need different voices.

## Built packs (Phase 3 step 2, draft release `packs-v1`)

| Pack type | Size (unpacked) | Notes |
|---|---|---|
| Indic speak (hi mr gu bn ta te kn ml or) | 137.7 MB each | IndicConformer int8 + metadata patch |
| en-speak | 46.4 MB | `nemo-ctc-en-conformer-small` |
| MMS listen (mr gu bn ta te kn ml or) | 57.6–57.7 MB each | exported from Meta's checkpoints, fp16 weights (step 3) |
| Piper listen (hi, en) | 18.6 / 19.4 MB | int8; reuse the app's `espeak-ng-data` |

Rough totals on a phone: speak one Indic language + hear all 10 ≈ 138 + 19 + 19 + 8 × 58 ≈ **640 MB**;
speak one + hear 2–3 ≈ **250 MB**.

Smoke-test round trips (our own voice → our own STT, test phrase "Hello, how are you?"): ta 2.9% CER, bn 0.0%,
en 7.1% ("hullo how are you"). This proves the models load and agree with each other; real-speech accuracy is step 3/8.

## Step 3 verdicts (real-speech verification, 2026-09-28)

**Chosen models** (source of truth: `scripts/packs/languages.json`):

| Lang | Speak (STT) | Listen (voice) |
|---|---|---|
| hi | IndicConformer | Piper `hi_IN-priyamvada-medium` int8 (18.6 MB) |
| en | NeMo `conformer-small` (46.4 MB) | Piper `en_US-ljspeech-medium` int8 (19.4 MB, **public domain**) |
| mr gu bn ta te kn ml or | IndicConformer (137.7 MB) | MMS, **fp16 weights** (≈ 57.6 MB) |

**Real speech** (Google FLEURS test clips, native speakers; 3 clips per language, so ±5 points is noise; step 8 is the real benchmark):

| | hi | en | mr | gu | bn | ta | te | kn | ml | or |
|---|---|---|---|---|---|---|---|---|---|---|
| CER | 3.9% | 14.4% | 6.3% | 11.0% | 5.7% | 42.7%* | 1.1% | 5.4% | 14.2% | 15.3% |
| WER | 9.3% | 18.6% | 20.6% | 28.4% | 17.5% | 57.7%* | 8.6% | 6.7% | 36.8% | 34.3% |

\* One Tamil clip is a 25 s recording where the speaker read the sentence **twice**; the model transcribed both copies
correctly, which counts as 113% CER against the single reference. The other two clips: 4.5% and 10.6%. On 10 clips,
Tamil is 19.8% CER; worth a closer look in step 8. Some "errors" everywhere are digits vs spoken numbers ("100" / "सौ")
and code-mixed words ("pH"), the gotchas the plan predicts. STT runs at RTF ≈ 0.14 on a 2-vCPU cloud runner.

**Decisions and why:**
- **MMS voice format: fp16 weights, fp32 compute.** Tamil, 10 sentences: int8 = 38.0 MB but RTF 1.0–1.3 (slower than
  real time, because ONNX Runtime runs int8 convolutions slowly); fp32 = 114.0 MB, RTF 0.26; **fp16 weights = 57.6 MB,
  RTF 0.27**, same clarity (round trip 8.1% vs 9.0% int8 / 10.7% fp32, within noise). ONNX Runtime turns the weights back
  into fp32 once at load time, so RAM while loaded is still about 114 MB.
- **English STT: conformer-small** (46.4 MB, CER 10.1%, WER 18.0% on 10 clips) over Parakeet TDT-CTC 110M (131.7 MB,
  CER 8.7%, WER 12.6%): nearly 3× smaller for a small accuracy cost.
- **Malayalam voice: MMS** instead of Piper `meera`: round trip 6.8% vs 21.6%, and a clear licence (meera's is "see URL",
  fine-tuned from `lessac`).
- **English voice: `ljspeech`** instead of `lessac`: lessac's dataset (Blizzard 2013) is **research-only with no
  redistribution**; LJ Speech is public domain.
- **Listening check:** the user listened to the full-sentence samples of every language: all clear.

## Open questions for later steps
- **Step 3:** actually decode a known clip per language (catches a model/vocabulary mismatch the metadata can't),
  compare Bengali/Gujarati voice options, pick the English STT, confirm the remaining licences from each MODEL_CARD.
- **Step 3:** try int8 quantization of the converted MMS voices (≈ 108 MB → ≈ 30–40 MB?) and check quality.
- **Freeze** exact repo commits and SHA-256s in `pack.json` before submission (the commits above are today's).

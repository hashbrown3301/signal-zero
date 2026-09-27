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
| ml | `vits-piper-ml_IN-arjun-medium`, `…-meera-medium` (+ int8) | 67 / 21 MB | Piper | check MODEL_CARD |
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
| Other Piper / Mimic3 / Coqui voices, English STT | per MODEL_CARD, to be confirmed in step 3 | – |

Fine for SIH (non-commercial). A commercial deployment would need different voices.

## Open questions for later steps
- **Step 3:** actually decode a known clip per language (catches a model/vocabulary mismatch the metadata can't),
  compare Bengali/Gujarati voice options, pick the English STT, confirm the remaining licences from each MODEL_CARD.
- **Step 3:** try int8 quantization of the converted MMS voices (≈ 108 MB → ≈ 30–40 MB?) and check quality.
- **Freeze** exact repo commits and SHA-256s in `pack.json` before submission (the commits above are today's).

# Translation model selection

Research snapshot: 2026-09-30. The app has speech recognition and synthesis packs,
but no translation runtime. These candidates are not installed or validated in
the app. Do not present them as completed ten-language translation support.

## Offline candidate: IndicTrans2 distilled

Official models (model cards report MIT; API metadata checked at the revisions below):

| Direction | Model | Revision | Download access |
| --- | --- | --- | --- |
| Indic -> Indic | [indictrans2-indic-indic-dist-320M](https://huggingface.co/ai4bharat/indictrans2-indic-indic-dist-320M) | `ffb7582b6d43791f1fb26b2153fc065f2e9ea575` | Gated, automatic approval after access terms |
| English -> Indic | [indictrans2-en-indic-dist-200M](https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M) | `173b94239f7c38886b2747b8d4a5db771a7e1232` | Gated, automatic approval after access terms |
| Indic -> English | [indictrans2-indic-en-dist-200M](https://huggingface.co/ai4bharat/indictrans2-indic-en-dist-200M) | `eb9e49d81077cfc5311e82ff36d8c1fc11557b5d` | Gated, automatic approval after access terms |

Audit each model card's language tags and official preprocessing before export.
Use direct Indic -> Indic translation rather than compulsory English pivoting.
The repositories do not supply ONNX files in their listed artifacts. Required work:
reproducible export, tokenization/preprocessing on Android, incremental decoding,
quantization comparison, model pack manifests and checksums, and device RAM/latency
trials. Load only the model for the current direction; do not load all three plus
all voices on a low-RAM phone.

Access approval must happen in the user's Hugging Face account. No credential is
configured in this cloud session; do not request that tokens be pasted into chat.

## Public ONNX candidate: NLLB-200 distilled 600M

[Xenova/nllb-200-distilled-600M](https://huggingface.co/Xenova/nllb-200-distilled-600M),
revision `261c31d1a5732c67cdd16d80e8d6088507c7ccea`, has public ONNX encoder/decoder
artifacts including quantized variants. The model is CC BY-NC 4.0 and has a larger
600M-parameter footprint. Verify its model-card intended use and all ten language
tags; weigh noncommercial restrictions, tokenizer integration, decoding cost and
Android memory against IndicTrans2 before choosing it. An ONNX download alone is
not an Android integration or a quality benchmark.

## Online alternative

If the user allows online translation, evaluate an authenticated provider with
explicit support for all ten languages and deploy credentials on a backend, never
inside an APK. Include network time and offline failure behavior in latency tests.
Keep original and translated text separate, support same-language bypass, and
test all 90 directed pairs. Do not use an undocumented public translation endpoint
or imply that Google ML Kit supports a language without checking its actual list.

## Selection gate

Select a candidate only after the offline/online decision, then record exact bytes,
licenses, language coverage, corpus chrF, human checks of numbers/negations/names,
and first-token/end-to-end latency on the intended phones. No translation accuracy
or lag-free claim is supported yet.

iTantra language packs, built by `.github/workflows/build-packs.yml` from `scripts/packs/`.
The app downloads (or you sideload) these once; after that each language works fully offline.

- **`<lang>-speak.zip`**: speech-to-text for a language you speak.
- **`<lang>-listen.zip`**: a voice for a language you want to hear.
- **`index.json`**: the catalogue (sizes, SHA-256, licences). Every pack also carries its own `pack.json` with its
  sources and exact versions. Full audit: `docs/MODELS.md` in the repository.

## Attribution and licences

| Packs | Model | Author / source | Licence |
|---|---|---|---|
| hi mr gu bn ta te kn ml or `-speak` | IndicConformer (int8 ONNX, metadata added by `scripts/fetch_models.py`) | AI4Bharat; ONNX export by OpenVoiceOS (`OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx`) | MIT |
| en `-speak` | NeMo `stt_en_conformer_ctc_small` (sherpa-onnx export) | NVIDIA; sherpa-onnx (k2-fsa) | CC-BY-4.0 |
| mr gu bn ta te kn ml or `-listen` | MMS-TTS (VITS), exported by `scripts/packs/export_mms.py` with fp16 weights | Meta AI, Massively Multilingual Speech (`dl.fbaipublicfiles.com/mms/tts/`) | **CC-BY-NC-4.0** |
| hi `-listen` | Piper `hi_IN-priyamvada-medium` (int8, sherpa-onnx) | Piper voice trained on an IndicTTS-based dataset; sherpa-onnx conversion | **CC BY-NC-SA 4.0** (dataset) |
| en `-listen` | Piper `en_US-ljspeech-medium` (int8, sherpa-onnx) | Piper; LJ Speech dataset | public domain (dataset) |

Export code used for the MMS voices: VITS by Jaehyeon Kim et al. (`github.com/jaywalnut310/vits`, MIT), following
sherpa-onnx's VITS exporter (Apache-2.0).

**Non-commercial:** the MMS voices and the Hindi voice may only be used non-commercially, with attribution
(and share-alike for the Hindi voice). A commercial deployment would need different voices.

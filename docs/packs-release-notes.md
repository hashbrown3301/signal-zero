iTantra language packs, built by `.github/workflows/build-packs.yml` from `scripts/packs/`.

- **`<lang>-speak.zip`**: speech-to-text for a language you speak (AI4Bharat IndicConformer, **MIT**; English: NVIDIA NeMo).
- **`<lang>-listen.zip`**: an int8 voice for a language you want to hear.
- **`index.json`**: the catalogue (sizes, SHA-256, licences). Every pack also carries its own `pack.json` with sources and commits.

**Licences:** MMS voices (Meta) are **CC-BY-NC-4.0**, and the Hindi Piper voice's dataset is **CC BY-NC-SA 4.0**:
non-commercial use only, with attribution. Other voices: see each pack's `pack.json` / `MODEL_CARD`.
Source audit: `docs/MODELS.md`.

# iTantra Phase 3 plan: all 10 SIH languages

## Goal
Every phone can speak and hear all 10 SIH languages: Hindi, English, Marathi, Gujarati, Bengali, Tamil, Telugu,
Kannada, Malayalam, Odia. Each phone picks its own language; the receiver speaks whatever language the packet says.

**This is not translation.** If phone A speaks Tamil and phone B is set to Hindi, B **hears Tamil**, spoken by a Tamil
voice. The project rule "no translation feature" still holds.

**Exit criterion:** for every language, a spoken sentence is transcribed in the correct script, sent to the second phone
(Wi-Fi or Bluetooth), and spoken back intelligibly. Character error rate (CER) is measured per language on a fixed test set.

---

## Design decisions

### STT models (see `docs/MODELS.md` for the audit)
| Languages | Model | Source | Licence |
|---|---|---|---|
| 9 Indic | AI4Bharat IndicConformer, one int8 model per language (NeMo CTC, 257 tokens) | `OpenVoiceOS/ai4bharat-indicconformer-<lang>-onnx` + Phase 0's metadata patch | MIT |
| English | NeMo CTC first (`nemo-ctc-en-conformer-small` or `parakeet_tdt_ctc_110m-en`), Moonshine tiny as fallback | sherpa-onnx releases | per model card |

- The Phase 0 patch script becomes data-driven (language code in, pack out); the audit showed that all 9 Indic
  models need exactly the same metadata.
- Never use `trysem/indicconformer-120m-onnx` (mislabeled folders).
- Verify every model with a known clip in its language **before** it goes into Android.

### TTS voices
- **Piper** where a voice exists: Hindi, English, Malayalam.
- **Mimic3 / Coqui** prebuilt by sherpa-onnx: Gujarati, Bengali (compared against MMS in step 3).
- **Meta MMS**, converted by us from `facebook/mms-tts-<iso>` with sherpa-onnx's MMS export script: Marathi, Tamil,
  Telugu, Kannada, Odia. All MMS voices take native-script text (`is_uroman=false`), so no romanization is needed.
- **Not** `sriram09764/itantra-tts-onnx` (another team's set, unknown licence/provenance).
- MMS is **CC-BY-NC-4.0**: fine for SIH, and it must be stated in the licence table and pitch.

### Packet language codes (fixed forever)
The packet's `lang` byte exists since Phase 1, but the decoder only knows Hindi and **rejects anything else as a corrupt
packet, which drops the link**. Phase 3 fixes both sides:

| Code | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Lang | hi | en | mr | gu | bn | ta | te | kn | ml | or |

- Codes are never renumbered; new languages get new codes.
- An unknown code decodes as "unknown language" (text shown + install prompt), **not** as a bad packet.

### Language packs (keep the APK small)
- Hindi stays bundled in the APK, **but is described by the same `pack.json`** as every other pack (no Hindi-only code).
- Every other language = a **pack**: STT model + tokens + TTS voice + `pack.json` (language code, STT type, TTS type,
  file list, SHA-256, licence, source URLs and commits).
- Installed once, then fully offline:
  - in-app download with progress + SHA-256 check, **or**
  - `adb push` sideload for offline provisioning (demo-day safety net).
- Stored in app-specific storage; nothing in Kotlin names a specific language. The manifest drives everything.
- **Hosting (decide at step 7):** the STT models need our metadata patch, so packs have to be hosted by us (e.g. a
  GitHub Release of the project repo). The licences allow redistribution with attribution; NC voices stay NC.
- **Per-CPU APKs** (ABI splits, step 4): today's APK is 296 MB because it carries three CPU builds; one APK per CPU
  brings each to about 230–250 MB.

### Engine management (RAM)
- Load **one STT** (the phone's own language) at a time.
- Load TTS voices lazily when a packet in that language arrives; keep at most **2 voices** in memory (LRU), and **1 on
  Android "low RAM" devices** if step 6's measurements need it (the A03 Core reports low RAM and already uses about
  370 MB with Hindi STT + TTS).
- Target: stay under about 450 MB app RAM on a low-end phone.

### Receiver behaviour
- The packet's `lang` decides the voice.
- If the receiver lacks that language's pack → show the text + an "Install <language> voice" prompt. Never speak with the wrong voice.

---

## Steps (test after each, then commit)

| # | Step | Test |
|---|---|---|
| 1 | **Availability audit:** STT model + TTS voice per language, with URL, size and licence in `docs/MODELS.md` | Table complete for all 10 (**done**) |
| 2 | Generalise `fetch_models.py` + patch script to take a language code; convert MMS voices; build packs into `dist/packs/<lang>/` with `pack.json` | Script builds all 10 packs on the PC |
| 3 | **Desktop verification** (Python, `.venv`): each pack decodes a known clip and synthesises a sentence; pick English STT and the bn/gu voices; try int8 MMS | Correct script + audible voice for all 10 |
| 4 | Language codes in `PacketCodec` (+ unknown-language handling, tests); pack manager: list, install from file (adb push), SHA-256 verify, delete; per-CPU APKs | Sideload Tamil pack, app lists it |
| 5 | Language picker + engine factory driven by `pack.json` (STT: nemo_ctc / moonshine / …; TTS: piper / mms / …) | Switch Hindi ↔ Tamil ↔ English on the S25 |
| 6 | Lazy TTS loading + LRU cache + missing-pack fallback; RAM measured on the A03 Core | Receive a packet in an uninstalled language → text + prompt, no crash |
| 7 | In-app download (hosted packs) with progress + retry | Install a pack over Wi-Fi, then airplane mode still works |
| 8 | **Accuracy benchmark:** 20 FLEURS sentences per language, CER/WER on desktop; 3 sentences per language spot-checked on the phone | Results table in `BENCHMARKS.md` |
| 9 | Two-phone cross test over **both Wi-Fi and Bluetooth**: phone A in language X, phone B in language Y, each hears the other's language | 10 × 1 sentence round-trip logged per transport |

---

## Gotchas
- **Use CER, not just WER**, for Indic languages; word boundaries vary, especially for Tamil, Malayalam, Telugu, Kannada.
- **Numbers and English words** inside Indic speech (code-mixing) will come out oddly. Note it, don't chase it in this phase.
- **Odia and Bengali** voices may be slower (higher TTS RTF). Measure, don't assume.
- **APK size:** Hindi bundled + app ≈ 230–250 MB per CPU type; each downloaded pack ≈ 100–250 MB.
- Record every model's **exact file name, version (repo commit) and SHA-256**. Freeze versions before submission.
- Test recordings and transcripts must not contain personal details before they're committed (the repo is public).

---

## Metrics to record
- Per language: STT CER/WER, STT RTF, TTS RTF, TTS time-to-first-audio, pack size, RAM with the pack loaded
- Device + Android version for every number

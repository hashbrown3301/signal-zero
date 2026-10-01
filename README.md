# iTantra

Offline multilingual hold-to-talk voice translation app for Smart India Hackathon **SIH26173 (ISRO)**.
Native Kotlin + Jetpack Compose, minSdk 26. Everything runs on the phone, with no internet.

**Phase 0 (done):** hold to talk → Silero VAD → IndicConformer Hindi STT → transcript on screen → Piper Hindi TTS,
with per-stage timings on screen. About 1.2 s from releasing the button to hearing the reply for a 20-word sentence on a Galaxy S25.
See [BENCHMARKS.md](BENCHMARKS.md).

**Phase 1 (in progress):** two phones exchanging text over Wi-Fi. See [docs/PHASE1_PLAN.md](docs/PHASE1_PLAN.md).

**Upgradation:** lower first-audio delay through chunked voice synthesis, quieter idle
links, stronger delivery checks, and direct language selection. The ten-language
translation plan is in [docs/UPGRADATION_PLAN.md](docs/UPGRADATION_PLAN.md).
**Fully offline hold-to-talk translation is integrated:** select **Speak** and **Hear**,
hold for a phrase, then release to recognize, translate and play the target voice.
Incoming Wi-Fi/Bluetooth messages are translated on the receiving phone. Original
and translated text are shown separately. Install the shared translation pack and
the desired recognition/voice packs first; ZIP import supports entirely offline setup.
Tests and the cloud voice benchmark are
documented in [docs/UPGRADATION_VALIDATION.md](docs/UPGRADATION_VALIDATION.md).
Translation device/quality acceptance is in
[docs/OFFLINE_TRANSLATION_VALIDATION.md](docs/OFFLINE_TRANSLATION_VALIDATION.md).
Actual model/runtime cloud checks are in
[docs/OFFLINE_TRANSLATION_RESULTS.md](docs/OFFLINE_TRANSLATION_RESULTS.md).

## Models

| Stage | Model | Source | License |
|---|---|---|---|
| VAD | Silero VAD | [sherpa-onnx asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | MIT |
| STT | AI4Bharat IndicConformer Hindi (int8 ONNX) | [OpenVoiceOS/ai4bharat-indicconformer-hi-onnx](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-hi-onnx) | MIT |
| Translation | NLLB-200 distilled 600M (int8 ONNX, shared across all 10 languages) | [Xenova/nllb-200-distilled-600M](https://huggingface.co/Xenova/nllb-200-distilled-600M) | CC BY-NC 4.0; upstream intended for research, not production |
| TTS | Piper `hi_IN-priyamvada-medium` | [sherpa-onnx tts-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) | dataset CC BY-NC-SA 4.0 (**non-commercial**) |

Runtime: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.7 (Apache-2.0).
Translation uses ONNX Runtime Java 1.27.0 with sherpa's shared native runtime 1.27.1,
and ONNX Runtime Extensions 0.13.0 for exact SentencePiece tokenization.

The STT model ships without the metadata sherpa-onnx needs, so `scripts/fetch_models.py` adds
`vocab_size`, `subsampling_factor`, `normalize_type` and `model_type` to it.

## Setup (Windows)

Requires the Android SDK, Android Studio's bundled JDK, and Python 3.12. From the repo root:

```powershell
# 1. Python env + models (~250 MB download; the AAR and models are not in git)
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r scripts\requirements.txt
.venv\Scripts\python.exe scripts\fetch_models.py

# Optional: prepare the ~900 MB translation pack for importing without internet on the phone
.venv\Scripts\python.exe scripts\fetch_translation_model.py --zip

# 2. Optional: check the models on the PC (TTS a Hindi sentence, then STT it back)
.venv\Scripts\python.exe scripts\test_models_pc.py

# 3. Build and install on a USB-connected phone (USB debugging on)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk   # 32-bit phones: app-armeabi-v7a-debug.apk
adb shell am start -n com.itantra/.MainActivity

# 4. Logs
adb logcat -s iTantra:* AndroidRuntime:E
```

`local.properties` needs `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk` (forward slashes).

## Notes

- A real phone is recommended. The x86_64 emulator is supported, but it needs roughly 16 GB of PC RAM to run alongside Gradle.
- The debug APK is about 240 MB per CPU type because the Hindi models are bundled as uncompressed assets.
- The shared translation pack is 899,478,308 bytes (~858 MiB) and stays outside the APK.
  On first use the app creates a verified optimized decoder (~475 MB); translation uses ~1.4 GB storage.
  It supports all 90 directed pairs. The app loads one model lazily and caches repeat translations;
  it requires a 64-bit phone with at least 4 GB RAM and 2.5 GB free memory to load.
  Six GB RAM or more is recommended until device trials are complete. Two-GB low-RAM phones
  keep recognition/same-language playback but cannot load this translation model.
  Short phrases reduce decoding latency. No lag-free or improved-accuracy claim has been established.
- In **Languages**, download the translation pack once or use **Import ZIP** with the generated
  `dist/translation/nllb-200-distilled-600m-int8-v1.zip`. Setup downloads verify pinned SHA-256s.
  Conversation inference performs no network calls, including when internet is unavailable.
- ONNX Runtime Extensions' published 0.13.0 ARM64 libraries have 4 KB alignment. A rebuilt
  16 KB-compatible runtime and physical-device testing are required before a Play release.
- Other languages are installed as packs from the **Language packs** screen: tap **Download** (needs internet
  once; packs come from the public release https://github.com/hashbrown3301/signal-zero/releases/tag/packs-v1),
  or without internet use **Import pack…** or `adb push <pack>.zip /sdcard/Android/data/com.itantra/files/incoming/`.
  After installing, every language works fully offline. Packs are built by `.github/workflows/build-packs.yml`;
  attribution and licences are in the release notes.

## License

The code in this repository is MIT-licensed (see [LICENSE](LICENSE)), © 2026 Signal Zero.
The models are **not** included and keep their own licenses (see [Models](#models)); in particular the Piper
Hindi voice's dataset is CC BY-NC-SA 4.0 (non-commercial).

# iTantra

Offline Hindi voice communication app for Smart India Hackathon **SIH26173 (ISRO)**.
Native Kotlin + Jetpack Compose, minSdk 26. Everything runs on the phone, with no internet.

**Phase 0 (done):** hold to talk → Silero VAD → IndicConformer Hindi STT → transcript on screen → Piper Hindi TTS,
with per-stage timings on screen. About 1.2 s from releasing the button to hearing the reply for a 20-word sentence on a Galaxy S25.
See [BENCHMARKS.md](BENCHMARKS.md).

**Phase 1 (in progress):** two phones exchanging text over Wi-Fi. See [docs/PHASE1_PLAN.md](docs/PHASE1_PLAN.md).

## Models

| Stage | Model | Source | License |
|---|---|---|---|
| VAD | Silero VAD | [sherpa-onnx asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | MIT |
| STT | AI4Bharat IndicConformer Hindi (int8 ONNX) | [OpenVoiceOS/ai4bharat-indicconformer-hi-onnx](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-hi-onnx) | MIT |
| TTS | Piper `hi_IN-priyamvada-medium` | [sherpa-onnx tts-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) | dataset CC BY-NC-SA 4.0 (**non-commercial**) |

Runtime: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.7 (Apache-2.0).

The STT model ships without the metadata sherpa-onnx needs, so `scripts/fetch_models.py` adds
`vocab_size`, `subsampling_factor`, `normalize_type` and `model_type` to it.

## Setup (Windows)

Requires the Android SDK, Android Studio's bundled JDK, and Python 3.12. From the repo root:

```powershell
# 1. Python env + models (~250 MB download; the AAR and models are not in git)
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r scripts\requirements.txt
.venv\Scripts\python.exe scripts\fetch_models.py

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
- Other languages are installed as packs (Language packs screen → Import pack…, or `adb push <pack>.zip
  /sdcard/Android/data/com.itantra/files/incoming/`). Packs are built by `.github/workflows/build-packs.yml`.

## License

The code in this repository is MIT-licensed (see [LICENSE](LICENSE)), © 2026 Signal Zero.
The models are **not** included and keep their own licenses (see [Models](#models)); in particular the Piper
Hindi voice's dataset is CC BY-NC-SA 4.0 (non-commercial).

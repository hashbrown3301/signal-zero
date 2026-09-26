# iTantra

Offline Hindi voice assistant for Smart India Hackathon problem **SIH26173 (ISRO)**. Native Kotlin + Jetpack Compose, package `com.itantra`, minSdk 26.

## Phase status

- [x] **Phase 0: offline voice loop** (done 2026-09-26). Hold to talk → Silero VAD → IndicConformer Hindi STT → transcript on screen → Piper Hindi TTS playback, with VAD/STT/TTS/total timings on screen. About 1.2 s total for a 20-word sentence on a Galaxy S25. See `BENCHMARKS.md`.

## Rules

- No translation feature.
- Don't copy code from other SIH26173 GitHub repos (no licenses).
- STT model: `OpenVoiceOS/ai4bharat-indicconformer-hi-onnx`. Do **not** use `trysem/indicconformer-120m-onnx` (mislabeled).
- Work step by step: explain what and why, and wait for the user's OK before each step.

## Layout

- `app/src/main/java/com/itantra/`
  - `MainActivity.kt`, `MainViewModel.kt` (pipeline + timings), `ui/MainScreen.kt`
  - `audio/AudioRecorder.kt`: 16 kHz mono capture
  - `speech/VadTrimmer.kt`, `SttEngine.kt`, `TtsEngine.kt`, `AssetCopier.kt`: sherpa-onnx wrappers
- `scripts/fetch_models.py`: downloads the sherpa-onnx AAR and all models, and patches the STT model with sherpa-onnx metadata
- `scripts/test_models_pc.py`: desktop TTS → STT round-trip check
- Models (`app/src/main/assets/{vad,stt,tts}`) and `app/libs/*.aar` are gitignored; recreate them with the fetch script.

## Commands (Windows, from the repo root)

```
.venv\Scripts\python.exe scripts\fetch_models.py      # one-time: AAR + models
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s iTantra:* AndroidRuntime:E
```

## Environment notes

- The dev PC has about 8 GB RAM, which is too little for the emulator. Test on the USB-connected Galaxy S25.
- Gradle is capped at `-Xmx1536m` with in-process Kotlin compilation (`gradle.properties`).
- Use the project `.venv` for Python. Don't install into global Python (it breaks other packages via protobuf).

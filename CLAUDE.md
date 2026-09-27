# iTantra

Offline Hindi voice assistant for Smart India Hackathon problem **SIH26173 (ISRO)**. Native Kotlin + Jetpack Compose, package `com.itantra`, minSdk 26.

## Phase status

- [x] **Phase 0: offline voice loop** (done 2026-09-26). Hold to talk → Silero VAD → IndicConformer Hindi STT → transcript on screen → Piper Hindi TTS playback, with VAD/STT/TTS/total timings on screen. About 1.2 s total for a 20-word sentence on a Galaxy S25. See `BENCHMARKS.md`.
- [ ] **Phase 1: two phones, text over Wi-Fi.** Working end to end; the **exit test is on hold** and will run together with Phase 2 step 5. Plan: `docs/PHASE1_PLAN.md`.
  - [x] Steps 1–6: PacketCodec, TcpTransport, PC fake peer, SessionManager, Host/Join/Solo UI, RTT + end-to-end latency
  - [x] Preliminary S25 ↔ Galaxy M21 run over the S25 hotspot: 4/4 delivered, RTT 49–68 ms (see `BENCHMARKS.md`)
  - [ ] Step 7 exit test (**on hold**): 10 sentences each way (`docs/phase1_sentences.md`), then pull both `benchmarks.csv` files and finish the write-up. A free 9-message conversation on 2026-09-27 went 9/9 but isn't the scripted run.
  - [x] Host card labels IPs as hotspot vs Wi-Fi (via Android's joined-Wi-Fi interfaces) and shows the hotspot first
  - [x] Both phones run the current build (`013d50a`)
- [ ] **Phase 2: Bluetooth transport (RFCOMM).** Plan: `docs/PHASE2_PLAN.md`. Don't change `PacketCodec`, `SessionManager` or the speech code.
  - [x] Step 0: plan doc; framing extracted into `comm/FramedStream.kt` (shared by TCP and Bluetooth)
  - [x] Step 1: permissions (`BLUETOOTH_CONNECT` only), "Bluetooth off" prompt, paired-device list (phones first)
  - [x] Step 2: `bluetooth/BluetoothTransport` (host/join, shared FramedStream); A03 Core ↔ S25: 4/4 delivered, RTT about 50 ms
  - [ ] Step 3: Wi-Fi/Bluetooth toggle, full voice loop over Bluetooth
  - [ ] Step 4: reconnect after going out of range
  - [ ] Step 5: 10 sentences over Wi-Fi and Bluetooth, comparison in `BENCHMARKS.md` (also closes Phase 1)

## Rules

- No translation feature.
- Don't copy code from other SIH26173 GitHub repos (no licenses).
- STT model: `OpenVoiceOS/ai4bharat-indicconformer-hi-onnx`. Do **not** use `trysem/indicconformer-120m-onnx` (mislabeled).
- Work step by step: explain what and why, and wait for the user's OK before each step.

## Layout

- `app/src/main/java/com/itantra/`
  - `MainActivity.kt`, `MainViewModel.kt` (loads engines, runs one session), `BenchmarkLog.kt` (`files/benchmarks.csv`)
  - `ui/`: `MainScreen.kt` (router), `HomeScreen.kt` (Host/Join/Solo), `SessionScreen.kt` (chat + timings)
  - `audio/AudioRecorder.kt`: 16 kHz mono capture
  - `speech/VadTrimmer.kt`, `SttEngine.kt`, `TtsEngine.kt`, `AssetCopier.kt`: sherpa-onnx wrappers
  - `comm/`: `Packet`, `PacketCodec` (17 B overhead + CRC32), `FramedStream` (shared framing), `Transport`, `TcpTransport`, `LocalAddresses` (plain Kotlin, no Android)
  - `session/SessionManager.kt`: the only place speech meets the network (queue while talking, ACKs, PING/RTT)
- `app/src/test/`: JUnit for codec, TCP transport and SessionManager (`gradlew testDebugUnitTest`, runs on the PC)
- `scripts/fetch_models.py`: downloads the sherpa-onnx AAR and all models, and patches the STT model with sherpa-onnx metadata
- `scripts/test_models_pc.py`: desktop TTS → STT round-trip check
- `scripts/fake_peer.py`: PC stand-in for the second phone (`adb forward`/`reverse`, `--watch FILE` for scripted sends)
- Models (`app/src/main/assets/{vad,stt,tts}`) and `app/libs/*.aar` are gitignored; recreate them with the fetch script.

## Commands (Windows, from the repo root)

```
.venv\Scripts\python.exe scripts\fetch_models.py      # one-time: AAR + models
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s iTantra:* AndroidRuntime:E
adb shell am start -n com.itantra/.MainActivity --es mode host        # or: --es mode join --es peer <ip>
adb shell run-as com.itantra cat files/benchmarks.csv > benchmarks.csv # pull per-message timings
```

## Environment notes

- The dev PC has about 8 GB RAM, which is too little for the emulator. Test on the USB-connected Galaxy S25.
- Gradle is capped at `-Xmx1536m` with in-process Kotlin compilation (`gradle.properties`).
- Use the project `.venv` for Python. Don't install into global Python (it breaks other packages via protobuf).
- Android 15+ lists the phone's own hotspot (e.g. `swlan0`) as a WIFI network flagged `LOCAL_NETWORK` with no WifiInfo. Don't treat it as joined Wi-Fi.
- With the screen off, Android cuts a backgrounded app's network after about 70 s. The session screen keeps the screen on during Host/Join.
- Second test phone: Galaxy M21 2021 (SM-M215G, Android 13, arm64). It's much slower (STT about 2.4× real time vs 11× on the S25).
- Third test phone: Galaxy A03 Core (SM-A032F, Android 13 Go, **32-bit armeabi-v7a**, 1.9 GB RAM). Models load in about 24 s; STT is about 1× real time. The APK includes armeabi-v7a for it (about 296 MB).

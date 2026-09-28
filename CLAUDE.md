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
- [ ] **Phase 2: Bluetooth transport (RFCOMM).** Working end to end (steps 0–4 done); the **exit test is on hold** (step 5, shared with Phase 1). Plan: `docs/PHASE2_PLAN.md`. Don't change `PacketCodec`, `SessionManager` or the speech code.
  - [x] Step 0: plan doc; framing extracted into `comm/FramedStream.kt` (shared by TCP and Bluetooth)
  - [x] Step 1: permissions (`BLUETOOTH_CONNECT` only), "Bluetooth off" prompt, paired-device list (phones first)
  - [x] Step 2: `bluetooth/BluetoothTransport` (host/join, shared FramedStream); A03 Core ↔ S25: 4/4 delivered, RTT about 50 ms
  - [x] Step 3: full voice loop over Bluetooth (A03 Core host ↔ S25, 6/6 delivered, RTT 40–44 ms); CSV `link` + `setup_ms` columns; Bluetooth setup about 3.1 s
  - [x] Step 4: reconnect after a drop, with a "Link lost / Reconnected" banner and `files/links.csv`. S25 ↔ A03 Core: lost, then back automatically (successful attempt 2.5 s). Max distance not measured yet.
  - [x] Step 5 prep: `scripts/summarize_benchmarks.py` (tables, losses, WER); app also logs SENT so unacked messages count as lost. Both phones (S25, A03 Core) have this build.
  - [ ] Step 5 (**on hold**, user will run it later): 10 sentences each way over Wi-Fi and Bluetooth, then `summarize_benchmarks.py --pull`, write up in `BENCHMARKS.md`, and mark Phases 1 and 2 done
- [ ] **Phase 3: all 10 SIH languages** (hi en mr gu bn ta te kn ml or). **Features done (steps 1–7); steps 8–9 (accuracy benchmark, two-phone cross test) on standby.** Plan: `docs/PHASE3_PLAN.md`; models: `docs/MODELS.md`. Mixed languages are **not** translation: the receiver hears the sender's language.
  - [x] Step 1: availability audit. All 9 IndicConformer models exist (MIT, unique files, correct scripts); TTS via Piper (hi, en, ml), Mimic3/Coqui (gu, bn), and MMS converted by us from `facebook/mms-tts-*` (mr, ta, te, kn, or). Don't use `sriram09764/itantra-tts-onnx`.
  - [x] Step 2: `scripts/packs/` (build_pack, export_mms, smoke_test, make_index, `languages.json`) + `.github/workflows/build-packs.yml` → **draft** release `packs-v1`. Lightweight: speak pack = STT (≈138 MB), listen pack = int8 voice (≈18–40 MB, Piper reuses the app's espeak-ng-data). All 20 packs (10 languages × speak/listen) are in the draft release `packs-v1`. Sizes: Indic speak 137.7 MB, `en-speak` 46.4 MB, MMS listen 38.0 MB, Piper int8 listen 18.3–18.6 MB (hi, en, ml). Round trips: ta 2.9% CER, bn 0.0%, en 7.1%.
  - [x] Step 3: real-speech verification in CI (`verify-packs.yml`, `pack-experiments.yml`; FLEURS clips). CER 1–6% for hi te kn bn mr; see `docs/MODELS.md`. Decisions: MMS voices as **fp16 weights** (57.6 MB, RTF 0.27 vs int8's 1.0–1.3), English STT conformer-small, Malayalam voice → MMS, English voice → Piper `ljspeech` (public domain; `lessac` is research-only). User listened: all voices clear.
  - [x] Step 3 wrap-up: the 9 changed listen packs rebuilt and smoke-tested (MMS fp16 57.6–57.7 MB, en ljspeech 19.4 MB)
  - [x] Step 4: language codes 1–10 in `Packet` (unknown codes decode instead of dropping the link); `packs/` pack manager (`PackStore`: zip install with SHA-256 + zip-slip checks, atomic replace; `PackRepository`: built-in Hindi from `assets/builtin/`, adb sideload folder, file-picker import); "Language packs" screen; per-CPU APKs (arm64 241.5 MB). S25: Tamil packs installed in about 3 s, damaged copy rejected.
  - [x] Step 5: language picker + engine factory driven by `pack.json` (`speech/EngineFactory`: built-in = assets, installed = files); "I speak" picker (one STT loaded at a time); outgoing packets tagged with the phone's language; `Status.NO_VOICE` (text shown, still ACKed). S25: switching hi ↔ ta takes 0.9–1.1 s; Solo in Tamil transcribes in Tamil script and speaks back with the Tamil MMS voice (TTS about 0.4 s). 62 PC tests.
  - [x] Step 6: voices load on demand (`speech/VoiceCache`, LRU: 2 voices, 1 on Android low-RAM phones; own voice not pinned since it's only needed in Solo); "Install <language> voice →" prompt on text-only messages opens Language packs (session keeps running). Two phones over Bluetooth: A03 Core (Tamil) ↔ S25 (Hindi), each spoke the other's language; A03 Core 326–334 MB PSS (< 450 MB target). 67 PC tests. Not verified on a phone: the install prompt; the int8 Hindi voice comparison (`hi-listen.zip` still waits in the A03 Core's sideload folder).
  - Finding: the **A03 Core is below a usable spec**: STT 3.6–3.7× slower than real time, MMS Tamil voice 27 s per sentence, loading Hindi 43 s. Recommended minimum: 64-bit phone with ≥ 4 GB RAM. Low-end speed-ups (threads, lighter voices) are future work.
  - [x] Step 7: packs are **public** (release `packs-v1`, published by `.github/workflows/publish-packs.yml` with attribution notes); Language packs lists **all 10 languages** from a catalogue bundled in the APK (`assets/catalog/index.json`, refreshable online); `packs/PackDownloader` downloads with resume (HTTP Range), SHA-256 check, then the normal `PackStore` install; mobile-data warning. Verified: English downloaded on the S25 and the A03 Core, then used in airplane mode (STT 70 ms on the S25). Cancel/resume only verified by PC tests. 73 PC tests.
  - [ ] Step 8 (**standby**): accuracy benchmark (20 FLEURS sentences per language, CER/WER via `verify-packs.yml` with clips=20; 3 per language spot-checked on the phone)
  - [ ] Step 9 (**standby**): two-phone cross test, phones in different languages, over Wi-Fi and Bluetooth (core already seen working in step 6)

- [ ] **UI redesign** (design canvas "iTantra UI", a private claude.ai artifact: Home · Talk · Solo · Languages · Metrics · Alert, Logo, System). Dark navy + teal, no chat bubbles, link status green/yellow/red, amber only for alerts.
  - [x] A: mockup approved (Connect is the Home screen; iTantra header + logo on every screen; Solo tab)
  - [x] B: `ui/theme/` (Material 3 colour scheme, type scale, shapes; bundled Manrope + Noto Sans per script + JetBrains Mono, OFL licences in `assets/licenses/`; `scriptFont(iso)` picks the Noto family for Indian-script text), `ui/components/` (Primary/SecondaryButton, HoldToTalkButton with 4 states, LinkMotif/LinkStatusLabel, ITantraMark/Wordmark/BrandHeader), adaptive launcher icon, navy window theme. APK +1.5 MB.
  - [ ] C: restyle the screens to the design, with bottom navigation (Home · Talk · Languages · Metrics). Speech and network code untouched.
    - [x] Part 1: `ui/MainScreen` tabs (session keeps running across tabs; Talk opens by itself when a link first connects or Solo starts; End returns Home), `HomeScreen` (brand header, language chip, Wi-Fi / Bluetooth / Solo tabs, hosting/connecting progress with animated yellow dots), `TalkScreen` (was SessionScreen: link header green/yellow/red, transcript lines without bubbles, tap a line for timings, numbers row, HoldToTalkButton), `MetricsScreen` (p50/p90 from this session's messages, message sizes, app PSS, pack sizes, FLEURS accuracy table), `SessionUi.kt` (state → labels). Solo verified on the S25.
    - [ ] Part 2: Languages screen (all 10 in their own scripts; select, download, delete; replaces the Home dropdown and the old packs screen)
  - [ ] D (later, separate steps): Phone mode, Alert, Metrics screen

**Pending test day** (all tooling ready): Phase 1+2 exit run (10 sentences each way, Wi-Fi + Bluetooth, `summarize_benchmarks.py`), Phase 3 steps 8–9, and the step 6 leftovers (install prompt, int8 Hindi voice on the A03 Core).

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
  - `packs/`: `PackManifest` (pack.json), `PackStore` (install/verify/delete, plain Kotlin), `PackRepository` (Android: built-in, sideload, import)
  - `comm/`: `Packet`, `PacketCodec` (17 B overhead + CRC32), `FramedStream` (shared framing), `Transport`, `TcpTransport`, `LocalAddresses` (plain Kotlin, no Android)
  - `session/SessionManager.kt`: the only place speech meets the network (queue while talking, ACKs, PING/RTT)
- `app/src/test/`: JUnit for codec, TCP transport and SessionManager (`gradlew testDebugUnitTest`, runs on the PC)
- `scripts/fetch_models.py`: downloads the sherpa-onnx AAR and all models, and patches the STT model with sherpa-onnx metadata
- `scripts/test_models_pc.py`: desktop TTS → STT round-trip check
- `scripts/fake_peer.py`: PC stand-in for the second phone (`adb forward`/`reverse`, `--watch FILE` for scripted sends)
- `scripts/packs/`: language-pack builder (`languages.json` is the per-language source of truth; MMS export runs only in CI)
- `.github/workflows/build-packs.yml`: manual cloud build of packs → draft release (packs are never committed; `dist/` is gitignored)
- `.github/workflows/publish-packs.yml`: makes the pack release public and checks every asset downloads without a login
- `scripts/summarize_benchmarks.py`: `--pull DIR` copies every connected phone's CSVs; then it prints the `BENCHMARKS.md` tables for the scripted sentences (`--save-filtered DIR` keeps only those rows, safe to commit)
- Models (`app/src/main/assets/{vad,stt,tts}`) and `app/libs/*.aar` are gitignored; recreate them with the fetch script.

## Commands (Windows, from the repo root)

```
.venv\Scripts\python.exe scripts\fetch_models.py      # one-time: AAR + models
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk   # per-CPU APKs; A03 Core: app-armeabi-v7a-debug.apk
adb logcat -s iTantra:* AndroidRuntime:E
adb shell am start -n com.itantra/.MainActivity --es mode host        # or: --es mode join --es peer <ip>
adb shell run-as com.itantra cat files/benchmarks.csv > benchmarks.csv # pull per-message timings
adb push ta-listen.zip /sdcard/Android/data/com.itantra/files/incoming/  # sideload a pack (use PowerShell: Git Bash mangles /sdcard paths)
```

## Environment notes

- The dev PC has about 8 GB RAM, which is too little for the emulator. Test on the USB-connected Galaxy S25.
- Gradle is capped at `-Xmx1536m` with in-process Kotlin compilation (`gradle.properties`).
- Use the project `.venv` for Python. Don't install into global Python (it breaks other packages via protobuf).
- Android 15+ lists the phone's own hotspot (e.g. `swlan0`) as a WIFI network flagged `LOCAL_NETWORK` with no WifiInfo. Don't treat it as joined Wi-Fi.
- With the screen off, Android cuts a backgrounded app's network after about 70 s. The session screen keeps the screen on during Host/Join.
- Second test phone: Galaxy M21 2021 (SM-M215G, Android 13, arm64). It's much slower (STT about 2.4× real time vs 11× on the S25).
- Third test phone: Galaxy A03 Core (SM-A032F, Android 13 Go, **32-bit armeabi-v7a**, 1.9 GB RAM). Models load in about 24 s; STT is about 1× real time. The APK includes armeabi-v7a for it (about 296 MB).

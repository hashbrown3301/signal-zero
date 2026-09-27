# iTantra benchmarks

## Phase 0: offline Hindi voice loop (2026-09-26)

### Device

| | |
|---|---|
| Phone | Samsung Galaxy S25 (SM-S931B) |
| OS | Android 16 (API 36) |
| ABI | arm64-v8a |
| RAM | 12 GB |
| Build | debug APK, commit `a55ed40` |

### Stack

| Stage | Model | Runtime config |
|---|---|---|
| VAD | Silero VAD (`silero_vad.onnx`) | 512-sample window, threshold 0.5, 1 thread |
| STT | AI4Bharat IndicConformer Hindi, int8 (`OpenVoiceOS/ai4bharat-indicconformer-hi-onnx`) | NeMo CTC, greedy search, 80 mel bins, 4 threads |
| TTS | Piper `vits-piper-hi_IN-priyamvada-medium` (22,050 Hz) | 4 threads |

Engine: sherpa-onnx 1.13.7 (onnxruntime 1.27.1).

### Full pipeline (hold to talk → VAD → STT → TTS)

Test sentence: a 20-word Hindi sentence spoken live by the user. Transcript produced:

> मेरा नाम [नाम] है और मैं मैं [उम्र] साल का हूँ क्या आप मुझे आपका नंबर दे सकते हो क्या

The user confirmed the transcript was accurate. (Name and age replaced with placeholders.)

| Recorded | Speech after VAD | VAD | STT | TTS | **Total** |
|---|---|---|---|---|---|
| 9.40 s | 8.49 s | 47 ms | 760 ms | 360 ms | **1167 ms** |

*Total* = time from releasing the button until playback starts (VAD + STT + TTS). STT ran at about 11× real time.

### Model load (once, at app start)

| VAD | STT | TTS | Total |
|---|---|---|---|
| 45 ms | 573 ms | 502 ms | ~1.1 s |

The TTS figure excludes the one-time espeak-ng-data copy on first launch (146 ms).

### Per-stage tests

| Test | Input | Result |
|---|---|---|
| VAD only | 4.00 s recording | 2.46 s speech kept, 1 segment, 23 ms |
| VAD + STT | 5.00 s recording → 3.69 s speech | VAD 29 ms, STT 301 ms: "क्या आप मुझे कॉल कर सकते हो" (correct) |
| TTS only | "नमस्ते, मैं आईतंत्र हूँ। आज मौसम बहुत अच्छा है।" | 211 ms for 4.47 s of audio (~21× real time) |

### Desktop reference (PC sanity check, `scripts/test_models_pc.py`)

Intel i5-1235U laptop, 2 threads: TTS 243 ms for 2.76 s of audio, STT 787 ms; the transcript matched the input exactly.

## Phase 1: two phones over Wi-Fi hotspot, preliminary run (2026-09-27)

> **Preliminary.** A short 4-message exchange, not the full exit test (10 sentences each way from
> `docs/phase1_sentences.md`). The full run is still to do. Raw data: `docs/phase1_results/m21_benchmarks.csv`
> and `docs/phase1_results/s25_benchmarks.csv`.

### Setup

| | Host | Joiner |
|---|---|---|
| Phone | Samsung Galaxy S25 (SM-S931B) | Samsung Galaxy M21 2021 (SM-M215G) |
| OS | Android 16 | Android 13 |
| SoC / RAM | Snapdragon, 12 GB | Exynos 9611, 6 GB |
| Model load (VAD / STT / TTS) | 44 / 608 / 518 ms | 132 / 3447 / 2811 ms |

Link: TCP over the S25's mobile hotspot, host IP `10.242.62.95` (read from `swlan0`, not `192.168.x.1`).
Build: commit `3cc4fc6`.

### Results

**M21 → S25** (measured on the M21's clock: end-to-end = (ACK received − release) − RTT/2)

| Seq | Transcript | Recorded / speech | Bytes vs raw audio | VAD | STT | Other | Net (RTT/2) | S25 queue | S25 TTS | **End-to-end** |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | मैं ठीक हूँ धन्यवाद | 1.9 s / 1.6 s | 68 B vs 58 KB (875×) | 59 | 689 | 122 | 24 | 0 | 147 | **1041 ms** |
| 1 | मौसम खराब है तेज़ हवा चल रही है | 11.6 s / 8.0 s | 96 B vs 363 KB (3866×) | 325 | 3276 | 358 | 34 | 1 | 160 | **4154 ms** |

**S25 → M21** (measured on the S25's clock)

| Seq | Transcript | Recorded / speech | Bytes vs raw audio | VAD | STT | Other | Net (RTT/2) | M21 queue | M21 TTS | **End-to-end** |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | नमस्ते आप कैसे हैं | 1.8 s / 1.8 s | 65 B vs 55 KB (866×) | 21 | 180 | 301 | 34 | 3 | 663 | **1202 ms** |
| 1 | क्या आप मेरी आवाज सुन पा रहे हो | 2.7 s / 2.6 s | 96 B vs 84 KB (900×) | 35 | 246 | 77 | 42 | 3 | 757 | **1160 ms** |

- **Delivery:** 4 of 4 messages delivered, ACKed and played, with no losses or reconnects.
- **RTT over the hotspot:** 49–68 ms (vs 3–6 ms over USB with the PC fake peer).
- **The receiving phone's speed decides how fast you hear the reply.** The S25 synthesizes in about 150 ms; the M21 needs 660–760 ms for similar sentences.
  So S25 → M21 (about 1.2 s) is limited by the M21's TTS, and M21 → S25 (1.0–4.2 s) by the M21's STT.
- **On the M21, STT dominates:** 3.3 s for 8 s of speech (about 2.4× real time) versus about 11× real time on the S25.
  End-to-end latency on budget phones is therefore driven by STT, not the network (the network was 24–34 ms here).
- **"Other" is higher on the M21** (122–358 ms vs about 50 ms on the S25). This is the time around recording stop and
  hand-off on a slower CPU; worth profiling before the full run.

### Still to do for the exit criterion
- The full run: 10 sentences from `docs/phase1_sentences.md` each way, then STT accuracy against the reference text,
  and median/min/max end-to-end per direction.

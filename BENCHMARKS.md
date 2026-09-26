# iTantra benchmarks

## Phase 0: offline Hindi voice loop (2026-09-26)

### Device

| | |
|---|---|
| Phone | Samsung Galaxy S25 (SM-S931B) |
| OS | Android 16 (API 36) |
| ABI | arm64-v8a |
| RAM | 12 GB |
| Build | debug APK, commit `29eaaca` |

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

The user confirmed the transcript was accurate.

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

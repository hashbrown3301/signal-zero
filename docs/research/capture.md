# Capture and local logging reliability audit (research only)

Branch inspected: `Upgradation`. No project files edited. Audit date: 2026-10-01 UTC. Hardware behavior is not claimed to have been tested.

## Actual defects and effects

| Source | Finding | User effect | Feasible fix |
| --- | --- | --- | --- |
| `audio/AudioRecorder.kt:16–18,57–58,81` | `chunks` grows without a duration/sample limit and converts the whole capture to floats only at release. A ten-minute press holds ~19.2 MB of shorts plus ~38.4 MB of output floats, besides object overhead and inference state. | Long or accidentally stuck presses waste memory and run unbounded VAD/STT; especially harmful on low-RAM phones. | Cap capture at an explicit 30 seconds (480,000 samples at 16 kHz), fail with a short retry instruction when that limit is exceeded; do not silently translate a truncated recording. Keep the controller independent of Android for deterministic tests. |
| `audio/AudioRecorder.kt:56–58` | Negative `AudioRecord.read` results are ignored. A dead or invalid recorder can return errors continuously while `running` remains true. | CPU hot loop, misleading listening state, missing recognition failure explanation. | Treat negative read returns as terminal capture failures; retain the failure until `stop`, release the resource and report the actual failure. Do not retry `ERROR_DEAD_OBJECT` on the same object. |
| `audio/AudioRecorder.kt:71–76` | `reader?.join()` has no deadline; `stop` is called from non-suspending listener cancellation, including short presses and session leave. The comment claiming “at most one read” is stronger than the implementation or API guarantees. | A driver that fails to unblock read can freeze the UI forever. | Stop first, use a bounded join deadline, release in a finally block, and use recording-owned state so an old reader cannot append to a new recording. Report timeout and allow future capture safely. Do not claim real Android cancellation latency from JVM tests. |
| `audio/AudioRecorder.kt:26,51–53,67–78` | `record`/`reader` ownership is not synchronized; two stop calls can own the same handle. Chunks are shared across captures. | Double stop/release; an old reader can contaminate a newer capture if ownership is ever concurrent. | Atomically detach one capture at stop; guard starting and capture ownership; store thread, buffer, error and running flag together per capture. |
| `session/DeviceSpeech.kt:51–53` | `cancel` uses `recorder.isRecording`, which means reader running, rather than handle owned. When robust error handling stops the reader, this check would skip resource cleanup. | Microphone handle may remain allocated after an error. | Make cancel call an idempotent cleanup method unconditionally. Preserve `stop` for recognized audio and expose discard/cancel that avoids the float-array conversion. |
| `MainViewModel.kt:654–676`, `BenchmarkLog.kt:34–52` | Every conversation is logged as plaintext in logcat and `files/benchmarks.csv`, including original and translated text. Neither CSV has retention or an explicit opt-in. | An offline product retains sensitive conversation content beyond the visible session, and debug tools/backups can expose it. | Root should remove text content from production logs. Make benchmark transcript recording a debug-only explicit opt-in or redact text/error content by default. Bound/rotate performance CSV files. |

## Small testable boundary

Prefer a pure Kotlin `PcmCapture`/`PcmSource` adapter, rather than mocking static Android APIs or adding a large capture framework. Android `AudioRecorder` remains the public API and creates a small `AudioRecord` source; the controller owns bounded short chunks, one worker thread, capture failure, idempotent stop/discard and bounded shutdown. Pure JVM fake sources can deterministically verify:

1. Positive samples are preserved and PCM16 normalization remains `short / 32768f`.
2. A negative read terminates with an explicit error and does not repeatedly call read.
3. Exceeding the sample bound stops acquisition, bounds retained samples and rejects truncated recognition.
4. Calling stop or discard twice releases once; a failed start also releases once.
5. A reader blocked until stop is called finishes; a fake permanently blocked reader returns a timeout within the configured deadline.
6. An old delayed reader cannot write samples into a new capture.
7. Discard skips float conversion and error reporting while ensuring resource release.

Do not add tests that merely restate constant values. Real mic permission, vendor read cancellation, device-specific record state, UI threading and AudioTrack behavior still require Android/hardware testing later.

## Primary references inspected

- Android `AudioRecord` API reference: https://developer.android.com/reference/android/media/AudioRecord . Documents `READ_BLOCKING` as waiting until all requested audio has been read, `ERROR_DEAD_OBJECT` as an invalid object that must be recreated, and `getMinBufferSize` errors/initialization requirements. Local fetched source: `/workspace/scratch/android-audiorecord-reference.html`.
- Android log information disclosure guidance: https://developer.android.com/privacy-and-security/risks/log-info-disclosure . Supports limiting sensitive production log content; does not justify claiming logcat is publicly readable on modern Android. Local fetched source: `/workspace/scratch/android-logging-privacy.html`.

## Integration cautions

The session agent owns press/review and close handling; coordinate the `Listener.cancel` behavior before changing DeviceSpeech. Root owns MainViewModel and can address logcat and benchmark opt-in wiring. A capture length cap is a reliability bound, not a translation latency or accuracy benchmark. If a driver ignores both stop and release, bounded cancellation avoids an infinite UI wait but cannot guarantee that driver resources are reclaimed before the process exits.

# Session and offline peer reliability audit

Read-only phase-1 review of `/workspace/signal-zero`, branch `Upgradation`.
No application edits, native benchmarks, or heavy tests were run for this audit.
Findings below are source inspection and proposed reproductions, not claims of
failures measured on a phone. Public sources were fetched during this review.

## Existing strengths to preserve

The app already separates `Listener`, `Translator`, `Speaker`, and `Transport`.
The receiver uses a serial playback worker, preserves source text, translates
before selecting the target voice, and avoids source-language voice fallback.
Same-language playback bypasses translation. Failures and unavailable voices
preserve text and send a delivery ACK. Translation cancellation resets its busy
flag. Saved ACKs handle normal reconnect duplicates, and all in-flight receive
identities now remain present beyond 64 queued messages. Completed ACK history is
bounded to 64. Close and short-tap microphone-stop exceptions have recently been
made cleanup-safe.

Existing tests cover these paths, outgoing ACK identity matching, reconnect resend,
arrival ordering, packet fragmentation, host accept/close races, and leaving during
translation/playback. New work should extend these contracts rather than rewrite
the speech/transport pipeline.

## Concrete findings, ranked

### P1: A cancelled/disposed gesture does not have a safe microphone contract

`ui/components/HoldToTalkButton.kt:90-102` calls `tryAwaitRelease()` and ignores
its Boolean result. If the gesture is cancelled normally, the method returns
false, but the app calls `onPressEnd()` anyway: an unintended partial recording
can be transcribed and sent. If the pointer coroutine is cancelled/disposed while
waiting, `finally` only clears the visual `held` flag and does not stop capture.

The Talk screen can be removed by switching tabs (`ui/MainScreen.kt`); the session
intentionally survives tab changes. There is no microphone cancellation policy on
Activity stop or screen disposal. `collectAsStateWithLifecycle()` only affects
state collection, not the SessionManager/AudioRecord lifetime. A retained
ViewModel can therefore outlive the gesture which started recording.

Proposal: distinguish a completed release from cancellation, add an explicit
`cancelPress()` operation that discards capture without STT/send, and invoke it in
the appropriate gesture `finally`/lifecycle transition. Do not silently send when
the user navigates away. Confirm whether background sessions should survive while
always discarding an active microphone press. A maximum capture duration is also
needed; microphone chunks currently grow until release.

### P1: Setup cancellation can leak a transport not yet owned by a session

`MainViewModel.kt:329-364` creates a TCP/Bluetooth transport before waiting for
`engineLock`. `leaveSession()` only closes `session`, which is assigned after that
wait. Each transport has its own SupervisorJob/IO scope, independent of the setup
coroutine.

Reproduction to automate: let an old native STT call retain `engineLock` after
Leave, start another hosted session, let its transport bind, then Leave before
the lock is acquired. Cancelling `sessionJob` does not close that unregistered
transport. Exceptions constructing the listener/session have the same ownership
gap. This can leave a hidden listening socket and make the next bind fail.

Proposal: use `try/finally` ownership transfer; until SessionManager takes
ownership, cancellation or error closes the transport. Test setup as a small
owner component with a fake transport factory and a deliberately held lock.

### P1: Unlimited queueing makes slow CPU translation a memory/visibility problem

`SessionManager.kt:169`, `TcpTransport` and `BluetoothTransport` each use
`Channel.UNLIMITED`. Receiving keeps accepting text while one large translation
runs. In-flight deduplication now correctly retains every identity, which also
grows with this unlimited work. The visible transcript drops old rows at 100
messages, even when those rows are still queued (`SessionManager.kt:570-574`).
A flood or simply a much faster sender can accumulate minutes of invisible
playback work and keep talk disabled. The completed-ACK pruning scan also becomes
more expensive with many pending identities.

Proposal for this iteration: bounded session queue, non-suspending `trySend`, a
clear busy notice/retained text-only outcome, and a queue count in UI. Avoid
blocking the packet collector behind inference: PING/ACK control processing must
remain responsive. An overflow policy must preserve honest delivery semantics;
silently dropping a row yet calling it translated/played would be incorrect.
Longer term, bound transport inboxes and negotiate explicit busy/rejection status.

### P1/P2: AudioRecord fatal reads and stop ownership need an injectable boundary

`AudioRecorder.kt:56-59` ignores every read result `<= 0`. Android documents
`ERROR_DEAD_OBJECT` as requiring recreation. If a failed recorder keeps returning
a negative error immediately, this loop can consume CPU indefinitely with no
failure propagated to the user.

`AudioRecorder.stop()` is unsynchronized. `DeviceListener.finish()` stops on
Default while `cancel()` can stop on the UI thread; finishing and leaving can
overlap on the same AudioRecord. Both can observe the same `record`. If
`rec.stop()` throws, joining the reader is skipped, yet fields are cleared and a
new start is allowed. The old reader uses a shared `running` flag, so a late old
reader can observe a new capture's `running=true`. These are ownership risks,
not proof that a particular Android driver exhibits the race.

Proposal: single capture ownership/generation, one stop/release operation, fatal
read reporting, a bounded recording duration, and a backend interface that lets
JVM tests control reads, stop failures, and reader completion. Ensure a stale
reader cannot append to or spin during a new capture.

### P2: A delayed delivery ACK is not proof of first audio or successful translation

The ACK is generated after queueing, translation, and first synthesis
(`SessionManager.kt:461-480`). It is also generated for translation failure and
missing voice. Sender expiry is a fixed 60 seconds from button release
(`SessionManager.kt:223-231`). A receiver held-talk interval or cold/slow CPU
translation can exceed this, after which a later valid ACK is ignored. The
receiver may subsequently play text the sender still labels unconfirmed.

`Message.endToEndMs` currently claims receiver audio start, and `TalkScreen`
displays "end-to-end", although an ACK can mean text-only/failure and actual
AudioTrack setup happens later. Peer queue/TTS values saturate at 65,535 ms.
Voice loading and native lock waits are not included in the reported TTS timer.

This iteration should preserve wire v2 and delayed timing fields for compatibility
but call the metric a delivery/readiness estimate, never audible latency. Sender
"delivered" means text receipt only. Retain an unconfirmed result as uncertain,
offer explicit retry, and document timeout/saturation. A later negotiated protocol
can separate immediate receipt from processing/voice outcomes; merely moving the
existing ACK earlier would invalidate historical timing semantics.

### P2: Reconnect state is not associated with a durable peer identity

The TCP/Bluetooth hosts intentionally accept another peer after a disconnect.
Every new `Connected` event resends all pending outgoing packets, irrespective of
which peer connected (`SessionManager.kt:203-205`). Receive identity contains
only 16-bit sequence plus 32-bit timestamp and lacks a peer/session identifier.

A second phone joining an existing host session can receive outstanding text
intended for the first phone. A newly booted peer can also reuse packet identity
values. A transport's `Connected.peer` string is not an application-level session
handshake and TCP currently includes an ephemeral source port.

Proposal: at minimum bind resend policy to the established peer and surface
"different phone connected" instead of forwarding old pending work. A later
wire-version handshake/session nonce provides a stronger boundary. Do not claim
exactly-once delivery across arbitrary peers, process restart, or history eviction.

### P2: Stop/write failures can leak AudioTrack or stall playback

`TtsEngine.kt:62` ignores the return value from `AudioTrack.write`. Its loop waits
for the full original sample count; a short/error write is not converted into an
explicit failure. `finally` calls `track.stop()` before `track.release()` without
nested `finally`, so a stop exception can prevent release.

Proposal: check write results and initialization, make release unconditional,
bound/reject abnormal playback progress, and introduce a playback backend whose
error/partial-write behavior can be tested without hardware. Do not label a
message PLAYED solely because an unexpectedly stopped track exited the loop.

### P2/P3: Recovery and typed input are currently absent

There is no typed-text entry, source correction, translation retry, playback
replay/stop control, or review draft API. `handleSpeech()` sends immediately after
STT. Translation/voice errors tell the user to retry, but there is no action to do
so from the original row. Installing a missing translation pack requires leaving
the session; leaving resets all messages. Send errors immediately remove the
packet from pending, so reconnect only retries messages that were initially sent
successfully and remain unconfirmed.

Useful real features: optional source review before any send/translation; typed
input through the same validated text pipeline; retry local translation from the
retained source; replay the already translated text without rerunning STT/model;
and a user-reviewed exact phrasebook. Preserve origin/source provenance rather
than silently replacing what the microphone heard. These are implementable
features, not promises of stronger learned-model accuracy.

### P3: Public lifecycle/concurrency assumptions are implicit

`start()` is not idempotent; calling it twice creates two playback workers against
a map intended for one worker. `pressStart()` has no closed-session guard. The
hold/playback claim relies on production calls sharing the Main dispatcher;
its read/start/set sequence is not atomic for arbitrary parent scopes. Also,
pressEnd clears `talking` before `DeviceListener.finish()` actually stops the
microphone on Default, so an already queued remote playback can begin during
capture shutdown. The safe immediate rule is to withhold playback while the
listening/processing capture owner is still active, then claim work once
processing has relinquished ownership. Do not enqueue by a suspending send that
prevents the state from returning to Ready.

## Deterministic test strategy before device access

Add `kotlinx-coroutines-test` at the existing coroutines version. Use `runTest`, a
shared TestCoroutineScheduler, and `clock={testScheduler.currentTime}`. The clock
is already injectable. Replace timing sleeps in newly added state-machine tests
with gates or virtual time; keep real TCP tests for framing/socket behaviors.

Use a scripted Transport that can hold/drop/duplicate ACKs, disconnect/reconnect,
change peer identity, delay writes, and fail specific sends. Use Listener,
Translator, Speaker, and capture/playback fakes with explicit completion gates.
Record an ordered event trace; assert observable state and actions, not internal
method-call order alone. Proposed cases:

1. Cancel a press through each cause: user discard, gesture cancellation, screen
   disposal, lifecycle stop, short tap, and Leave. No STT, send, or queued Solo
   translation occurs; microphone stop happens once.
2. Race release/Leave/restart using gated capture stop; old samples/read errors
   cannot affect the new capture and resources release despite failures.
3. Hold a setup engine lock, create a transport, then cancel before registration.
   Assert its listener/socket is closed and a subsequent host can bind.
4. Block translation, fill the bounded queue, submit one extra row, and issue
   PING. Queue size stays bounded, overload is explicit, duplicates consume no
   new slots, and control replies continue.
5. Delay processing beyond ACK timeout using virtual time, then deliver the real
   ACK. Assert intentionally defined uncertain/late-confirmed behavior and no
   incorrect "heard"/first-audio assertion.
6. Lose an ACK, reconnect, duplicate TEXT before and after synthesis, and replay
   the saved ACK; no repeat translation/audio. Separately test completed-history
   eviction and document its replay limit.
7. Disconnect peer A with pending text and connect peer B. Pending A text must
   not be sent to B. Reset/stale ACK identity cases must remain isolated.
8. Source-review enabled: release produces Draft with original transcript/timings;
   no model/send/speaker runs until confirm. Correct, cancel, reject blank/oversize,
   and double-confirm. Typed submission invokes no microphone/STT.
9. Critical numeric-detail warning: preserve translation and receipt ACK, set
   NEEDS_REVIEW, prepare no voice. Only explicit Play anyway permits audio.
   User-reviewed phrase origin can bypass the autoplay gate while still showing
   any warnings. No warning must never be called "safe" or "accurate".
10. Retry translation failure/NO_VOICE and replay a completed target sentence:
    same source metadata, no packet resend or STT, ordered work, and no duplicate
    admission when the button is tapped repeatedly.
11. AudioTrack short/negative writes, stopped progress, stop throwing, cancellation
    during each chunk/prefetch: release always occurs and failure stays observable.
12. Seeded randomized replay of bounded start/release/cancel/incoming/disconnect/
    retry/close events across all ten source/target codes. Check queue bound,
    one active pipeline, no work after close, source preservation, language-correct
    voice selection, no automatic warned playback, and recoverable Ready state.

These tests prove application state/failure handling under simulated faults.
They cannot establish Android driver behavior, Bluetooth radio reliability,
acoustic first-audio latency, real recognition accuracy, or low-RAM performance.

## Agreed prospective contracts (wait for root's implementation go)

Root accepted the following outline; final fields can be coordinated before code:

- `SessionState.draft: SourceDraft?`, `reviewBeforeSend: Boolean`, and queue count;
  `Phase.Reviewing` disables another capture while the source draft is unresolved.
- `SourceDraft` exposes draft text, original transcript and source provenance;
  original Heard/release timing remains available for confirmation.
- `submitText(text): Boolean`; `cancelPress()`; `setReviewBeforeSend(enabled)`;
  `confirmDraft(text)`; `discardDraft()`; `retryMessage(id): Boolean`;
  `replayMessage(id, allowUnsafe=false): Boolean`.
- Message origin/provenance and `warnings: List<String>`; Status.NEEDS_REVIEW.
- Translation.kt owns `TranslationOrigin.MODEL/REVIEWED_PHRASE` and
  `Translated.origin` default MODEL. Runtime sibling owns
  `CriticalDetailChecker.check(source,target): List<String>` and phrasebook wrapper.
- Numerical detail warnings withhold MODEL autoplay after the existing delivery
  ACK. Reviewed phrase matches may autoplay but display warnings transparently.
  Explicit `allowUnsafe` is required to play a NEEDS_REVIEW row.

## Authoritative sources consulted

1. [Kotlin Channel.UNLIMITED](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/-factory/-u-n-l-i-m-i-t-e-d.html):
   sender never suspends; capacity is limited only by available memory. Supports
   the bounded-work recommendation, not an inference performance claim.
2. [Kotlin coroutine testing](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/):
   runTest, TestCoroutineScheduler and StandardTestDispatcher provide shared
   virtual time and controlled execution for the replay/fault tests.
3. [Android AudioRecord reference](https://developer.android.com/reference/android/media/AudioRecord):
   READ_BLOCKING semantics and ERROR_DEAD_OBJECT recreation requirement establish
   why ignoring negative read returns is an error-handling gap.
4. [Android AudioTrack reference](https://developer.android.com/reference/android/media/AudioTrack):
   write results and initialization/play state are observable backend contracts;
   code must handle failures and release resources.
5. [Android Compose gesture documentation](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures):
   gesture lifecycle helpers support treating cancellation separately from a
   successfully completed user release.
6. [Android Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle):
   activity visibility/lifetime and retained ViewModel state must not be confused
   with microphone lifetime.
7. [Android offline-first architecture](https://developer.android.com/topic/architecture/data-layer/offline-first):
   local source of truth, persisted user writes, queues and retry policies are
   useful patterns for a future local outbox/history. A backend is not required
   for implementing local typed/review/recovery paths here.
8. [OASIS MQTT 5.0 delivery definitions](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html):
   at-least-once delivery explicitly allows duplicates. Used only as a clear
   reliability vocabulary; this app need not add MQTT or a cloud broker.
9. [Android Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p):
   devices with supported hardware can connect without an access point. This is
   a potential later offline pairing feature requiring permissions/hardware
   validation, not a substitute for proving the current socket/session behavior.

# iTantra Phase 1 plan: two phones, text over Wi-Fi

## Goal
Phone A: hold to talk → STT → small text packet over Wi-Fi → Phone B: TTS → speaker.
Works in both directions. Each screen shows bytes sent and end-to-end latency.

**Exit criterion:** 10 Hindi sentences each way over one phone's hotspot, no losses, latency measured per sentence.

Test devices: Samsung Galaxy S25 (SM-S931B) plus the user's second Android phone (model to be recorded in step 7).

---

## Design decisions

### Modes
- The same app runs on both phones. The start screen offers **Host**, **Join** and **Solo**.
- **Solo** is the Phase 0 single-phone loop (STT → local TTS). It stays as a demo mode and a speed baseline.

### Link
- A TCP socket over one phone's Wi-Fi hotspot. No internet or router needed.
- TCP gives reliable, ordered delivery.
- Bluetooth RFCOMM comes later, as a second transport behind the same interface.

### Roles
- The host listens on `0.0.0.0:5005` and shows its IP on screen. The IP is read from the network interfaces, not hard-coded (Samsung hotspots don't always use `192.168.x.1`).
- The joiner enters the host's IP. Auto-discovery (NSD) can come later.

### Packet format (13 B header + text + 4 B CRC = 17 B overhead)

| Field | Size | Purpose |
|---|---|---|
| Magic `iT` + version | 3 B | Reject garbage |
| Type (TEXT / ACK / PING / PONG) | 1 B | Control vs speech |
| Sequence number | 2 B | Detect loss or reordering; ACK/PONG echo the seq they answer |
| Language code | 1 B | Receiver picks the right voice |
| Send timestamp | 4 B | Sender-clock ms (uint32), echoed back, never compared across devices |
| Payload length | 2 B | Framing on the TCP stream |
| Payload | N B | UTF-8 text (TEXT), or receiver timings (ACK) |
| CRC32 | 4 B | Integrity over header + payload. Redundant on TCP; needed once Bluetooth arrives |

**ACK payload (8 B):** the receiver's TTS synthesis time (uint32 ms) and how long the message waited in its queue while the receiver held the talk button (uint32 ms).

### Latency measurement across two phones
- The two phones' clocks don't agree, so we never subtract timestamps across devices.
- The receiver sends an **ACK at the moment TTS playback starts**.
- The sender computes end-to-end = `(ACK received − button released) − RTT/2`, all on its own clock. RTT comes from PING/PONG.
- Breakdown: network = end-to-end − (VAD + STT) − (TTS + queue wait from the ACK).

### Half-duplex (walkie-talkie behaviour)
- Incoming speech is not played while the user holds the button. It is queued and played on release; the wait is reported in the ACK.

### Architecture rule
- `CommEngine`/`Transport` knows nothing about STT/TTS.
- Speech code knows nothing about sockets.
- They meet only in `SessionManager`.

### New modules
- `comm/Packet.kt`, `comm/PacketCodec.kt`
- `comm/Transport.kt` (interface)
- `comm/TcpTransport.kt`
- `session/SessionManager.kt`
- `scripts/fake_peer.py`

`comm/` uses only plain Kotlin/Java APIs, so its unit tests run on the PC (`gradlew test`).

---

## Steps (test after each, then commit)

| # | Step | Test | Needs 2nd phone? |
|---|---|---|---|
| 1 | `PacketCodec`: encode/decode + CRC (plain Kotlin) | JUnit: round trip, corrupt CRC rejected, Hindi UTF-8 intact, truncated/bad magic rejected | No |
| 2 | `Transport` interface + `TcpTransport` (host/join, framing, reconnect) | JUnit over localhost | No |
| 3 | Python fake peer on the PC | Phone → PC prints packets; PC → phone sends Hindi text, phone speaks it | No |
| 4 | `SessionManager`: STT → packet → send; receive → TTS → ACK; queue during PTT; Solo mode | Full loop against the fake peer | No |
| 5 | UI: Host/Join/Solo screen, IP display, connection state, message list, bytes vs raw-audio equivalent, latency | Visual check on the S25 | No |
| 6 | PING/PONG RTT + ACK-based end-to-end latency | Numbers appear against the fake peer | No |
| 7 | Two-phone test over the S25 hotspot | 10 sentences each way; log in `BENCHMARKS.md` | **Yes** |

> **Key trick:** in step 3 the PC stands in for the second phone, so steps 1–6 need only the S25.

Fake-peer wiring over USB:
- Phone as **Host**: `adb forward tcp:5005 tcp:5005`, and the PC connects to `127.0.0.1:5005`.
- Phone as **Joiner**: `adb reverse tcp:5005 tcp:5005`, and the phone joins `127.0.0.1`.

---

## Gotchas
- The server must bind `0.0.0.0`, not a specific IP.
- Detect and display the hotspot phone's own IP; don't hard-code it.
- Samsung hotspots can switch off after a few idle minutes. Raise the hotspot timeout before testing.
- Keep the screen on during a session (`FLAG_KEEP_SCREEN_ON`); Android may kill the socket when the phone sleeps.
- When a phone joins a hotspot with no internet, Android asks "no internet, stay connected?". Tap **Keep**, or the Wi-Fi may drop.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`.

---

## Metrics to record (in `BENCHMARKS.md`)
- Bytes per utterance (17 B overhead + payload) vs the raw PCM equivalent (16 kHz × 16-bit × duration)
- RTT (PING/PONG)
- End-to-end latency per sentence: button release → receiver audio start
- Stage breakdown: VAD / STT / network / TTS / queue wait
- Device names, Android versions, link type (hotspot)

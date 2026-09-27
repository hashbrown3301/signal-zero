# iTantra Phase 2 plan: Bluetooth transport

## Goal
The same call works over Bluetooth instead of Wi-Fi: no hotspot, no router.
The user picks **Wi-Fi** or **Bluetooth** on the connect screen; everything above the transport is unchanged.

**Exit criterion:** 10 Hindi sentences each way over Bluetooth between two phones, no losses, latency logged and
compared against Wi-Fi. The same sitting also runs the 10 sentences over Wi-Fi, which closes Phase 1's on-hold exit test.

Test devices: Samsung Galaxy S25 (SM-S931B, Android 16) and Galaxy M21 2021 (SM-M215G, Android 13).

---

## Design decisions

### Bluetooth Classic (RFCOMM), not BLE
| | RFCOMM (Classic) | BLE (GATT) |
|---|---|---|
| Model | Stream socket, like TCP | Small characteristic writes |
| Packet size | Any; reuse our length-prefix framing | MTU 20–512 B, needs chunking |
| Code reuse | Same `PacketCodec` + framing as TCP | New fragmentation layer |
| Bandwidth | Hundreds of kbps, far more than text needs | Lower, fine for text |
| Complexity | Low | Higher |

→ **RFCOMM.** BLE can be future scope (lower power, mesh), not now.

### How it connects
- One phone = **Host**: `listenUsingRfcommWithServiceRecord("iTantra", APP_UUID)`. It serves one peer at a time and goes back
  to listening after a drop (same semantics as the TCP host).
- Other phone = **Join**: picks the host from the paired-device list → `createRfcommSocketToServiceRecord(APP_UUID)`,
  with the same 1 s / 2 s / 5 s reconnect backoff as TCP.
- A fixed, randomly generated app UUID identifies iTantra's service.
- **Pairing:** pair the two phones once in Android's Bluetooth settings; the app lists **already-paired devices**,
  phones first (by device class), others greyed out below, and remembers the last one picked. In-app discovery is later.

### Code
- **`comm/FramedStream.kt`**: packet framing over any `InputStream`/`OutputStream`, extracted from `TcpTransport`
  so both transports share it. PC tests with in-memory streams.
- **New `comm/BluetoothTransport.kt`** implementing the existing `Transport` interface. It uses Android APIs, so it's
  tested on the phones, not on the PC.
- **Unchanged:** `PacketCodec`, PING/PONG, ACK latency, `SessionManager`, the speech code.
- **`LinkState`** gets transport-neutral fields (port optional; "host" can be an IP or a device name), so the UI can say
  "waiting for M21 over Bluetooth".
- **UI:** the connect screen gets a **Wi-Fi / Bluetooth** toggle. Bluetooth shows the paired-device list instead of an
  IP field, and the session screen shows the link type.
- **Benchmark CSV** gains a `transport` column (`wifi` / `bt`) and the connection setup time (connecting → connected).

### Permissions
| Android | Permissions |
|---|---|
| 12+ (API 31+) | `BLUETOOTH_CONNECT` only (paired list, host, connect, enable prompt); a runtime prompt |
| 8–11 | `BLUETOOTH`, `BLUETOOTH_ADMIN` with `maxSdkVersion="30"`; no location (we don't scan) |

- `BLUETOOTH_SCAN` and location are only needed for discovering unpaired devices, which is out of scope. Add them
  (with `neverForLocation`) only when in-app discovery is built.
- Ask when the user taps Bluetooth, not at app start.
- Handle "Bluetooth is off" → the system prompt to turn it on (`ACTION_REQUEST_ENABLE`).

---

## Steps (test after each, then commit)

| # | Step | Test |
|---|---|---|
| 0 | Plan doc; extract `FramedStream` from `TcpTransport` | PC: new FramedStream tests + all existing TCP tests pass |
| 1 | Permissions + "Bluetooth off" handling + paired-device list UI | S25 lists its paired devices (phones first), no crash when BT is off or permission denied |
| 2 | `BluetoothTransport`: host (server socket) + join (client socket), shared framing, clean close | Two phones connect and exchange PING/PONG (RTT shown) |
| 3 | Wi-Fi/Bluetooth toggle wired into the session | Full voice loop over Bluetooth, both directions |
| 4 | Reconnect on drop (walk out of range and back) + clear "link lost" state | Link recovers without restarting the app |
| 5 | Benchmark: the 10 sentences over Wi-Fi **and** Bluetooth, same sitting | Comparison table in `BENCHMARKS.md`; also closes Phase 1's exit test |

> Needs **two phones** from step 2 onward (the PC fake peer doesn't cover Bluetooth easily on Windows).

---

## Gotchas
- Samsung may prompt for pairing confirmation on **both** phones; accept on both.
- Some phones kill Bluetooth sockets when the screen sleeps → keep the screen on during a session (already done in Phase 1).
- `accept()` and `connect()` block; run them on a background coroutine and close the socket to cancel.
- Don't call `cancelDiscovery()` before connecting: on Android 12+ it needs `BLUETOOTH_SCAN`, and we never start discovery.
- Range is about 10 m indoors, and walls cut it further; note it in the demo.
- **Turn the hotspot off during Bluetooth tests.** Both share the 2.4 GHz band on many phones and would skew the numbers.
- Don't run Wi-Fi and Bluetooth transports at the same time in this phase. **Auto-failover** (switch to Bluetooth
  when Wi-Fi drops) is a stretch goal for later.

---

## Metrics to record
- RTT (PING/PONG): Bluetooth vs Wi-Fi
- End-to-end latency per sentence: Bluetooth vs Wi-Fi
- Connection setup time (connecting → connected)
- Max working distance (rough, indoors)

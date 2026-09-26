"""A PC stand-in for the second phone: speaks the iTantra packet protocol over TCP.

Phone is Host (PC connects through USB):
    adb forward tcp:5005 tcp:5005
    python scripts/fake_peer.py --connect 127.0.0.1:5005

Phone joins (phone connects to 127.0.0.1:5005, which lands on the PC):
    adb reverse tcp:5005 tcp:5005
    python scripts/fake_peer.py --listen 5005

With no --send, type lines to send them as Hindi TEXT packets (Ctrl+C to quit).
Incoming TEXT is printed and ACKed, PING is answered with PONG, and ACK/PONG show round-trip time.

    python scripts/fake_peer.py --selftest     # check the codec against known bytes
"""

import argparse
import socket
import struct
import sys
import threading
import time
import zlib

MAGIC = b"iT"
VERSION = 1
HEADER = struct.Struct(">2sBBHBIH")  # magic, version, type, seq, lang, timestamp, length = 13 B
CRC = struct.Struct(">I")
OVERHEAD = HEADER.size + CRC.size

TEXT, ACK, PING, PONG = 1, 2, 3, 4
TYPE_NAMES = {TEXT: "TEXT", ACK: "ACK", PING: "PING", PONG: "PONG"}
HINDI = 1

_start = time.monotonic()


def log(msg: str) -> None:
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}", flush=True)


def now_ms() -> int:
    return int((time.monotonic() - _start) * 1000) & 0xFFFFFFFF


def encode(ptype: int, seq: int, ts: int, payload: bytes = b"", lang: int = HINDI) -> bytes:
    body = HEADER.pack(MAGIC, VERSION, ptype, seq & 0xFFFF, lang, ts & 0xFFFFFFFF, len(payload)) + payload
    return body + CRC.pack(zlib.crc32(body) & 0xFFFFFFFF)


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("peer closed the connection")
        buf += chunk
    return bytes(buf)


def read_packet(sock: socket.socket) -> dict:
    header = recv_exact(sock, HEADER.size)
    magic, version, ptype, seq, lang, ts, length = HEADER.unpack(header)
    if magic != MAGIC or version != VERSION:
        raise ValueError(f"bad magic/version: {magic!r} v{version}")
    rest = recv_exact(sock, length + CRC.size)
    payload, (crc,) = rest[:length], CRC.unpack(rest[length:])
    if zlib.crc32(header + payload) & 0xFFFFFFFF != crc:
        raise ValueError("CRC mismatch")
    return {"type": ptype, "seq": seq, "lang": lang, "ts": ts, "payload": payload, "size": OVERHEAD + length}


class Peer:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.lock = threading.Lock()
        self.seq = 0

    def send(self, data: bytes) -> None:
        with self.lock:
            self.sock.sendall(data)

    def send_text(self, text: str) -> None:
        payload = text.encode("utf-8")
        self.send(encode(TEXT, self.seq, now_ms(), payload))
        log(f"-> TEXT seq={self.seq} {OVERHEAD + len(payload)} B: {text}")
        self.seq += 1

    def reader(self) -> None:
        try:
            while True:
                p = read_packet(self.sock)
                name = TYPE_NAMES.get(p["type"], f"type{p['type']}")
                if p["type"] == TEXT:
                    log(f"<- TEXT seq={p['seq']} {p['size']} B: {p['payload'].decode('utf-8')}")
                    self.send(encode(ACK, p["seq"], p["ts"], struct.pack(">II", 0, 0), p["lang"]))
                elif p["type"] == PING:
                    self.send(encode(PONG, p["seq"], p["ts"], lang=p["lang"]))
                    log(f"<- PING seq={p['seq']} (answered PONG)")
                elif p["type"] == ACK:
                    tts_ms, queue_ms = struct.unpack(">II", p["payload"])
                    rtt = (now_ms() - p["ts"]) & 0xFFFFFFFF
                    log(f"<- ACK  seq={p['seq']} after {rtt} ms (peer TTS {tts_ms} ms, queued {queue_ms} ms)")
                else:
                    rtt = (now_ms() - p["ts"]) & 0xFFFFFFFF
                    log(f"<- {name} seq={p['seq']} rtt={rtt} ms")
        except (EOFError, OSError, ValueError) as e:
            log(f"-- connection ended: {e}")


def watch(path: str, peer: "Peer") -> None:
    """Send every line appended to `path` (created empty if missing) until the connection ends."""
    open(path, "a", encoding="utf-8").close()
    with open(path, encoding="utf-8") as f:
        f.seek(0, 2)
        log(f"-- watching {path}")
        while True:
            line = f.readline()
            if not line:
                time.sleep(0.1)
                continue
            if line.strip():
                try:
                    peer.send_text(line.strip())
                except OSError as e:
                    log(f"-- send failed: {e}")
                    return


def selftest() -> None:
    # Same packet as PacketCodecTest.wireLayoutMatchesSpec / matchesPythonFakePeerBytes.
    got = encode(TEXT, 0x0102, 0x0A0B0C0D, "ab".encode())
    assert got == bytes.fromhex("69 54 01 01 01 02 01 0a 0b 0c 0d 00 02 61 62 89 40 59 bd"), got.hex(" ")
    hindi = encode(TEXT, 7, 1000, "नमस्ते".encode())
    assert hindi == bytes.fromhex(
        "69 54 01 01 00 07 01 00 00 03 e8 00 12 e0 a4 a8 e0 a4 ae e0 a4 b8 e0 a5 8d e0 a4 a4 e0 a5 87 6d bf 35 41"
    ), hindi.hex(" ")
    p = read_packet(_FakeSock(hindi))
    assert p["payload"].decode() == "नमस्ते" and p["seq"] == 7 and p["ts"] == 1000
    print("selftest OK")


class _FakeSock:
    def __init__(self, data: bytes):
        self.data = data

    def recv(self, n: int) -> bytes:
        out, self.data = self.data[:n], self.data[n:]
        return out


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stdin.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--connect", metavar="HOST:PORT", help="connect to a phone that is hosting")
    mode.add_argument("--listen", metavar="PORT", type=int, help="wait for a phone to join")
    mode.add_argument("--selftest", action="store_true", help="check the codec and exit")
    ap.add_argument("--send", action="append", default=[], metavar="TEXT", help="send TEXT (repeatable), then exit")
    ap.add_argument("--wait", type=float, default=3.0, help="seconds to keep listening after --send (default 3)")
    ap.add_argument("--gap", type=float, default=0.0, help="seconds between --send messages")
    ap.add_argument("--watch", metavar="FILE", help="send each line appended to FILE (for scripted tests)")
    args = ap.parse_args()

    if args.selftest:
        selftest()
        return

    if args.connect:
        host, port = args.connect.rsplit(":", 1)
        sock = socket.create_connection((host, int(port)), timeout=5)
        sock.settimeout(None)
        log(f"-- connected to {args.connect}")
    else:
        srv = socket.create_server(("0.0.0.0", args.listen))
        log(f"-- listening on :{args.listen}")
        sock, addr = srv.accept()
        log(f"-- phone connected from {addr[0]}:{addr[1]}")
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    peer = Peer(sock)
    threading.Thread(target=peer.reader, daemon=True).start()

    if args.send:
        for i, text in enumerate(args.send):
            if i and args.gap:
                time.sleep(args.gap)
            peer.send_text(text)
        time.sleep(args.wait)
    elif args.watch:
        watch(args.watch, peer)
    else:
        try:
            for line in sys.stdin:
                if line.strip():
                    peer.send_text(line.strip())
        except KeyboardInterrupt:
            pass
    sock.close()


if __name__ == "__main__":
    main()

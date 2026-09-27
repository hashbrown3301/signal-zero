"""Turn the phones' benchmarks.csv files into the BENCHMARKS.md tables for the scripted test.

    # copy the CSVs off every connected phone, then summarize
    python scripts/summarize_benchmarks.py --pull out_dir/
    python scripts/summarize_benchmarks.py out_dir/*.csv > summary.md

    # also save only the scripted-test rows (safe to commit: no free conversation)
    python scripts/summarize_benchmarks.py out_dir/*.csv --save-filtered docs/phase2_results/

Only sentences that match docs/phase1_sentences.md are used (best word-error-rate match, WER <= 0.6),
so free-form conversation in the same files is ignored. For each sent message the sender's final row
counts: ACKED = delivered, SENT/FAILED without an ACK = lost.
"""

import argparse
import csv
import re
import statistics
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SENTENCES = ROOT / "docs" / "phase1_sentences.md"
MATCH_MAX_WER = 0.6
LINK_NAMES = {"wifi": "Wi-Fi (hotspot)", "bt": "Bluetooth (RFCOMM)"}
MODEL_NAMES = {"SM-S931B": "Galaxy S25", "SM-M215G": "Galaxy M21", "SM-A032F": "Galaxy A03 Core"}


# ---------- text ----------

def normalize(text: str) -> list[str]:
    """Words for WER: no punctuation, nukta dropped, chandrabindu → anusvara (STT rarely keeps either)."""
    text = text.replace("़", "").replace("ँ", "ं")
    text = re.sub(r"[।,.?!\"'“”‘’:;\-–]", " ", text)
    return text.split()


def wer(ref: list[str], hyp: list[str]) -> float:
    d = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, d[0] = d[0], i
        for j, h in enumerate(hyp, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r != h))
    return d[len(hyp)] / max(len(ref), 1)


def load_sentences() -> list[str]:
    rows = []
    for line in SENTENCES.read_text(encoding="utf-8").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) == 3 and cells[0].isdigit():
            rows.append(cells[2])
    return rows


def match(text: str, sentences: list[str]) -> tuple[int, float] | None:
    hyp = normalize(text)
    best = min(((i, wer(normalize(s), hyp)) for i, s in enumerate(sentences)), key=lambda x: x[1])
    return best if best[1] <= MATCH_MAX_WER else None


# ---------- data ----------

def num(v: str) -> float | None:
    return float(v) if v not in ("", None) else None


def read_rows(paths: list[Path]) -> list[dict]:
    rows = []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            if "link" not in (reader.fieldnames or []):
                print(f"skipping {p}: old CSV layout (no link column)", file=sys.stderr)
                continue
            rows += list(reader)
    return rows


def sent_messages(rows: list[dict], sentences: list[str]) -> list[dict]:
    """One entry per scripted sentence sent: the sender's last row for (device, session, link, seq)."""
    final: dict[tuple, dict] = {}
    for r in rows:
        if r["direction"] != "OUTGOING":
            continue
        key = (r["device"], r["session"], r["link"], r["seq"])
        if key not in final or r["status"] in ("ACKED", "FAILED"):
            final[key] = r
    out = []
    for r in final.values():
        m = match(r["text"], sentences)
        if m:
            out.append({**r, "ref": m[0], "wer": m[1]})
    return sorted(out, key=lambda r: (r["link"], r["device"], r["session"], r["time"]))


def peer_of(device: str, devices: set[str]) -> str:
    others = sorted(devices - {device})
    return others[0] if len(others) == 1 else "peer"


def name(model: str) -> str:
    return MODEL_NAMES.get(model, model)


# ---------- report ----------

def fmt(v, digits=0) -> str:
    if v is None:
        return "–"
    return f"{v:.{digits}f}" if digits else f"{round(v)}"


def stats(values: list[float]) -> str:
    v = [x for x in values if x is not None]
    if not v:
        return "–"
    return f"{fmt(statistics.median(v))} ({fmt(min(v))}–{fmt(max(v))})"


def report(rows: list[dict], sentences: list[str]) -> str:
    msgs = sent_messages(rows, sentences)
    devices = {r["device"] for r in rows}
    out = []
    groups = defaultdict(list)
    for m in msgs:
        groups[(m["link"], m["device"])].append(m)

    summary = []
    for (link, dev), ms in sorted(groups.items()):
        peer = peer_of(dev, devices)
        out.append(f"#### {LINK_NAMES.get(link, link)}: {name(dev)} → {name(peer)}\n")
        out.append("| # | Words | WER | Transcript | Bytes vs raw audio | VAD | STT | Other | Net | Peer queue | Peer TTS | **End-to-end** |")
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for m in sorted(ms, key=lambda m: m["ref"]):
            ref_words = len(normalize(sentences[m["ref"]]))
            delivered = m["status"] == "ACKED"
            wire, rec = num(m["wire_bytes"]), num(m["recorded_s"])
            raw = rec * 32000 if rec else None
            bytes_col = f"{fmt(wire)} B vs {fmt(raw / 1024)} KB ({fmt(raw / wire)}×)" if wire and raw else "–"
            rtt = num(m["rtt_ms"])
            e2e = f"**{fmt(num(m['e2e_ms']))} ms**" if delivered else f"**lost ({m['status'].lower()})**"
            out.append(
                f"| {m['ref'] + 1} | {ref_words} | {m['wer']:.0%} | {m['text']} | {bytes_col} | {fmt(num(m['vad_ms']))} | "
                f"{fmt(num(m['stt_ms']))} | {fmt(num(m['other_ms']))} | {fmt(rtt / 2 if rtt else None)} | "
                f"{fmt(num(m['peer_queue_ms']))} | {fmt(num(m['peer_tts_ms']))} | {e2e} |"
            )
        delivered = [m for m in ms if m["status"] == "ACKED"]
        out.append("")
        summary.append({
            "link": link, "dir": f"{name(dev)} → {name(peer)}",
            "sent": len({m["ref"] for m in ms}), "delivered": len({m["ref"] for m in delivered}),
            "e2e": stats([num(m["e2e_ms"]) for m in delivered]),
            "rtt": stats([num(m["rtt_ms"]) for m in delivered]),
            "stt": stats([num(m["stt_ms"]) for m in delivered]),
            "peer_tts": stats([num(m["peer_tts_ms"]) for m in delivered]),
            "wer": statistics.mean(m["wer"] for m in ms) if ms else None,
            "setup": stats(sorted({num(r["setup_ms"]) for r in rows
                                   if r["link"] == link and r["device"] == dev and num(r["setup_ms"])})),
        })

    head = [
        "| Link | Direction | Delivered | End-to-end ms, median (min–max) | RTT ms | STT ms | Peer TTS ms | Mean WER | Setup ms |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for s in summary:
        head.append(
            f"| {LINK_NAMES.get(s['link'], s['link'])} | {s['dir']} | {s['delivered']}/{s['sent']} of 10 | {s['e2e']} | "
            f"{s['rtt']} | {s['stt']} | {s['peer_tts']} | {s['wer']:.0%} | {s['setup']} |"
        )
    missing = [
        f"- {LINK_NAMES.get(s['link'], s['link'])}, {s['dir']}: {10 - s['sent']} of the 10 sentences not found in the logs"
        for s in summary if s["sent"] < 10
    ]
    notes = ["", "WER = word error rate after removing punctuation, nukta and chandrabindu differences."]
    return "\n".join(head + ([""] + missing if missing else []) + notes + ["", *out])


def save_filtered(rows: list[dict], sentences: list[str], out_dir: Path) -> None:
    """Keep only rows whose text matches a scripted sentence (both directions), one file per device."""
    out_dir.mkdir(parents=True, exist_ok=True)
    by_dev = defaultdict(list)
    for r in rows:
        if match(r["text"], sentences):
            by_dev[r["device"]].append(r)
    for dev, rs in by_dev.items():
        path = out_dir / f"{dev}.csv"
        with open(path, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rs[0].keys()))
            w.writeheader()
            w.writerows(rs)
        print(f"saved {len(rs)} scripted rows → {path}", file=sys.stderr)


def pull(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    devices = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
    for line in devices:
        if not line.endswith("\tdevice"):
            continue
        serial = line.split("\t")[0]
        model = subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.product.model"],
                               capture_output=True, text=True).stdout.strip()
        for fname in ("benchmarks.csv", "links.csv"):
            data = subprocess.run(["adb", "-s", serial, "exec-out", "run-as", "com.itantra", "cat", f"files/{fname}"],
                                  capture_output=True).stdout
            if data:
                dest = out_dir / f"{model}_{fname}"
                dest.write_bytes(data)
                print(f"pulled {dest} ({len(data)} B)", file=sys.stderr)


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", nargs="*", type=Path, help="benchmarks.csv files from the phones")
    ap.add_argument("--pull", type=Path, metavar="DIR", help="copy benchmarks.csv/links.csv off every connected phone")
    ap.add_argument("--save-filtered", type=Path, metavar="DIR", help="save only scripted-test rows")
    args = ap.parse_args()

    if args.pull:
        pull(args.pull)
        return
    sentences = load_sentences()
    rows = read_rows([p for p in args.csv if not p.name.endswith("links.csv")])
    if args.save_filtered:
        save_filtered(rows, sentences, args.save_filtered)
    print(report(rows, sentences))


if __name__ == "__main__":
    main()

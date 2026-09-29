"""Original score + sound design for the iTantra film, locked to the composition's timeline (100 s, 48 kHz stereo).

numpy only: polyBLEP oscillators, FFT-mask filters, STFT sweeps, FFT convolution reverb. Master with ffmpeg afterwards.
"""
import sys
import wave
from pathlib import Path

import numpy as np

SR = 48000
DUR = 100.0
N = int(SR * DUR)
rng = np.random.default_rng(26173)


def mtof(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def tt(d):
    return np.arange(int(d * SR)) / SR


def S(t):
    return int(round(t * SR))


# ---------------------------------------------------------------- buses
music = np.zeros((N, 2))
drums = np.zeros((N, 2))
fx = np.zeros((N, 2))
send = np.zeros((N, 2))  # reverb send


def put(bus, x, at, gain=1.0, pan=0.0, rev=0.0):
    """Mix mono or stereo x into bus at time `at` (s) with constant-power pan and a reverb send."""
    if x.ndim == 1:
        a = (pan + 1) * np.pi / 4
        x = np.stack([x * np.cos(a), x * np.sin(a)], axis=1)
    i = S(at)
    if i < 0:
        x = x[-i:]
        i = 0
    j = min(N, i + len(x))
    if j <= i:
        return
    bus[i:j] += x[: j - i] * gain
    if rev:
        send[i:j] += x[: j - i] * gain * rev


# ---------------------------------------------------------------- dsp helpers
def fft_filter(x, fn):
    """Zero-phase filter along axis 0 with magnitude response fn(freqs)."""
    n = len(x)
    size = 1 << int(np.ceil(np.log2(n + 1)))
    X = np.fft.rfft(x, size, axis=0)
    f = np.fft.rfftfreq(size, 1 / SR)
    H = fn(f)
    Y = X * (H[:, None] if X.ndim == 2 else H)
    return np.fft.irfft(Y, size, axis=0)[:n]


def lp(fc, order=2):
    return lambda f: 1 / np.sqrt(1 + (f / fc) ** (2 * order))


def hp(fc, order=2):
    return lambda f: 1 / np.sqrt(1 + (fc / np.maximum(f, 1e-3)) ** (2 * order))


def bp(lo, hi, order=2):
    return lambda f: lp(hi, order)(f) * hp(lo, order)(f)


def sweep(x, fc0, fc1, q=1.2):
    """Time-varying band-pass (STFT, gaussian band in log-frequency) from fc0 to fc1 Hz."""
    nfft, hop = 1024, 256
    win = np.hanning(nfft)
    pad = np.concatenate([np.zeros(nfft), x, np.zeros(nfft)])
    frames = 1 + (len(pad) - nfft) // hop
    out = np.zeros(len(pad))
    norm = np.zeros(len(pad))
    f = np.fft.rfftfreq(nfft, 1 / SR)
    lf = np.log2(np.maximum(f, 20))
    for k in range(frames):
        s = k * hop
        seg = pad[s : s + nfft] * win
        p = min(1.0, max(0.0, (s - nfft) / max(1, len(x))))
        fc = fc0 * (fc1 / fc0) ** p
        H = np.exp(-0.5 * ((lf - np.log2(fc)) / q) ** 2)
        out[s : s + nfft] += np.fft.irfft(np.fft.rfft(seg) * H, nfft) * win
        norm[s : s + nfft] += win**2
    out /= np.maximum(norm, 1e-6)
    return out[nfft : nfft + len(x)]


def saw(freq, t, phase0=0.0):
    """polyBLEP sawtooth; freq may be scalar or array."""
    f = np.broadcast_to(freq, t.shape).astype(float)
    ph = (phase0 + np.cumsum(f) / SR) % 1.0
    dt = f / SR
    y = 2 * ph - 1
    m = ph < dt
    x = ph[m] / dt[m]
    y[m] -= x + x - x * x - 1
    m = ph > 1 - dt
    x = (ph[m] - 1) / dt[m]
    y[m] -= x * x + x + x + 1
    return y


def sine(freq, t, phase0=0.0):
    f = np.broadcast_to(freq, t.shape).astype(float)
    return np.sin(2 * np.pi * (phase0 + np.cumsum(f) / SR))


def env_ad(n, a, d, sustain=1.0):
    e = np.ones(n) * sustain
    na = max(1, int(a * SR))
    nd = max(1, int(d * SR))
    e[:na] = np.linspace(0, 1, na) ** 1.5
    e[-nd:] *= np.linspace(1, 0, nd) ** 1.3
    return e



# ---------------------------------------------------------------- reverb IR (stereo, frequency-dependent decay)
def make_ir(rt_low=3.2, rt_mid=2.6, rt_high=1.1, length=3.4, pre=0.022):
    n = int(length * SR)
    t = np.arange(n) / SR
    ir = np.zeros((n, 2))
    for ch in range(2):
        noise = rng.standard_normal(n)
        bands = [(lp(350, 2), rt_low), (bp(350, 3500, 2), rt_mid), (hp(3500, 2), rt_high)]
        acc = np.zeros(n)
        for fn, rt in bands:
            acc += fft_filter(noise, fn) * np.exp(-6.91 * t / rt)
        ir[:, ch] = acc
    er = np.zeros((n, 2))
    for k in range(14):  # sparse early reflections
        d = int((0.008 + 0.06 * rng.random()) * SR)
        er[d, k % 2] += (0.6 - 0.035 * k) * rng.choice([-1, 1])
    ir += er * 3
    ir = np.concatenate([np.zeros((int(pre * SR), 2)), ir])
    return ir / np.sqrt((ir**2).sum(axis=0).mean())


def convolve(x, ir):
    n = len(x) + len(ir) - 1
    size = 1 << int(np.ceil(np.log2(n)))
    y = np.fft.irfft(np.fft.rfft(x, size, axis=0) * np.fft.rfft(ir, size, axis=0), size, axis=0)
    return y[: len(x)]


# ---------------------------------------------------------------- instruments
CH = {
    "Dmaj9": ([50, 57, 61, 64, 66], 38),
    "Bm9": ([47, 54, 57, 61, 62], 35),
    "Gmaj9": ([43, 50, 54, 57, 59], 31 + 12),
    "Asus": ([45, 52, 57, 59, 64], 33 + 12),
    "drone": ([38, 45], 38),
}
ARP = {
    "Dmaj9": [74, 78, 81, 85, 88, 86],
    "Bm9": [71, 74, 78, 81, 85, 83],
    "Gmaj9": [67, 71, 74, 78, 81, 79],
    "Asus": [69, 71, 76, 78, 81, 83],
}


def pad(chord, start, end, level=1.0, cutoff=3800, attack=1.4, release=1.8):
    notes, _ = CH[chord]
    dur = end - start + release
    t = tt(dur)
    out = np.zeros((len(t), 2))
    for m in notes:
        f0 = mtof(m)
        for ch, cents in ((0, (-7, 0, 5)), (1, (-4, 0, 8))):
            for c in cents:
                ph = rng.random()
                vib = 1 + 0.0015 * np.sin(2 * np.pi * (0.13 + 0.05 * rng.random()) * t + ph * 6)
                out[:, ch] += saw(f0 * 2 ** (c / 1200) * vib, t, ph) / 3
    out /= len(notes)
    out = fft_filter(out, lambda f: lp(cutoff, 2)(f) * hp(140, 1)(f))
    for m in notes[-2:]:  # glassy air on the top two notes
        for ch, det in ((0, -3), (1, 3)):
            out[:, ch] += 0.22 * sine(mtof(m + 12) * 2 ** (det / 1200), t) + 0.08 * sine(mtof(m + 19), t)
    breathe = 1 + 0.08 * np.sin(2 * np.pi * 0.11 * t)
    e = env_ad(len(t), attack, release) * breathe
    put(music, out * e[:, None], start, 0.6 * level, rev=0.5)


def sub(chord, start, end, level=1.0, release=0.6):
    _, root = CH[chord]
    t = tt(end - start + release)
    f = mtof(root - 12) if root > 40 else mtof(root)
    x = np.tanh(1.4 * (sine(f, t) + 0.25 * sine(2 * f, t)))
    put(music, x * env_ad(len(t), 0.25, release), start, 0.085 * level)


def pluck(m, dur=0.5):
    t = tt(dur)
    f = mtof(m)
    x = 0.8 * sine(f, t) + 0.25 * saw(f, t) * np.exp(-t / 0.03) + 0.2 * sine(2 * f, t) * np.exp(-t / 0.05)
    x *= np.exp(-t / 0.13) * np.minimum(1, t / 0.003)
    return x


def kick():
    t = tt(0.5)
    f = 50 + 110 * np.exp(-t / 0.03)
    x = np.tanh(1.5 * sine(f, t) * np.exp(-t / 0.13))
    click = fft_filter(rng.standard_normal(len(t)), bp(2000, 6000)) * np.exp(-t / 0.004) * 0.55
    return x + click


def hat(open_=False):
    t = tt(0.2 if open_ else 0.06)
    x = fft_filter(rng.standard_normal(len(t)), hp(7500, 3))
    return x * np.exp(-t / (0.06 if open_ else 0.013))


def shaker():
    t = tt(0.08)
    x = fft_filter(rng.standard_normal(len(t)), bp(4500, 10000, 2))
    return x * np.sin(np.pi * np.minimum(t / 0.08, 1)) ** 2


def snap():
    t = tt(0.25)
    body = fft_filter(rng.standard_normal(len(t)), bp(1200, 5000, 2)) * np.exp(-t / 0.05)
    tone = sine(210, t) * np.exp(-t / 0.04) * 0.4
    return body + tone


KICK, HAT, OHAT, SHK, SNAP = kick(), hat(), hat(True), shaker(), snap()


def groove(start, end, bpm=96, kick_gain=1.0, hats=True, snaps=True, shak=True, cut_beats=()):
    beat = 60 / bpm
    kicks = []
    k = 0
    while start + k * beat < end - 1e-6:
        at = start + k * beat
        if k not in cut_beats:
            put(drums, KICK, at, 0.3 * kick_gain)
            kicks.append(at)
        if hats:
            put(drums, HAT if k % 4 != 3 else OHAT, at + beat / 2, 0.16, pan=0.25)
        if snaps and k % 4 in (1, 3) and k not in cut_beats:
            put(drums, SNAP, at, 0.11, pan=-0.1, rev=0.3)
        if shak:
            for s in range(4):
                put(drums, SHK, at + s * beat / 4, (0.045, 0.026, 0.038, 0.026)[s] * (0.9 + 0.2 * rng.random()), pan=-0.35)
        k += 1
    return kicks


def arp(chord, start, end, bpm=96, level=1.0, step=4):
    beat = 60 / bpm
    notes = ARP[chord]
    order = [0, 2, 1, 3, 2, 4, 3, 5]
    k = 0
    while start + k * beat / step < end - 1e-6:
        m = notes[order[k % len(order)] % len(notes)]
        at = start + k * beat / step
        x = pluck(m)
        pan = 0.35 * np.sin(k * 0.9)
        acc = (1.0 if k % 4 == 0 else 0.7) * level
        put(music, x, at, 0.12 * acc, pan=pan, rev=0.3)
        for tap in range(1, 4):  # ping-pong dotted-eighth delay
            put(music, x, at + tap * beat * 0.75, 0.12 * acc * 0.38**tap, pan=(-pan if tap % 2 else pan) * 1.6, rev=0.2)
        k += 1


# ---------------------------------------------------------------- sound design
def pop(f=1175, level=1.0, pan=0.0, weight=0.0, rev=0.22, at=0.0):
    """Glass pop for text reveals: sine + inharmonic partials, a click, optional low 'thock' for weight."""
    t = tt(0.6)
    x = sine(f, t) * np.exp(-t / 0.085) + 0.35 * sine(2.76 * f, t) * np.exp(-t / 0.035) + 0.12 * sine(5.4 * f, t) * np.exp(-t / 0.015)
    x *= np.minimum(1, t / 0.0015)
    x += fft_filter(rng.standard_normal(len(t)), hp(3000)) * np.exp(-t / 0.0015) * 0.3
    if weight:
        x += weight * np.tanh(2 * sine(140 * np.exp(-t / 0.08) + 60, t)) * np.exp(-t / 0.05)
    put(fx, x, at, 0.24 * level, pan=pan, rev=rev)


def tick(f, at, level=1.0, pan=0.0):
    t = tt(0.05)
    x = sine(f, t) * np.exp(-t / 0.008) * np.minimum(1, t / 0.0008)
    put(fx, x, at, 0.09 * level, pan=pan, rev=0.08)


def bell(f, dur=1.8):
    t = tt(dur)
    x = sine(f, t) * np.exp(-t / 0.6) + 0.5 * sine(2.0 * f, t) * np.exp(-t / 0.3) + 0.25 * sine(3.01 * f, t) * np.exp(-t / 0.15)
    return x * np.minimum(1, t / 0.002)


def shimmer(at, level=1.0, notes=(81, 86, 88, 90, 93, 98)):
    for k, m in enumerate(notes):
        put(fx, bell(mtof(m)), at + k * 0.035, 0.05 * level, pan=-0.6 + 0.24 * k, rev=0.7)
    t = tt(1.2)
    air = fft_filter(rng.standard_normal(len(t)), hp(6000, 2)) * np.sin(np.pi * t / 1.2) ** 2
    put(fx, air, at - 0.2, 0.025 * level, rev=0.5)


def whoosh(at, dur=0.7, lo=300, hi=5000, level=1.0, pan0=-0.6, pan1=0.6, peak=0.62):
    """Band-swept noise; `at` is the moment of peak energy."""
    n = int(dur * SR)
    x = sweep(rng.standard_normal(n), lo, hi, q=0.9)
    t = np.arange(n) / n
    e = np.where(t < peak, (t / peak) ** 2.2, ((1 - t) / (1 - peak)) ** 1.6)
    x = x * e
    pans = np.linspace(pan0, pan1, n)
    a = (pans + 1) * np.pi / 4
    st = np.stack([x * np.cos(a), x * np.sin(a)], axis=1)
    put(fx, st / (np.abs(st).max() + 1e-9), at - dur * peak, 0.17 * level, rev=0.25)


def riser(end_at, dur=1.8, level=1.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = sweep(rng.standard_normal(n), 400, 9000, q=1.0)
    noise /= np.abs(noise).max() + 1e-9
    tone = sine(220 * 2 ** (1.5 * t / dur), t) * 0.35 + sine(330 * 2 ** (1.5 * t / dur), t) * 0.2
    trem = 0.75 + 0.25 * np.sin(2 * np.pi * np.cumsum(4 + 14 * (t / dur) ** 2) / SR)
    x = (noise * 0.8 + tone) * trem * (t / dur) ** 2.4
    put(fx, x, end_at - dur, 0.14 * level, rev=0.3)


def impact(at, level=1.0, chord=None, big=False):
    t = tt(2.2)
    f = 32 + 38 * np.exp(-t / 0.18)
    boom = np.tanh(1.8 * sine(f, t) * np.exp(-t / (0.7 if big else 0.45)))
    thud = fft_filter(rng.standard_normal(len(t)), lp(900, 2)) * np.exp(-t / 0.05) * 0.9
    put(fx, 0.5 * boom + thud * 0.7, at, 0.2 * level, rev=0.35)
    if chord:
        notes, _ = CH[chord]
        tc = tt(2.6)
        stab = np.zeros(len(tc))
        for m in notes:
            stab += saw(mtof(m + 12), tc) + saw(mtof(m + 12) * 1.004, tc)
        stab = fft_filter(stab / len(notes), lp(2600, 2)) * np.exp(-tc / 0.5) * np.minimum(1, tc / 0.004)
        put(fx, stab, at, 0.14 * level, rev=0.9)


def reverse_swell(end_at, chord="Dmaj9", dur=1.6, level=1.0):
    notes, _ = CH[chord]
    t = tt(dur)
    x = np.zeros(len(t))
    for m in notes:
        x += saw(mtof(m + 12), t)
    x = fft_filter(x / len(notes), lp(3000, 2)) * np.exp(-t / 0.35)
    wet = convolve(np.stack([x, x], axis=1), IR)[: len(t)]
    rev = wet[::-1] * np.linspace(0, 1, len(t))[:, None] ** 2
    put(fx, rev / (np.abs(rev).max() + 1e-9), end_at - dur, 0.12 * level)


def data_stream(start, end, level=1.0):
    """The voice codec's frames: one micro-burst every 20 ms of speech, compressed into the playhead sweep."""
    frames = 188
    for k in range(frames):
        at = start + (end - start) * k / frames
        t = tt(0.012)
        x = fft_filter(rng.standard_normal(len(t)), bp(1800, 6500, 2)) * np.exp(-t / 0.003)
        put(fx, x, at, 0.045 * level * (0.8 + 0.4 * rng.random()), pan=-0.3 + 0.6 * k / frames)


def haptic(at, level=1.0):
    t = tt(0.12)
    x = np.tanh(3 * sine(180 * np.exp(-t / 0.02) + 70, t)) * np.exp(-t / 0.025)
    put(fx, x, at, 0.16 * level)


# ---------------------------------------------------------------- the score
IR = make_ir()
PENTA = [74, 76, 78, 81, 83, 86, 88, 90, 93, 95]  # D major pentatonic, D5 upward

# 0 – 5.6 · Weight: a low drone under the voice, a reverse swell into the bloom
pad("drone", 0.0, 5.4, level=0.55, cutoff=600, attack=1.8, release=1.2)
sub("drone", 0.6, 5.3, level=0.35, release=0.8)
reverse_swell(5.0, "Dmaj9", 1.4, 0.9)
impact(5.0, 0.85, chord="Dmaj9")
pop(1175, 0.55, weight=0.4, at=0.8)  # "Voice is heavy."

# 5.6 – 15.6 · Meaning → thesis
pad("Dmaj9", 5.6, 8.1, 0.9)
pad("Bm9", 8.1, 10.6, 0.9)
pad("Gmaj9", 10.6, 13.1, 0.95)
pad("Asus", 13.1, 15.6, 1.0, cutoff=2200)
sub("Dmaj9", 5.6, 8.1, 0.6)
sub("Bm9", 8.1, 10.6, 0.6)
sub("Gmaj9", 10.6, 13.1, 0.7)
sub("Asus", 13.1, 15.6, 0.8)
pop(988, 0.6, weight=0.4, at=5.9)  # "Meaning isn't."
shimmer(6.75, 0.6)  # the Hindi line resolves
for g in range(5):  # 55 tiles, five groups of 11
    pop(mtof(PENTA[g + 2]), 0.5, pan=-0.5 + 0.25 * g, rev=0.15, at=8.45 + g * 0.14)
whoosh(8.9, 0.9, 4000, 500, 0.5, 0.4, -0.4, 0.5)  # counter falls 120,336 → 55
tick(2400, 9.3, 0.8, -0.3)
tick(2700, 9.5, 0.8, 0.3)
arp("Bm9", 9.4, 10.6, level=0.6)
arp("Gmaj9", 10.6, 12.2, level=0.7)
whoosh(12.15, 0.7, 500, 6000, 0.9)  # tiles leave frame
riser(12.35, 1.6, 0.9)
impact(12.35, 0.8, chord="Gmaj9")  # "Transmit meaning."
impact(12.95, 1.0, chord="Asus", big=True)  # "Not bandwidth."
for s in range(8):  # filtered hat build into the drop
    put(drums, HAT, 14.35 + s * 0.156, 0.05 + 0.012 * s, pan=0.2)
riser(15.6, 1.2, 0.6)

# 15.6 – 55.6 · USP: the engine. 96 BPM, 2-bar chords
kicks = []
seq = ["Bm9", "Gmaj9", "Dmaj9", "Asus"] * 2
for i, c in enumerate(seq):
    a, b = 15.6 + i * 5.0, 15.6 + (i + 1) * 5.0
    pad(c, a, b, 0.75, cutoff=2000, attack=0.3, release=0.8)
    sub(c, a, b, 1.0, release=0.2)
    arp(c, a, b, level=0.55 if 27.9 <= a < 30.8 else 0.85)
kicks += groove(15.6, 26.6 - 0.625, cut_beats=())
kicks += groove(26.6, 34.6 - 0.625)
kicks += groove(34.6, 45.6 - 0.625)
kicks += groove(45.6, 53.7)
impact(15.6, 1.0, chord="Bm9", big=True)
for at, lvl in ((26.6, 0.6), (34.6, 0.6), (45.6, 0.6)):
    whoosh(at - 0.1, 0.8, 300, 5000, 0.8)
    impact(at, lvl)
# U1 · codecs
pop(880, 0.8, weight=0.5, at=15.9)
tick(2200, 16.2, 0.7)
for i in range(5):
    pop(mtof(PENTA[i]), 0.6, pan=-0.2, at=16.7 + i * 0.6)
    whoosh(16.95 + i * 0.6, 0.45, 800, 4000, 0.25, -0.5, 0.2, 0.4)
shimmer(19.2, 0.55, notes=(86, 90, 93, 98))  # iTantra's bar: the teal one
for i in range(4):
    pop(mtof(PENTA[5 + i]), 0.75, pan=0.5, weight=0.2, at=21.64 + i * 0.22)
# U2 · bits over time
pop(880, 0.8, weight=0.5, at=26.9)
tick(2200, 27.1, 0.7)
pop(mtof(74), 0.45, pan=-0.4, at=27.3)
pop(mtof(81), 0.45, pan=-0.4, at=27.45)
data_stream(27.9, 30.7, 1.0)
for m, d in ((86, 0), (93, 0.05)):  # the single clean packet
    put(fx, bell(mtof(m), 1.4), 30.75 + d, 0.09, pan=0.5, rev=0.5)
haptic(30.75, 0.9)
pop(mtof(78), 0.6, pan=-0.3, at=31.2)
pop(mtof(83), 0.6, pan=0.3, at=31.6)
impact(32.1, 0.55)
pop(mtof(90), 0.8, weight=0.3, at=32.1)
# U3 · packet anatomy
pop(880, 0.8, weight=0.5, at=34.9)
tick(2200, 35.1, 0.7)
for k in range(55):  # each byte lands
    tick(1600 * 2 ** (k / 55 * 1.4), 35.4 + k * 0.018, 0.55, pan=-0.7 + 1.4 * k / 55)
for k in range(8):
    tick(3100 + 150 * k, 36.5 + k * 0.18, 0.35, pan=-0.5 + 0.14 * k)
whoosh(38.6, 0.8, 600, 3000, 0.4)
pop(mtof(86), 0.7, pan=0.2, at=38.9)
for k in range(4):
    pop(mtof(PENTA[3 + k]), 0.6, pan=-0.45 + 0.3 * k, at=40.3 + k * 0.3)
# U4 · latency
pop(880, 0.8, weight=0.5, at=45.9)
tick(2200, 46.1, 0.7)
seg_ms = [35, 246, 77, 42, 3, 757]
seg_notes = [74, 78, 81, None, None, 76]
st = 46.6
for ms, m in zip(seg_ms, seg_notes):
    if m:
        pop(mtof(m), 0.55, pan=-0.4 + 0.8 * (st - 46.6) / 2.4, at=st)
    if ms == 42:
        for mm, d in ((93, 0), (98, 0.04)):
            put(fx, bell(mtof(mm), 1.2), st + d, 0.08, rev=0.5)
    st += max(0.1, 2.3 * ms / 1160)
pop(mtof(81), 0.7, weight=0.3, at=st)  # 1,160 ms total
tick(2500, 49.2, 0.6, -0.4)
tick(2800, 49.35, 0.6, 0.4)
pop(988, 0.7, weight=0.3, at=49.8)
for k in range(3):
    pop(mtof(PENTA[4 + k]), 0.7, pan=-0.4 + 0.4 * k, weight=0.2, at=50.8 + k * 0.35)
riser(55.6, 1.9, 1.0)

# 55.6 – 80.8 · the product: a breakdown; voices lead
reverse_swell(55.6, "Dmaj9", 1.2, 0.8)
impact(55.6, 1.0, chord="Dmaj9", big=True)
for c, a, b in (("Dmaj9", 55.6, 60.6), ("Gmaj9", 60.6, 65.6), ("Bm9", 65.6, 70.6), ("Gmaj9", 70.6, 75.6), ("Asus", 75.6, 80.8)):
    pad(c, a, b, 1.0, cutoff=1500, attack=1.0, release=1.6)
    sub(c, a, b, 0.55, release=0.8)
arp("Dmaj9", 56.0, 60.6, level=0.35, step=2)
arp("Gmaj9", 60.6, 62.6, level=0.3, step=2)
whoosh(55.9, 1.2, 250, 2500, 0.7, 0.2, 0.6, 0.5)  # phone rises
pop(988, 0.7, weight=0.4, at=56.4)
pop(mtof(81), 0.55, at=57.0)
whoosh(58.3, 1.2, 400, 3000, 0.45, 0.6, 0.1)  # macro push
pop(988, 0.7, weight=0.4, at=59.3)
whoosh(60.6, 1.0, 3000, 400, 0.4, 0.1, 0.6)  # pull back
whoosh(62.0, 0.9, 300, 3500, 0.6, 0.6, -0.4)  # phone slides left
pop(988, 0.7, weight=0.4, at=62.3)
whoosh(63.2, 0.9, 300, 2000, 0.35, -0.2, -0.3)  # push in on the button
haptic(62.9, 1.0)
whoosh(68.0, 0.8, 2500, 400, 0.35)
whoosh(68.5, 0.9, 400, 4000, 0.55, 0.8, 0.3)  # phone B arrives
for k in range(6):  # the 66 bytes leave phone A
    tick(2600 + 200 * k, 69.4 + k * 0.03, 0.6, -0.5)
whoosh(69.95, 0.9, 900, 7000, 0.75, -0.6, 0.6, 0.5)
haptic(70.35, 0.6)
whoosh(70.7, 0.9, 300, 3000, 0.5, 0.5, -0.5)  # camera pan
pop(988, 0.6, weight=0.3, at=70.45)
pop(880, 0.6, weight=0.3, at=70.9)
shimmer(70.65, 0.9)  # Hindi → Telugu
shimmer(74.95, 0.6, notes=(78, 81, 86, 90, 93))
pop(988, 0.6, weight=0.3, at=75.4)
shimmer(77.95, 0.8, notes=(76, 81, 85, 88, 93, 97))  # Telugu → Gujarati

# 80.8 – 94.9 · ten languages → on-device: the groove returns
impact(80.8, 0.7, chord="Dmaj9")
for c, a, b in (("Dmaj9", 80.8, 85.8), ("Bm9", 85.8, 90.8), ("Gmaj9", 90.8, 93.3), ("Asus", 93.3, 94.9)):
    pad(c, a, b, 0.85, cutoff=2100, attack=0.4, release=1.0)
    sub(c, a, b, 1.0, release=0.3)
    arp(c, a, min(b, 93.9), level=0.8)
kicks += groove(80.8, 93.8, hats=True)
for i in range(10):  # each language lands
    pop(mtof(PENTA[i]), 0.65, pan=np.sin(-np.pi / 2 + i * 2 * np.pi / 10) * 0.7, rev=0.3, at=81.0 + i * 0.3)
pop(784, 0.6, weight=0.4, at=81.2)
pop(880, 0.6, weight=0.4, at=81.9)
shimmer(83.8, 0.8, notes=(74, 78, 81, 86, 90, 93, 98))  # every pair linked
whoosh(85.3, 0.6, 3000, 600, 0.35)
pop(784, 0.7, weight=0.4, at=85.7)
pop(988, 0.7, weight=0.4, at=86.3)
whoosh(89.3, 0.8, 3000, 500, 0.4)
whoosh(90.0, 1.2, 250, 2500, 0.6, 0.3, 0.6, 0.5)  # phone rises
pop(880, 0.7, weight=0.4, at=90.0)
pop(988, 0.7, weight=0.4, at=90.5)
pop(mtof(81), 0.55, at=91.6)
whoosh(94.35, 0.7, 2500, 400, 0.4)

# 94.9 – 100 · close
riser(94.9, 1.9, 1.1)
impact(94.9, 1.0, chord="Dmaj9", big=True)  # "Communication beyond bandwidth."
pad("Dmaj9", 94.9, 99.2, 1.0, cutoff=1900, attack=0.6, release=0.8)
sub("Dmaj9", 94.9, 99.0, 0.8, release=0.8)
reverse_swell(98.2, "Dmaj9", 1.3, 0.9)
for k, m in enumerate((74, 78, 81)):  # the mark draws itself
    tick(mtof(m + 12), 97.9 + k * 0.06, 0.7, -0.2 + 0.2 * k)
impact(98.2, 1.0, chord="Dmaj9", big=True)  # logo
for m, d in ((74, 0), (81, 0.03), (86, 0.06), (90, 0.09)):
    put(fx, bell(mtof(m), 2.2), 98.2 + d, 0.07, rev=0.8)
pop(mtof(86), 0.5, at=98.7)

# ---------------------------------------------------------------- mix
beat_sc = np.ones(N)  # sidechain pump from the kicks
for k in kicks:
    i = S(k)
    n = min(N - i, int(0.3 * SR))
    tt_ = np.arange(n) / SR
    beat_sc[i : i + n] = np.minimum(beat_sc[i : i + n], 1 - 0.45 * np.exp(-tt_ / 0.09))
music *= beat_sc[:, None]

duck = np.ones(N)  # voices lead: music ducks ~11 dB under them
for a, b in ((0.3, 4.06), (62.9, 67.7), (70.6, 74.4), (75.0, 80.7)):
    ia, ib = S(a - 0.25), S(b + 0.35)
    duck[ia:ib] = 0.28
    duck[S(a - 0.25) : S(a)] = np.linspace(1, 0.28, S(a) - S(a - 0.25))
    duck[S(b) : S(b + 0.35)] = np.linspace(0.28, 1, S(b + 0.35) - S(b))
wet = convolve(send, IR)
bed = (music + 0.22 * wet) * duck[:, None] + drums * duck[:, None] ** 0.5
mix = bed + fx
mix = fft_filter(mix, lambda f: hp(32, 2)(f) * (0.5 + 0.5 * hp(110, 1)(f)) * (1 + 1.2 * bp(2500, 12000, 1)(f)))  # low shelf −6 dB, presence + air lift
fade = np.ones(N)
fade[-S(1.2) :] = np.linspace(1, 0, S(1.2)) ** 1.5
mix *= fade[:, None]
mix /= np.abs(mix).max() + 1e-9
mix = np.tanh(1.25 * mix) / np.tanh(1.25)  # glue
out = Path(sys.argv[1])
pcm = (np.clip(mix, -1, 1) * 0.89 * 32767).astype(np.int16)
with wave.open(str(out), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print("wrote", out, round(len(pcm) / SR, 2), "s")

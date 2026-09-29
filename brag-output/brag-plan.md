# Brag Plan: iTantra (v2, translation-first)

## What is this app?
An offline voice link between phones for 10 Indian languages (PS SIH26173: multilingual STT + TTS transceiver for low-bitrate links). Speech becomes a few dozen bytes of text, crosses Wi-Fi or Bluetooth, and is spoken again on the other phone. Flagship story: **any language to any language**. A Hindi speaker is heard in Telugu, Telugu in Gujarati, across all 10.

## The angle
Two ideas, in order:
1. **Voice is heavy. Meaning isn't.** One real Hindi sentence, "कृपया पानी और दवाइयां उत्तर वाले गेट पर भेज दीजिए" (please send water and medicines to the north gate): 4.8 s of voice as the app records it (16 kHz, 16-bit mono) is **153,404 bytes**. The app sent the same sentence in **66 bytes** ("66 B sent" in the real footage; wire v2 = 49 letters at 1 byte each + a 17 B header).
2. **Speak your language. They hear theirs.** Since only meaning travels, the receiving phone can speak it in the listener's language: Hindi → Telugu → Gujarati → every pair of the ten.

## Truth check
| Item | Status | How the film handles it |
|---|---|---|
| Cross-language translation (IndicTrans2, on the receiving phone) | **Planned, not built.** The model download is gated on Hugging Face and the app has no translation screen yet. | Shown as **motion design** (text transforming between scripts in space, the app's own voices speaking the result), **never as a faked app screen**. |
| STT, TTS, 10 language packs, push-to-talk, Wi-Fi + Bluetooth, offline, 66 B packets | Built and real | Real screen recordings only |
| Live calling, speaker profiles, network adaptation | Not built | Omitted |
| RTT / latency numbers | Only emulator numbers in the footage | No latency headline |

The translated sentences are written by me, not by IndicTrans2, and **need a native-speaker check** before the film goes public. They can be swapped for IndicTrans2 output once the model access works.

## Hook (0–5.6 s)
White frame. A different real Hindi message, "क्या आप मेरी आवाज़ साफ़ सुन पा रहे हैं?" (can you hear me clearly?), plays; a waveform from that clip draws in sync while a counter runs to **120,336 bytes** (3.76 s at 16 kHz); it blooms into a field of one dot per byte. Headline: **Voice is heavy.**

## Key moments
- The field collapses into the Hindi line, which becomes **55** teal tiles (17 header + 38 letters; "55 B sent" in the Hindi phone's footage). **Meaning isn't.**
- Two graphite phones. Left: the real Hindi Talk screen (hold to talk → Sending "Recognizing speech on this phone" → "55 B sent"). The 55 tiles fly across. Above the right phone, the Hindi line re-forms, letter by letter, into Telugu; the Telugu voice speaks it. **Speak Hindi. / They hear Telugu.**
- Reply: Telugu → Gujarati, faster. Then all 10 scripts light up as a network: every pair connected.

## Outro
**Communication beyond bandwidth.** The iTantra mark draws itself (geometry from `Brand.kt`), then the wordmark with the teal *i*. Sub-line: *Ten languages. On-device. Built for low bandwidth.*

## User flow worth showing (real footage)
1. Home: "Offline voice link · Models ready · offline", Connect a phone, Wi-Fi / Bluetooth / Solo.
2. Talk: hold to talk → Listening → Sending → "55 B sent".
3. Languages: Language packs list ("Downloaded once, then they work offline").

## Tone
- Preset: polished, with cinematic scale on the title, the translation moment and the outro
- Creative direction: flagship product unveiling; bright studio white; one idea per shot; the scripts are the stars
- Interpretation: few words, long holds, slow physical camera on the phones; drama comes from the numbers and the script transformations.

## Format: landscape, 1920x1080 · Duration: 60 s (the user's request)

## Visual identity
- Background: studio white `#F7F8F6` → `#ECEFED` (radial falloff, soft floor shadow)
- Text: app Navy `#09203F`; secondary `#5B6B72`
- Accent: app teal `#1EAE98`; glow Mint `#DDF4EC`
- Fonts: Manrope (display, bundled in the app), Noto Sans per script (bundled), JetBrains Mono (data)
- Phones: generic graphite Android body, real recordings inside, status bar cropped

## Translations used (to verify with native speakers)
- hi (real, spoken and sent): कृपया पानी और दवाइयां उत्तर वाले गेट पर भेज दीजिए
- te: దయచేసి నీళ్లు, మందులు ఉత్తర గేటుకు పంపండి.
- Telugu reply: సరే, పది నిమిషాల్లో పంపుతాం. → gu: ઠીક છે, દસ મિનિટમાં મોકલીએ છીએ.
- Ten-language ring ("Send water and medicines"): पानी और दवाइयाँ भेजिए · Send water and medicines · पाणी आणि औषधे पाठवा · પાણી અને દવાઓ મોકલો · জল আর ওষুধ পাঠান · தண்ணீரும் மருந்துகளும் அனுப்புங்கள் · నీళ్లు, మందులు పంపండి · ನೀರು ಮತ್ತು ಔಷಧಿ ಕಳುಹಿಸಿ · വെള്ളവും മരുന്നും അയയ്ക്കൂ · ପାଣି ଓ ଔଷଧ ପଠାନ୍ତୁ

## Audio direction
- Role: minimal bed; the app's real voices lead (Hindi Piper, Telugu MMS, Gujarati MMS, generated on the PC with the app's own packs)
- Music: bundled `happy-beats-business-moves-vol-10` (60.00 s, 110 BPM), mixed low with the first 4 s cut, ducked under every voice, faded at the end. The license should be checked before posting.
- SFX: sparse: grouped soft ticks for the 55 tiles, a quiet click on hold-to-talk, one low soft impact on the title and the logo
- Restraint: nothing louder than a voice; no whooshes or risers

## Storyboard (60 s)
| # | Scene | Time | Content |
|---|---|---|---|
| 1 | Weight | 0–5.6 | "क्या आप मेरी आवाज़ साफ़ सुन पा रहे हैं?" voice + waveform → a field of 120,336 dots → counter 120,336 bytes. **Voice is heavy.** |
| 2 | Meaning | 5.6–11.6 | Field → the Hindi line → 55 tiles (17 header + 38 letters); counter 120,336 → 55. **Meaning isn't.** |
| 3 | Thesis | 11–15 | Tiles leave frame right like a packet. **Transmit meaning.** / **Not bandwidth.** |
| 4 | Product | 15–22 | The phone rises into frame, real Home screen, macro on "Models ready · offline". **Two phones. No network.** / **Wi-Fi or Bluetooth.** |
| 5 | Hindi → Telugu | 22–35 | Two phones; real Hindi Talk flow on the left (hold → Listening → Sending → 66 B sent), tiles cross to the right; the Hindi line morphs into Telugu above the right phone, the Telugu voice speaks it. **Speak Hindi.** / **They hear Telugu.** |
| 6 | Telugu → Gujarati | 35–41 | Faster reply: the Telugu line → Gujarati; Gujarati voice. **Any language. Any direction.** |
| 7 | Ten languages | 41–50 | "Can you hear me clearly?" in 10 scripts, arranged as a ring; lines link every pair. **Ten languages. Every pair.** → **Speak your language. They hear theirs.** Real Language packs screen glides through. |
| 8 | On-device | 50–54 | Macro: "Downloaded once, then they work offline." **Intelligence. On-device.** / **No cloud. No towers.** |
| 9 | Close | 54–60 | **Communication beyond bandwidth.** → mark draws itself, wordmark, sub-line. |

## v3: USP section (15.6–55.6 s, film now 100 s)
Inserted right after "Transmit meaning. Not bandwidth."; the product scenes moved 40 s later. Music switched to `happy-beats-business-moves-vol-12` (117 s, same 110 BPM).

| # | Scene | Time | Content and sources |
|---|---|---|---|
| U1 | Codecs | 15.6–26.6 | Log-scale bars for the same 3.76 s clip: raw 16 kHz PCM 120,336 B · GSM/AMR 12.2 kbps 5,735 B · Opus 6 kbps 2,820 B · Codec 2 700 bps 329 B · iTantra 55 B. Ratios 2,188× / 104× / 51× / 6×. Codec bytes = bitrate × 3.76 s, payload only (headers not counted, which flatters the codecs); iTantra = the whole packet. |
| U2 | Bits over time | 26.6–34.6 | A voice call sends a 30.5 B AMR frame every 20 ms (188 frames); iTantra sends one packet after release. 12,200 bps vs ≈117 bps (55 B × 8 ÷ 3.76 s) = 104×. |
| U3 | Packet anatomy | 34.6–45.6 | `PacketCodec` v2: magic 2 · ver 1 · type 1 · seq 2 · lang 1 · time 4 · len 2 (13 B header) + 38 B text + CRC32 4 = 55 B. UTF-8 would need 98 B for the text; 1 byte per letter = 38 B. Chips: CRC32 checked · acknowledged · resent after a reconnect · language in 1 byte. |
| U4 | Latency | 45.6–55.6 | BENCHMARKS.md S25 → M21 over Wi-Fi: VAD 35 · STT 246 · packet out 77 · network 42 · queue 3 · TTS 757 = 1,160 ms. Cards: 1.17 s (8.5 s of speech → voice on one S25), 40–68 ms round trip (Wi-Fi hotspot, Bluetooth), 0 servers. |

Not claimed: latency lower than a phone call (a call is continuous at ~150 ms mouth-to-ear; iTantra is about 1 s per phrase), or any number that isn't measured or computed from standard bitrates.

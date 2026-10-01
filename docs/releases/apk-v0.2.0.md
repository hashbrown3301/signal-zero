# iTantra 0.2.0 — offline translation preview

This is a **noncommercial testing prerelease** for Android 8.0 or later. It includes
the offline translation and conversation upgrades merged into `main` on
1 October 2026, plus release preparation on `Upgradation`: version `0.2.0` /
version code `2` and explicit handling of revoked microphone/Bluetooth permissions.

## Download and install

- **`iTantra-0.2.0-arm64.apk`**: choose this for a supported 64-bit Android phone.
  General translation needs at least 4 GB RAM and about 2.5 GB available memory;
  6 GB RAM or more is recommended until phone trials are complete.
- **`iTantra-0.2.0-armv7.apk`**: older 32-bit phones. General NLLB translation cannot
  load on this architecture. Same-language speech and exact saved reviewed
  translations provide limited operation; this is not unrestricted translation.
- **`SHA256SUMS.txt`** and **`release-manifest.json`**: file integrity, package
  versions, signing certificate and build provenance.

Download the matching APK on your phone, open it, and allow installation from
your browser when Android asks. The APK contains Hindi speech assets. Install
the shared translation model and additional speech/voice packs in **Languages**,
or import their ZIPs for offline provisioning. The translation pack is about
900 MB and creates an optimized decoder for about **1.4 GB total installed model
storage**, in addition to the app and speech packs. Conversations work without
internet after setup; peer conversation uses a local Wi-Fi or Bluetooth link.

**Updating from 0.1.0:** the original signing keystore was unavailable. This build
uses the cloud testing certificate, which differs from the published 0.1.0 APK.
Android will not install it over 0.1.0. Uninstall 0.1.0 first, then install 0.2.0.
Uninstalling removes that installation's private local data and downloaded packs;
keep any original imported pack ZIPs to reuse. Future compatible updates must
reuse this signing certificate. The private keystore is never a release asset.

## New in this preview

- Optional **Review speech**: edit recognition before translating or sending.
- **Numeric-detail review**: changed/missing observable quantities, signs, times
  or percentages in a model result require **Play anyway** before audio.
- **Reviewed phrases**: add/edit/delete and reuse exact directional translations
  locally. A saved match avoids loading the large native translation model.
- **Type, retry and replay**: continue without the microphone, retry retained
  source text, or replay a retained translation without another model call.
- Translation installation/repair during idle conversations, bounded capture
  and queues, improved cancellation/cleanup and pack integrity checks.
- Release-variant build: not debuggable, with transcript logging removed and
  debug-only diagnostic CSV persistence disabled. It is signed with a testing
  key; this is not a production signing identity.

## Validation and known limits

The feature source passed **231 JVM tests** and **46 Python tests**. Four opt-in
native checks were skipped in the regular JVM suite. Release preparation reran
the **231 passing JVM tests in the release variant**, assembled all three ABI
APKs and passed Android release lint with **zero errors and 24 warnings**.
The warnings concern dependency updates and existing style/resource/storage
guidance; they are not a substitute for physical-device qualification. The separate native
FLORES evaluation made **180 attempts across all 90 language directions**:
179 nonblank outputs, one generation-limit failure, combined **chrF++ 44.47**.
chrF++ measures lexical reference similarity, not an accuracy percentage.
Cloud translation median was 2.19 seconds, p95 4.42 seconds; these exclude
recognition, audio, UI and radio time and are not phone benchmarks.

Android rendering, microphone, speaker, Bluetooth/Wi-Fi, battery, thermal and
phone memory behavior have not been tested for this build. Numeric checks miss
numbers written as words, changed units and meaning errors such as wrong names,
negation or roles. Reviewed phrases are
reviewed by their users, not certified translations. General model accuracy
has not been improved or certified by these features.

ARM64 ONNX Runtime Extensions libraries still have 4 KB ELF alignment, so
compatibility with Android devices using **16 KB memory pages is unqualified**.
Passing APK ZIP alignment alone does not fix native ELF alignment.

The app code is MIT-licensed. Models have separate licenses and intended-use
limits: NLLB is **CC BY-NC 4.0**, intended for research rather than production;
the bundled Hindi Piper voice dataset is **CC BY-NC-SA 4.0**, and several
additional voices are noncommercial. This preview is for noncommercial
research/testing. Model attribution and license links are in the repository
[README](https://github.com/hashbrown3301/signal-zero/blob/Upgradation/README.md)
and [language pack notices](https://github.com/hashbrown3301/signal-zero/blob/Upgradation/docs/packs-release-notes.md).

See the [research and feature plan](https://github.com/hashbrown3301/signal-zero/blob/Upgradation/docs/RESEARCH_AND_PRODUCT_PLAN.md)
and [raw evidence/results](https://github.com/hashbrown3301/signal-zero/blob/Upgradation/docs/PRODUCT_UPGRADE_RESULTS.md).

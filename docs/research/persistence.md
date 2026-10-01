# Persistence, readiness, resource and lifecycle research

Read-only project audit on branch `Upgradation`, HEAD `ec96e026dc692a108f84ed6d54e6480f8f735eb8`. No project implementation or dependency edits made. Small reproduction tests and downloaded public source text are in `/workspace/scratch` only.

## Architecture and existing safeguards

- `MainViewModel` owns installed speech packs under `filesDir/packs`, a shared translation model under `filesDir/translation`, resumable downloads under `cacheDir`, and a lazily loaded `OfflineTranslator`.
- `TranslationModelStore` pins model revision, lengths and SHA-256 for all three source artifacts. ZIP extraction accepts only flat, known entries; rejects duplicates, traversal and oversized files; verifies complete payloads before a same-directory atomic active-pointer update. Import cancellation is checked during extraction and before activation. UI readiness reads the small pinned manifest and lengths rather than hashing 899 MB repeatedly.
- `PackStore.install` stages an archive and verifies declared payload lengths/checksums before replacing its active directory, with rollback if the final rename fails. Extracted ZIP paths have a canonical zip-slip guard and an aggregate 600 MB expanded-byte cap.
- `OfflineTranslator` serializes runtime/cache access with a coroutine mutex, lazily constructs four ORT sessions, caps its text-result LRU at 128 entries, bypasses same-language work, and releases native resources under that mutex.
- `NllbRuntime` bounds source/generated tokens, checks cancellation between synchronous native calls, frees temporary tensors/results, closes partially constructed sessions, and disables heavyweight allocator/prepacking behavior. `RuntimeModelPatch` streams the derived decoder, hashes source/output, checks available storage, fsyncs output, and atomically activates it without mutating the original model.
- Installation is blocked while a conversation runs. Session start is blocked while a translation installation is busy. Installed speech/listen pack deletion evicts held voices under `voiceLock`; deleting the active recognition pack during a session is blocked.
- Resource loading requires a 64-bit process, a non-low-RAM device, total memory >= 3,000,000,000 bytes, no system low-memory condition, and at least 2,500,000,000 available bytes. This is conservative admission based on cloud measurements, not a demonstrated Android memory guarantee.
- App-specific files require no general storage permission. `android:allowBackup="false"` prevents Android automatic backup of private app data. User-selected imports already use the Storage Access Framework through URI streams.

## Concrete defects and gaps

### Reproduced defects

Three independent scratch-only Kotlin/JUnit reproductions passed, meaning the defects below are confirmed against current source. Runner: `python3 /workspace/scratch/check-persistence-audit.py`; test source: `/workspace/scratch/PersistenceAuditRepro.kt`.

1. **A valid import cannot repair same-length translation corruption.** `TranslationModelStore.kt:194` intentionally checks manifest/lengths only; `activate` at line 234 discards a completely verified incoming generation if that shallow check still passes. Corrupting encoder bytes without changing file length makes `ready()` return true; importing the original valid ZIP retains both the previous directory and corrupt bytes. The runtime does not rehash the encoder or SentencePiece vocabulary before loading them. A damaged decoder may be caught by `RuntimeModelPatch`, but a previously valid derived decoder can also cause the original damaged decoder to be skipped. Add an explicit integrity/repair operation rather than rehashing on every UI refresh; verified user-initiated repair must activate the new generation. Release the cached runtime before model replacement and invalidate its cache afterward.

2. **Speech-pack readiness survives deleted/truncated payloads.** `PackStore.get/list` (`PackStore.kt:26-34`) call only `read`, which parses manifest metadata (`:73-90`). After deleting `model.onnx`, both `get` and `list` still return the pack. `refreshLanguages` (`MainViewModel.kt:225-233`) therefore exposes recognition/voice support that does not exist. `PackRepository.find` (`:40-43`) chooses a damaged installed override over a healthy built-in fallback. Add bounded manifest/path/length validation to the cheap readiness path, reserving complete hashes for installation or explicit integrity checks.

3. **Manifest paths can escape a speech-pack folder even when ZIP entry paths are safe.** `PackStore.verify` (`:97-104`) resolves `files[].path` and engine paths without containment validation. An archive containing `ta-listen/pack.json`, `ta-listen/tokens.txt`, and top-level `outside.bin`, with engine/model path `../outside.bin`, passes verification and installs successfully. The verified model remains outside the selected folder, is deleted with staging, and the resulting installed pack references a missing file. Reject unsafe/canonical escaping manifest paths, duplicate declarations, invalid IDs and unexpected archive root contents. Preserve nested legitimate pack data only when its canonical path remains inside the selected pack folder. ZIP-entry traversal tests alone do not catch this defect.

### Lifecycle/resource gaps

4. **The large native translator remains resident after leaving a conversation.** `leaveSession` (`MainViewModel.kt:470-480`) closes the session but never releases the translator. Release occurs only in `onCleared` (`:687-700`). Current cloud benchmark reports about 1.79 GB resident after load and a process peak around 2.05 GB. An idle start screen or backgrounded activity can retain that native allocation. Add an idle/background/memory-pressure policy that releases under the translator mutex after current native work returns; exact reviewed phrases should continue working without loading the large model. This should be tested with a fake closeable translator rather than assuming JVM memory equals Android memory.

5. **No installed translation model deletion or interrupted-generation cleanup API.** Unlike `PackStore.cleanUp`, `TranslationModelStore` has no cleanup/deletion action. A killed process bypasses `finally`, leaving `.model-*` candidate directories; damaged/unreferenced generations and download cache can retain hundreds of MB. Cleanup must preserve the current active generation and ongoing install, use ownership/serialization rather than race another instance, and close the runtime before deleting a live model. Persistent models belong in `filesDir`; partial downloads in `cacheDir` can disappear at any time and must remain optional.

6. **Readiness is currently conflated with one heavyweight engine.** A low-RAM or 32-bit phone is told it cannot load offline translation (`PacksScreen.kt:253-255`), which is correct for NLLB but unnecessarily prevents an independent exact reviewed-phrase route. Model installation, phrase coverage, voice availability, language-pair coverage and runtime admission are different readiness facts. Do not label reviewed phrase coverage as unrestricted translation.

7. **Startup initialization can be derailed by translation-directory creation failure.** `MainViewModel.init` calls `refreshTranslation` before speech language initialization (`:206-212`), while lazy `TranslationModelStore` construction throws if model/download directories cannot be created. A translation-storage failure can prevent otherwise available built-in speech initialization. Keep optional translator/phrasebook failures isolated from core speech readiness.

### Native/library and model release limits

8. **ORT Extensions 0.13.0 is not natively 16 KB ELF aligned.** Direct `readelf -lW` inspection of current prepared arm64 AARs found both `libortextensions.so` and `libonnxruntime_extensions4j_jni.so` have `LOAD` alignment `0x1000`. Sherpa's native runtime/API libraries and the Microsoft Java JNI library have `0x4000` LOAD alignment. Results: `/workspace/scratch/native-alignment-research.json`. The built arm64 APK passes `zipalign -c -P 16 4`; correct ZIP alignment does not repair incompatible ELF segments. Android documents a compatibility mode which may allow some apps to run, but it is not a native-compliance or reliability guarantee. Upgrade/rebuild the extension dependency and check all shipped native libraries/RELRO plus APK/AAB packaging before claiming 16 KB support. This gate is independent of Firebase, network services or phone availability.

9. **NLLB is an evaluation dependency, not a production-ready licensed foundation.** The current model card explicitly states CC-BY-NC-4.0, research intended use, and that it is not released for production deployment. It excludes domain-specific/document/certified translation use and notes training sequences did not exceed 512 tokens. Existing installed model manifests/docs correctly retain attribution/intended use; the normal model card UI currently shows size/RAM/availability rather than source/license. Keep this honest in product readiness. A user-owned phrasebook avoids reliance on NLLB for its own stored phrase translations; adding one does not make the general-model path commercial/release-approved.

10. **Cached model translations have no independent dataset/model revision key.** The current model is immutable and pinned, so this is a future integration hazard rather than a present wrong-output defect. Once reviewed overrides are added, checking model cache before overrides would return stale machine output after a user correction. Lookup reviewed entries first and do not put reviewed text into the fallback model cache. If a wrapper cache is added, its key must include phrasebook revision and engine/model revision; edits/deletes/reset must invalidate it immediately.

## Recommended bounded local reviewed-phrase design

This is a narrow, testable accuracy and latency improvement: the app can translate an **exact user-reviewed phrase** locally without loading NLLB, while preserving the existing general translator for unmatched phrases on supported devices. It does not supply general ten-language translation on a 2 GB phone.

### Persistence contract

- A pure Kotlin/JVM `ReviewedPhraseStore` under `filesDir/phrases/reviewed.json`; no account, Firebase, cloud request, network dependency, broad storage permission or native engine.
- One envelope: `format: 1`, monotonic `revision`, and reviewed directional records `{id, sourceIso, targetIso, sourceText, targetText, createdAt, updatedAt}`. Persist only entries after explicit user review/save. A model output is not automatically reviewed. Do not auto-generate reverse entries.
- Suggested small limits: 500 directional entries, source <= 256 Unicode code points and <= 1,024 UTF-8 bytes, target <= 512 code points and <= 2,048 UTF-8 bytes, serialized JSON <= 2 MiB. A byte-limited reader must enforce the cap before loading/parsing; JSON validation must reject unsupported format, unknown language codes, blank text, unsupported control characters, duplicate IDs, duplicate normalized `(source, target, sourceText)` keys and invalid/reversed timestamps. Validate the complete candidate before replacing storage.
- Exact matching normalization: Unicode NFC and a documented trim/whitespace policy only. Preserve punctuation, case, digits, negation, accents and Indic joiners/marks. Do not use fuzzy/substrings/case/punctuation stripping that can change meaning. Store original reviewed source/target strings for display.
- Serialize writes under one owner/lock; prepare validated bytes in the same directory, flush/fsync, then atomically replace. Publish the new immutable snapshot/revision only after successful commit. Failed writes/imports retain both the last durable document and last good in-memory snapshot. Android `AtomicFile` is available, but it explicitly requires caller-provided threading protection; a JVM atomic-file helper like the existing store is easier to exercise without Android instrumentation.
- Expose distinct missing/healthy/corrupt/unsupported-format states. Corruption must not be silently interpreted as an empty phrasebook and overwritten. Retain last good snapshot during the same process if possible; on cold load show recovery/reset/import actions. General-model fallback may remain available with honest provenance; phrase-only mode should fail explicitly when a match is unavailable.
- Each successful add/edit/delete/reset/import increments the persistent revision. Deleting/resetting should atomically persist an empty/new document, clearing both memory and any owned recovery copy. Keep only bounded current/recovery data, never an unbounded edit history. Exports are explicit user actions through the existing file-picker pattern.

### Translator integration

- Add a `ReviewedPhraseTranslator` wrapper around the existing `Translator`: same-language bypass; load current immutable phrase snapshot; exact directional lookup; return reviewed target immediately; otherwise delegate to `OfflineTranslator`.
- Do phrase lookup before hardware/model admission and before fallback cache access. NLLB's lazy runtime must not be touched for a phrase hit. Low-RAM devices can use known reviewed pairs and same-language output; unknown cross-language text retains the original with the existing clear failure state.
- Extend `Translated`/message metadata with truthful provenance (`REVIEWED_PHRASE`, `MODEL`, `SAME_LANGUAGE`, optionally cache-origin metadata), rather than marking a manually stored phrase as a machine-result cache hit. Measure lookup/translation stage consistently and keep existing target-language TTS/wire behavior.
- The wrapper takes an injectable fallback for tests; the store exposes a monotonically revised snapshot. Edits/deletes become visible on the next lookup without app restart. Avoid memoizing misses across revisions. If entries are edited during an in-flight model call, define snapshot timing explicitly; a finished model result must never be auto-saved over the user's reviewed record.
- UI slice: save a corrected original-to-target pair from a conversation, edit/delete/search reviewed pairs, and optionally select/play a phrase. Show exact phrase coverage separately from general-model readiness and voice readiness. A small typed-text path can exercise phrase lookup plus playback without recording and without a phone/Firebase service.

## Meaningful cloud/JVM tests for implementation

1. Healthy persistence survives a new store instance; exact reviewed output precedes fallback; fallback is never initialized/called on a hit even if the device/model is unavailable.
2. All ten script/language tags survive round trip; NFC behavior is explicit; case/punctuation/negation/digits and distinct language directions do not cross-match; no accidental automatic reverse entry.
3. Duplicate-key/unknown-language/blank/control/oversized-entry/file/unsupported-format imports are rejected before mutation; failing I/O/atomic move/cancellation retains current document/snapshot/revision.
4. Corrupt/truncated JSON reports degraded storage rather than silently erasing existing data. A validated explicit recovery import replaces it atomically.
5. Add/edit/delete/reset/import advance revision; removing a correction immediately removes its override; no stale wrapper/model cache can resurrect deleted reviewed text.
6. Concurrent lookup/mutation sees complete old or new snapshots; competing mutations are serialized and no updates are lost. Cancellation propagates through fallback. In-flight model output cannot overwrite a later reviewed correction.
7. Phrase-only unmatched translation fails explicitly, preserves original text, and produces no spoken untranslated substitute; matched output uses the selected target language and existing chunked speaker behavior.
8. Cheap speech-pack readiness rejects missing/truncated files; manifest traversal and duplicate declarations reject before activation; known healthy built-ins recover from damaged installed overrides.
9. Explicit translation repair replaces same-length damaged bytes; failed replacement preserves active generation; stale-generation cleanup/deletion cannot remove an active model or leave a cached runtime usable after deletion.
10. Native release policy tests use a fake engine to prove serialized close, no close during active calls, and low-RAM reviewed lookups after release. Keep real ORT translation smoke tests separate from fast bounded persistence tests.

## Public sources consulted

All fetched successfully during this audit; URLs/snippets are recorded in `/workspace/scratch/persistence-sources.json`.

- Android app-specific storage: https://developer.android.com/training/data-storage/app-specific — private persistent storage, app-specific uninstall behavior, and cache eviction/maintenance rules.
- Android `AtomicFile`: https://developer.android.com/reference/android/util/AtomicFile — finish/fail write protocol, syncing/commit, and explicit caller-owned threading protection.
- Android 16 KB page-size guidance: https://developer.android.com/guide/practices/page-sizes — separate ZIP/ELF requirements, prebuilt dependency compatibility, modern AGP/NDK guidance and limitations of page-size compatibility mode.
- Upstream NLLB model card: https://huggingface.co/facebook/nllb-200-distilled-600M/raw/main/README.md — license, research/non-production intended use, input/training and domain limitations.

Runtime skill read for public network research: `cloud-environment:cloud-environment-runtime`; current cloud network policy is observed/enforced unrestricted, and source fetches used the inherited HTTPS proxy/trust configuration.

# Read-only review of root integration

Reviewed current working-tree changes to MainViewModel, MainActivity, MainScreen and BenchmarkLog on Upgradation. No files edited. This review is static; Android lifecycle and real driver behavior were not exercised.

## Findings sent to root

1. **Silent source-language fallback in packAction:** `openPacks`/catalog/import refresh calls `loadLanguage("hi")` whenever the selected source lacks a speak pack. The new typed-input path intentionally permits such languages, so this changes selected/persisted source and phrasebook matching. Preserve selection and initialize newly available ASR for the declared ISO instead.
2. **Readiness failure can retain busy state:** `installTranslation` finally evaluates `translationStore.ready()` before clearing `translation.busy`; a lazy model-store storage-creation failure can repeat in finally and prevent cleanup. Ensure busy/progress reset in all paths and report readiness failure separately.
3. **Speech download cancellation can retain progress entry:** cancellation of `downloadJob` makes `withContext(IO)` throw before its Result reaches `onFailure`, leaving `downloads[id]` busy. Add outer cancellation/finally cleanup.
4. **Ownership cleanup should cover every sessionJob exit:** after SessionManager takes ownership, `unownedTransport` becomes null. Cancellation currently closes only the unowned transport. A locally scoped manager reference closed in finally prevents independent transport-scope survival on unexpected cancellation and avoids an old job clearing a newer session. Existing `leaveSession`/`onCleared` already close the ordinary cancellation path; `trackLink` already catches CSV write errors.
5. **Home language selector disagrees with typed language selection:** Home still disables missing-speech languages while Packs permits selection. Update discovery text/enablement to the new source-language contract.

## Findings sent to other owners

- SessionManager pressEnd used a release timestamp outside its synchronized lexical scope. Session owner confirmed fixed by returning the timestamp from the gate.
- ReviewDraftDialog initially enabled confirm for any nonblank text but session rejects unsupported control characters. Its error was only in session.notice behind the modal. UI owner asked to use the shared input validator and display inline rejection.

## Contracts verified

- Reviewed phrase fallback is lazy and lookup runs before constructing/loading native translation.
- Original and translated conversation contents were removed from normal logcat; persistent diagnostic CSV is now debug-gated and bounded. Manifest already disables Android backup.
- Typed session startup no longer waits for ASR readiness; capture remains gated by model readiness and loading state.
- Activity onStop cancels an active microphone press; SessionManager cancelPress discards capture without recognition/transmission.
- Translation release is serialized behind native calls, and ViewModel destruction has an independent cleanup scope after viewModelScope cancellation.
- Source/target language ISO codes are retained separately and phrasebook usage checks both selected language directions.

These findings should be rechecked after owners finish integration and before the coordinated full build. A static review is not a claim that the Android lifecycle or UI has been executed.

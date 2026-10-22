# "Define" in a Gboard-based keyboard

These notes cover adding a "Define" entry to the keyboard's features panel, next to clipboard and text editing. The entry would open DictBoard's card for the selected or just-typed word. They come from reading [PixelBoard](https://github.com/Akshayykadam/PixelBoard)'s patch source (`pixelboard-patches/`, GPL-3.0) and record what's verified and what isn't.

## What PixelBoard is, and isn't

- PixelBoard is a patch bundle (Morphe/ReVanced style) applied to Google's stock Gboard APK (18.4.1 / 18.3.1, arm64). It's not Gboard source; Gboard is closed source.
- **Patches** (`patches/src/main/kotlin/…`) are Kotlin bytecode patches. They find a method in Gboard and insert smali that calls a static method of the extension.
- **Extension** (`extensions/extension/src/main/java/…`) is plain Java compiled to a dex (`pixelboard-patches.rve`). `GboardPatchesExtensionCarrierPatch` merges its classes into the APK. Calls go through a typed catalog (`RuntimeAbi.kt`, `RuntimeCallId`), and `RuntimeCallEmitter` checks the register counts.
- Example: `GboardFeaturesMenuAccessibilityPatch` inserts a call to `GboardFeaturesMenuAccessibilityRuntime.onAccessPointsPanelInflated(View)` before the `return` of `AccessPointsPanelV2.onFinishInflate()`.

### Corrections to earlier notes

- **No PixelBoard patch adds an entry to the keyboard's features panel.** The "toolbar Restart (↻) button" belongs to PixelBoard's own settings activity (`GboardPatchesSettingsActivity`), not to the keyboard. PixelBoard only *enables* entries Gboard already has, through feature flags (Scan text/OCR, writing tools), and *suppresses* some (`AgenticDictationFeedbackAccessPointProvider`). It hooks the panel itself only for accessibility. A new "Define" entry would therefore be new work, with no existing pattern to copy.
- Not everything a patch targets is obfuscated. View classes inflated from XML keep their names: `com.google.android.libraries.inputmethod.accesspoint.widget.AccessPointsPanelV2`, `AccessPointsPanel`, and `AccessPointsPanelViewPage`. Hooks on their lifecycle methods (`onFinishInflate`, `onVisibilityChanged`) are much less likely to break between releases than hooks into obfuscated logic (`rcq.m`).
- Entries come from `*AccessPointProvider` classes. These are the real extension point to study when adding a native entry.

## Plan

### 1. Now: no keyboard patch (done in this repo)

"Define" in the selected-text menu (`ACTION_PROCESS_TEXT`) works in every app with any keyboard, PixelBoard included. It needs no reverse engineering.

### 2. Next: a thin patch that calls DictBoard

The patch knows nothing about dictionaries. It only gets the word and starts DictBoard's card:

```java
// In the extension, run from the keyboard (an InputMethodService):
InputConnection ic = ime.getCurrentInputConnection();
CharSequence word = ic.getSelectedText(0);
if (word == null || word.length() == 0) {
    word = lastWordBefore(ic.getTextBeforeCursor(64, 0));  // the word just typed
}
Intent card = new Intent(Intent.ACTION_PROCESS_TEXT)
        .setClassName("io.github.bosiopietro.dictboard", "io.github.bosiopietro.dictboard.DefineActivity")
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
ime.startActivity(card);
```

- The intent is explicit, so no `<queries>` entry is needed to *start* the activity. A `<queries><package android:name="io.github.bosiopietro.dictboard"/></queries>` entry is only needed to check that DictBoard is installed first (with `getPackageInfo`) on Android 11+. The patch can add one to Gboard's manifest the way `GboardManifestXmlUtils` adds permissions.
- Starting an activity from the IME is allowed. The system binds the keyboard as the visible input method, which exempts it from background-activity-launch limits. **To verify** on a device.
- Keyboard networking stays in DictBoard, so the patched keyboard needs no new permission.

Where the button goes, from least to most fragile:

1. **The selected-text menu Gboard already shows.** Option 1 covers this.
2. **A view added to the features panel at runtime.** Hook `AccessPointsPanelV2.onFinishInflate` the way the accessibility patch does, and add a view to the panel's grid from the extension. This relies only on unobfuscated view classes, though the grid's structure (child views, styling) is **to verify** on 18.4.1.
3. **A real access point**: a new `AccessPointProvider` registered alongside Gboard's own. This looks native (icon, reorder, pin to toolbar) but depends on obfuscated registration code that changes every release.

First step: a patch that adds a button with option 2 and shows a toast with the selected word. That proves the hook, the extension call and `InputConnection` access before the button is wired to DictBoard.

### 3. Later: a card inside the keyboard

Drawing the card in the keyboard area (like the clipboard drawer) instead of over the app would need either the dictionary inside the extension (the `dictionary` module is plain Kotlin/Java and could go in), or a bound service or ContentProvider in DictBoard that returns a `Definition`. Both tie the patch to Gboard's panel and window internals more tightly, so this comes after option 2 has proven itself.

## Why Kotlin, not WinDict's Rust over JNI

WinDict's lookup core (`src/offline.rs`, `src/dictionary.rs`) is separable from its Win32 code. The only Windows-specific parts are the file mapping, `%LOCALAPPDATA%` and the i18n strings. It could be built with `cargo-ndk`, but the format is simple: a block index and raw-deflate blocks, which `java.util.zip.Inflater(nowrap)` reads directly. A Kotlin port (about 500 lines) avoids the NDK, per-ABI `.so` files and JNI marshalling of `Definition`. It also runs unchanged inside a Gboard extension if option 3 ever needs it. Both implementations are held to the same tests.

## Licensing

- Code derived from PixelBoard (any patch) is GPL-3.0. Keep it in its own repo or directory under that license. DictBoard itself stays MIT, since it only receives an intent.
- WordNet data: CC BY 4.0 plus the WordNet 3.1 license. Their text is in `data/LICENSE-WordNet.txt` and in the APK's assets.
- Don't redistribute patched Gboard APKs. Share the patch source, and let users patch their own copy.

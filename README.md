# DictBoard

[WinDict](https://github.com/BosioPietro/WinDict) on Android: select a word in any app, tap **Define**, and read its definition in a card over the app, offline.

- **Define** appears in the menu of selected text in every app, with any keyboard (`ACTION_PROCESS_TEXT`). Text shared to DictBoard is defined too.
- Opened from the launcher, it's a box to type a word in. It suggests words as you type, puts typos right ("recieve" finds "receive"), and asks "Did you mean…?" about unknown words.
- Inflected forms give their base ("ran" gives "run"). Synonyms and opposites open their own definitions, and Back goes back through them.
- The dictionary is WinDict's: Open English WordNet, about 150,000 words and phrases, bundled in the APK. It's mapped into memory rather than loaded, so lookups take under a millisecond and cost almost no RAM.

## Layout

```
data/          english.wdict (WinDict's dictionary file, unchanged) and its license
dictionary/    the lookup engine: plain Kotlin, no Android, tested on the JVM
app/           the Android app: the card and the type-in box
docs/          notes on putting "Define" into a Gboard-based keyboard
```

`dictionary` reads WinDict's `.wdict` format, the same one WinDict's `tools/wdict.py` writes. It ports WinDict's `src/offline.rs`: lookups, inflections, accents, and the typo-tolerant suggestions. Its tests are WinDict's own, so the two apps read a dictionary the same way, and a dictionary WinDict's tools build (the Italian one, say) works here unchanged.

## Building

Requires JDK 17+.

```sh
./gradlew :dictionary:test        # the engine, no Android SDK needed
./gradlew :app:assembleDebug      # the APK, with the Android SDK (ANDROID_HOME or local.properties)
```

Without an Android SDK, the build leaves out `app`. CI builds both, and keeps the debug APK as an artifact of each run.

## Keyboard integration

The plan is for a Gboard-based keyboard to get a "Define" entry that opens this same card. See [docs/gboard-integration.md](docs/gboard-integration.md).

## License

MIT. `data/english.wdict` is derived from Open English WordNet (CC BY 4.0) and Princeton WordNet 3.1: see [data/LICENSE-WordNet.txt](data/LICENSE-WordNet.txt), which is also shipped in the APK.

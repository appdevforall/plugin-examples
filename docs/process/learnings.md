# Learnings

Cross-session gotchas, discoveries, and patterns worth not re-deriving.

## Compressed assets

- Brotli decompression that produces output ≈ input size (compression ratio ≈ 1.0) is a strong signal the input is corrupt, already-compressed, or encrypted. A real PDF/HTML/text payload compresses 2–4×. The Bookshelf plugin's broken `JavaJavaJavaObjectOrientedProblemSolving.pdf.br` decompressed 5,064,723 → 5,064,711 bytes — 12 bytes of brotli framing overhead on otherwise incompressible noise — which was the smoking gun that it wasn't a PDF at all.

## Android / adb

- Pulling SQLite databases out of an emulator-installed app:
  - First try `adb -s <device> shell run-as <pkg> cat databases/<file> > local.db`. This only works if the app's APK has `android:debuggable="true"`. Code On The Go's release builds do not, so `run-as` returns "package not debuggable" and writes that error string to your output file.
  - On an emulator (root-capable), the fallback is `adb root` (one-time per boot) followed by `adb pull /data/data/<pkg>/databases/<file> .`. This bypasses the debuggable check entirely. Reach for `adb root` early when inspecting/clearing on-device app data — it's far more reliable than fighting `run-as`'s working-directory quirks.
- **Launching Code On The Go via adb — don't use `monkey`.** CoGo *debug* builds bundle **LeakCanary**, which registers its own launcher activity, so `adb shell monkey -p com.itsaky.androidide -c android.intent.category.LAUNCHER 1` (or any bare LAUNCHER intent) may open LeakCanary's "Leaks" screen or a disambiguation chooser instead of the IDE, and the IDE's own activities are not exported (direct `am start` on them is denied). Launch the explicit launcher component: `adb shell am start -n com.itsaky.androidide/.activities.SplashActivity`.
- **Plugin Manager icons are Glide-cached.** CoGo loads plugin icons through Glide's disk cache (`/data/data/com.itsaky.androidide/cache/image_manager_disk_cache`), keyed by path without mtime invalidation. Reinstalling a plugin under the same `plugin.id` with changed `icon_day/night.png` keeps showing the **old** icon (the on-disk extracted icon under `app_plugin_icons/<id>/` updates correctly, but the rendered bitmap is stale). Clear that cache dir (via `adb root`) and restart, or install on a clean device, to confirm an icon change. Template *thumbnails* are not affected — they're re-read from the `.cgt` each time.

## Plugin build & install gotchas

- **On-device install markers hide code changes.** `ai-literacy-course`'s `CourseInstaller` extracts its bundle once and gates it behind `.installed-v<INSTALL_VERSION>`; if the marker exists, extraction *and* `CourseShell.generate()` are skipped. A logic fix (e.g. lesson-item ordering) has zero on-device effect until `INSTALL_VERSION` is bumped — it looks like "the fix didn't work" and costs a device round-trip. Bump the version constant as part of any extraction/generation change.
- **`assemblePlugin` silently ships broken `.cgp`s when downloaded assets are missing.** Plugins with a `downloadAssets` task (`ai-literacy-course` → course ZIP + `pdfjs.zip`; `ndk-installer-plugin`) don't fetch those assets during a plain `assemblePlugin`, and there's no build-time warning — the missing asset only surfaces as a runtime failure on device (`Bundled asset not found: pdfjs.zip` → "Could not prepare the course"). Run `./gradlew downloadAssets assemblePlugin` (or `scripts/update-libs.sh`) and `unzip -l` the `.cgp` to confirm assets are present before handing it over.

## CoGo project templates (Pebble `.cgt`)

- **A bare `${{TAG}}` at end-of-line loses its trailing newline.** CoGo's Pebble renderer trims the newline after a standalone substitution tag, so a `.peb` line like `name: ${{APP_NAME | lower}}` renders as `name: myapp` immediately followed by the *next* line with no break — e.g. `name: myappdescription: ...`, which is invalid YAML and silently corrupts the generated `pubspec.yaml`. `assemblePlugin` and `unzip -l` show nothing wrong; it only surfaces when you generate a project on device. Fix: put the tag inside quotes or otherwise make it not the last token on the line — `name: "${{APP_NAME | lower}}"`. Applies to any generated file where a value must stay on its own line (YAML keys, etc.). Dart's own `$var`/`${expr}` are *not* Pebble tokens (`${` + letter ≠ `${{`), so Dart interpolation passes through untouched.
- **Register templates headlessly via `IdeTemplateService`, not by writing to `/sdcard`.** The correct way to add project templates is a UI-less `IPlugin` that, on `activate()`, builds a `.cgt` from bundled Pebble assets (`createTemplateBuilder(...).addStaticFromAssets(...).build(getPluginDirectory())`) and calls `registerTemplate(file)`; on `deactivate()` it `unregisterTemplate(name)` + deletes the staged file. Model: `pebble-custom-function-template-installer` / `flutter-template`. Writing generated files directly to `Environment.getExternalStorageDirectory()` breaks under scoped storage (`targetSdk` ≥ 30) — that was the defect in the original Flutter Template submission.

## PDF debugging without pdftotext

- When `pdftotext`/`mutool`/`qpdf` aren't installed and you need to read a PDF's /Title or /Author, the Info dictionary is often inside a flate-encoded object stream. Walk every `stream ... endstream` block, `zlib.decompress` each, and regex for `/(Title|Author|Subject|Creator|Producer|Keywords)\s*\(...\)` in the decoded bytes — and for hex-encoded `<FEFF...>` UTF-16BE strings. This recovered the Morelli & Walde authorship of `JavaJavaJava.pdf` in the bookshelf session.

# Learnings

Cross-session gotchas, discoveries, and patterns worth not re-deriving.

## Compressed assets

- Brotli decompression that produces output ≈ input size (compression ratio ≈ 1.0) is a strong signal the input is corrupt, already-compressed, or encrypted. A real PDF/HTML/text payload compresses 2–4×. The Bookshelf plugin's broken `JavaJavaJavaObjectOrientedProblemSolving.pdf.br` decompressed 5,064,723 → 5,064,711 bytes — 12 bytes of brotli framing overhead on otherwise incompressible noise — which was the smoking gun that it wasn't a PDF at all.

## Android / adb

- Pulling SQLite databases out of an emulator-installed app:
  - First try `adb -s <device> shell run-as <pkg> cat databases/<file> > local.db`. This only works if the app's APK has `android:debuggable="true"`. Code On The Go's release builds do not, so `run-as` returns "package not debuggable" and writes that error string to your output file.
  - On an emulator (root-capable), the fallback is `adb root` (one-time per boot) followed by `adb pull /data/data/<pkg>/databases/<file> .`. This bypasses the debuggable check entirely.

## PDF debugging without pdftotext

- When `pdftotext`/`mutool`/`qpdf` aren't installed and you need to read a PDF's /Title or /Author, the Info dictionary is often inside a flate-encoded object stream. Walk every `stream ... endstream` block, `zlib.decompress` each, and regex for `/(Title|Author|Subject|Creator|Producer|Keywords)\s*\(...\)` in the decoded bytes — and for hex-encoded `<FEFF...>` UTF-16BE strings. This recovered the Morelli & Walde authorship of `JavaJavaJava.pdf` in the bookshelf session.

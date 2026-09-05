# rainbow-on-the-go

A Code On The Go plugin that colors matching parentheses, brackets, and braces by
nesting depth — an opening delimiter and its match share a color, cycling through six
colors (red, orange, yellow, green, blue, purple). Separate palettes are tuned for the
editor's light and dark themes.

It implements the generic `EditorDecorationProvider` API: the plugin computes the colored
spans — bracket detection, nesting depth, theme palettes, and skipping strings/comments —
and the editor merges them on top of the normal syntax highlighting. The IDE has no
knowledge of brackets; it just merges plugin-provided spans. The plugin adds no UI, requests
no permissions, makes no network calls, and reads no files.

> **Note:** this plugin requires a build of Code On The Go that includes the
> `EditorDecorationProvider` API and the editor-side decoration merge. All of the rainbow
> logic lives in this plugin.

## Build

```sh
cd rainbow-on-the-go
./gradlew assemblePlugin        # release .cgp -> build/plugin/rainbow-on-the-go.cgp
```

The plugin compiles against `../libs/plugin-api.jar`. If the `EditorDecorationProvider` API
was just added to Code On The Go, refresh the jars first from a local IDE checkout:

```sh
cd ..
./scripts/update-libs.sh --local ../CodeOnTheGo
```

## In-IDE help

A walkthrough is served by the host IDE at
`http://localhost:6174/plugin/org.appdevforall.rainbowonthego/index.html` once installed
(source: `src/main/assets/docs/index.html`). Long-press is wired through
`DocumentationExtension` for the three-tier tooltip help.

# random-xkcd

A small Code on the Go plugin that shows a random xkcd comic in the
editor bottom sheet. Tap for a new comic, double-tap to copy the URL,
triple-tap to copy the image. Long-press the tab for in-IDE help.

Designed as a canonical "this is what a small CoGo plugin looks like"
example. Under 300 lines of Kotlin, every plugin-specific concept
called out where it shows up in the code.

## The tutorial

The full walkthrough lives in `src/main/assets/docs/index.html` —
the **Tier 3 docs page** served by the host IDE at
`http://localhost:6174/plugin/com.codeonthego.xkcdrandom/index.html`
once the plugin is installed.

To read it:

- **Inside CoGo** (the canonical path) — long-press the **XKCD** tab in
  the editor bottom sheet → tap **"See More"** → tap **"Code
  walkthrough"**. The IDE opens the page in an in-IDE WebView.
- **Outside CoGo** — open `src/main/assets/docs/index.html` directly
  in any browser. Renders identically.

The tutorial covers the plugin in 7 steps:

1. Plugin entry point (`IPlugin` lifecycle)
2. Manifest + permissions
3. Bottom-sheet tab UI (`UIExtension`)
4. Tap interactions (single / double / triple)
5. Network fetch over HTTPS
6. Clipboard support (text + image via host `FileProvider`)
7. Three-tier tooltip help (`DocumentationExtension`)

## Build

```bash
./gradlew assemblePlugin
```

Produces `build/plugin/random-xkcd.cgp` — the bundle you sideload
into Code on the Go via **Preferences → Plugin Manager → +**.

## Source layout

```
random-xkcd/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── assets/
    │   ├── docs/                  ← Tier 3 walkthrough (the tutorial)
    │   ├── icon_day.png           ← Plugin Manager icon, light theme
    │   └── icon_night.png         ← Plugin Manager icon, dark theme
    ├── kotlin/com/codeonthego/xkcdrandom/
    │   ├── XkcdRandomPlugin.kt    ← lifecycle + tab + tooltip registration
    │   ├── fragments/XkcdPanelFragment.kt
    │   ├── net/XkcdApiClient.kt   ← HTTP, two endpoints, no auth
    │   ├── net/XkcdComic.kt
    │   └── ui/TapCountClassifier.kt  ← 1/2/3 tap state machine
    └── res/
        ├── layout/fragment_xkcd_panel.xml
        └── values/, values-night/
```

Plus unit tests under `src/test/` for the tap classifier
(JUnit 4 + Truth, no Robolectric).

## Run tests

```bash
./gradlew testDebugUnitTest
```

## xkcd attribution + license

xkcd comics are © Randall Munroe and licensed **CC BY-NC 2.5**
(https://xkcd.com/license.html). This plugin:

- Fetches comics over HTTPS from xkcd.com (no caching, no redistribution
  beyond what the user explicitly copies to their own clipboard).
- Displays an attribution line — *"Comics © Randall Munroe · xkcd.com ·
  CC BY-NC 2.5"* — beneath every comic in the bottom-sheet panel.
- Is itself non-commercial (open-source demo plugin for an
  open-source IDE), consistent with the NC term.

The plugin's own source code is licensed per the surrounding
`plugin-examples` repository (see `LICENSE` at the repo root). xkcd's
license applies only to the comic content the plugin displays.

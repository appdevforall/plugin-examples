# random-xkcd

A small Code on the Go plugin that shows a random xkcd comic in the
editor bottom sheet. Three buttons above the comic — Random / Copy
URL / Copy image — mirror the xkcd.com control bar. Tap the image
to copy URL, double-tap to copy image. Long-press the tab for
in-IDE help.

Designed as a canonical "this is what a small CoGo plugin looks like"
example. Under 300 lines of Kotlin, every plugin-specific concept
called out where it shows up in the code.

## The tutorial

The full walkthrough lives in `src/main/assets/docs/index.html` —
the **Tier 3 docs page** served by the host IDE at
`http://localhost:6174/plugin/org.appdevforall.randomxkcd/index.html`
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
4. UI interactions: buttons + gestures (`GestureDetector.SimpleOnGestureListener`)
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
    ├── kotlin/org/appdevforall/randomxkcd/
    │   ├── XkcdRandomPlugin.kt    ← lifecycle + tab + tooltip registration
    │   ├── fragments/XkcdPanelFragment.kt  ← button wiring + GestureDetector
    │   ├── net/XkcdApiClient.kt   ← HTTP, two endpoints, no auth
    │   └── net/XkcdComic.kt
    └── res/
        ├── layout/fragment_xkcd_panel.xml
        └── values/, values-night/
```

No custom test surface — every piece is small enough that JVM unit
tests would just re-test Android framework behavior. UX is covered
by mobile-MCP / Android QA on real devices.

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

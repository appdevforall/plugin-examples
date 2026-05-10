# Maps

A Code on the Go plugin that scaffolds offline OpenStreetMap apps and
manages cached map regions on-device. Bottom-sheet tab + project
templates for read-only POI maps and annotation maps backed by
MapLibre.

ADFA-2436.

## What it does

- **Project templates:** registers two project templates with the IDE
  template service:
    - "OSM Map - read-only POIs" — minimal MapLibre-backed activity
      that renders a region and shows POIs.
    - "OSM Map - annotate" — adds an annotation FAB, CameraX capture,
      and a Room-backed annotation store on top of the read-only base.
- **Map Regions tab:** a persistent bottom-sheet tab the user opens
  from any open project. Lists the regions already cached on the
  device (`/sdcard/CodeOnTheGo/maps/<region-id>/`) with metadata
  (bbox, tile count, size, last-used date). Stays present even with an
  empty cache so the user sees the "no regions yet" affordance before
  scaffolding their first map project.
- **Region wizard:** a manual-launch sidebar item (`WizardActivity`)
  that walks the user through picking a region, refining a bounding
  box with a `BboxOverlayView`, and downloading the tiles into the
  cache. Result is consumed by both the templates above and any
  sibling plugin that wants pre-staged tiles.
- **Tutorial doc:** ships `assets/docs/osm-tutorial.md` so the IDE's
  doc surface can render an OSM + MapLibre primer.

## Project layout

```
src/main/kotlin/com/codeonthego/gisplugin/
  GisPlugin.kt                       — entry point: templates + UI + docs
  region/                            — Map Regions tab + on-device cache
    RegionCache.kt                     — list / read / delete cached regions
    RegionAdapter.kt                   — RecyclerView adapter
    RegionManagerFragment.kt           — bottom-sheet tab UI
  wizard/                            — bbox picker + tile downloader
    WizardLauncher.kt                  — sidebar entry point launcher
    WizardActivity.kt                  — three-step wizard host
    WizardResult.kt                    — result contract
    Bbox.kt                            — bbox math (tile coords, zoom)
    BboxOverlayView.kt                 — custom view: pan/zoom bbox picker
    CachedRegionPickerAdapter.kt       — step-1 region list adapter
    RegionDownloader.kt                — tile fetch / progress
  templates/                         — project-template emitters
    MapTemplateBuilder.kt              — builds the two .cgt templates
                                         that the IDE registers
```

```
src/main/res/
  layout/
    activity_wizard.xml                — WizardActivity host
    wizard_step1_pick_region.xml       — pick existing region or "new"
    wizard_step2_bbox.xml              — bbox refinement
    wizard_step3_download.xml          — tile download progress
    fragment_region_manager.xml        — bottom-sheet content
    item_region.xml                    — cached-region row
  values/{styles,strings,colors}.xml   — PluginTheme + i18n
  values-night/colors.xml              — dark-mode palette

src/main/assets/
  docs/osm-tutorial.md                 — long-form OSM + MapLibre primer
```

## How tiles work

The plugin never bundles tiles into its `.cgp`. At runtime, tiles are
either:

- **Downloaded on-first-use** through the wizard's `RegionDownloader`
  (fetches OSM tiles for the chosen bbox into
  `/sdcard/CodeOnTheGo/maps/<region-id>/`), or
- **Loaded from a previously-cached region** in the Map Regions tab,
  which lists what's already on the device.

The generated app's `assets/style.json` starts pointed at MapLibre's
public demo tile server (so a freshly-scaffolded project renders on
first run with internet), and is rewritten to read from the cached
`tiles.mbtiles` once the user picks a region. See
`assets/docs/osm-tutorial.md` for the user-facing version of this
story.

## MapLibre dependency

MapLibre Native is **not** a dependency of this plugin. It is
referenced only as a string in `templates/MapTemplateBuilder.kt`,
which writes a `build.gradle.kts` into the generated project that
declares `org.maplibre.gl:android-sdk:11.11.0`. The plugin itself is
pure AndroidX + Material + kotlinx-coroutines; MapLibre is pulled
into the user's project (not into the plugin's `.cgp`) when they
scaffold from one of the two templates.

## Building

```
./gradlew clean assemblePluginDebug
```

Produces `build/plugin/gis-plugin.cgp`. Drop the `.cgp` into the
IDE's plugin directory to install.

## Structural notes (from migration)

- Package name: kept `com.codeonthego.gisplugin` (existing namespace).
- `pluginName = "gis-plugin"` (matches `applicationId`).
- `minSdk = 28` — lifted from the original 26 to align with Forms /
  Beepy.
- Kotlin 2.3.0, AGP 8.11.0, Gradle 8.14.3 — matches Forms / Beepy.
- `compileOnly(files("../libs/plugin-api.jar"))` replaces the CoGo
  `project(":plugin-api")` dependency.
- `checkDebugAarMetadata` / `checkReleaseAarMetadata` disabled to work
  around plugin-builder pipeline limits (same workaround Beepy and
  Forms use).
- No JVM tests yet (source had no `src/test/`); add when the wizard
  or template-builder logic warrants extraction into testable units.

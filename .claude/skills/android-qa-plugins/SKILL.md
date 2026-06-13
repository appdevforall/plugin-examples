---
name: android-qa-plugins
user-invocable: true
description: Use when running the android-qa skill, or when using mobile-mcp or adb, to test a CoGo (CodeOnTheGo) plugin in this repo. Supplies the CoGo-specific device-QA details — plugin install flow, UI navigation, plugin-load failure modes, and CoGo emulator bootstrap — on top of the generic android-qa methodology.
---

# android-qa-plugins

Load alongside the generic `android-qa` skill; this adds the CoGo-plugin specifics — install flow, IDE navigation, plugin-load failure modes, and CoGo emulator bootstrap.

A **CoGo plugin** is a `.cgp`-packaged add-on installed into the CoGo IDE through its Plugin Manager. Plugin APKs load via a `DexClassLoader`, which is the root cause of several of the failure modes below.

## Clean state for CoGo plugins / the CoGo IDE

⚠️ The generic `android-qa` skill's destructive-reset safety guard applies here too — `pm clear com.itsaky.androidide` wipes the user's projects, so prefer uninstalling just the plugin.

| Target | Clean state means |
|---|---|
| **CoGo plugin** | Plugin NOT installed. The flow exercises install + restart + first-time activation. If already installed: uninstall via Plugin Manager + restart, *then* start recording. |
| **CoGo IDE itself** | App data cleared (`pm clear com.itsaky.androidide`) or a fresh APK install. Exercises onboarding, permission grants, project creation. |

## Installing a `.cgp`

1. Drawer → IDE preferences → Plugin Manager → **+** (bottom-right FAB ~956, 2081).
2. File picker → hamburger → **Downloads** (newly-pushed `.cgp` files don't appear under "Recent" until interacted with, so go to `/sdcard/Download/` directly) → tap the `.cgp`.
3. **Install Plugin** dialog → **Install**.
4. **Restart required** → **Restart now**.
5. After restart the plugin shows in Plugin Manager with status **Enabled**.

**Build plugins as a release variant for install.** Production CoGo (Android 16) silently drops trailing manifest meta-data entries when parsing a debug `.cgp` via the legacy `getPackageArchiveInfo` API, which trips a debug-only icon-asset validator. Build with `./gradlew assemblePlugin` (release) — the validator only fires when `ApplicationInfo.FLAG_DEBUGGABLE` is set, which release builds clear.

## CoGo UI navigation cheat sheet

Use when the target is the CoGo IDE or a CoGo plugin. Any coordinates below are illustrative — confirm with `mobile_list_elements_on_screen` on your device.

**Hamburger drawer (`com.itsaky.androidide:id/navigation`):**
| Drawer entry | Approx center | Notes |
|---|---|---|
| File tree | 79, 194 | Default open state |
| Build variants | 79, 352 | |
| Terminal | 79, 510 | |
| IDE preferences | 79, 668 | **Path to Plugin Manager** |
| Close this project | 79, 826 | |
| Help | 79, 984 | |

Dismiss the drawer without navigating via the "Close project menu" button (top-right ~934, 171). BACK works too but may nest.

**IDE preferences screen** (after the drawer entry above): Plugin Manager is the 6th item, around `(204, 1414)`. The list also has General / Editor / Build & Run / Termux / Git / About / Developer Options.

**Bottom sheet — plugin tabs live at the far right:**
1. Collapsed by default at y ≈ 1924–2205.
2. Expand: `mobile_swipe_on_screen direction=up x=540 y=2000 distance=1500`.
3. Built-in tabs (left→right): Build Output, App Logs, IDE Logs, Diagnostics, Search Results, Debugger, Git. Plugin tabs appear after them at the far right.
4. Scroll the tab row right to reveal plugin tabs: `direction=left x=700 y=560 distance=1400` (expanded state).
5. Tap the plugin tab to switch the ViewPager to that plugin's Fragment.

**Plugin view IDs** are namespaced under the plugin's package, e.g. `com.codeonthego.xkcdrandom:id/xkcd_image`. Treat `mobile_list_elements_on_screen` as authoritative — coordinate hints from past sessions can shift across CoGo restarts.

**Three-tier in-app tooltip (per plugin)** — CoGo's per-plugin help:
1. **Long-press** the plugin's bottom-sheet **tab title** (not the content) for ~2s → tier-1 popup + a "+ See more" link near `(540, 1294)`.
2. "See more" → tier-2 popup with a "Learn more" link around `(300, 1170)`.
3. "Learn more" → opens a full-screen browser to `http://localhost:6174/plugin/<plugin-id>/<uri>`.

**State persists across restart:** `am force-stop com.itsaky.androidide` + relaunch via `monkey -c LAUNCHER 1` returns to the last open project's EditorActivity. The bottom sheet collapses on restart but the last-active plugin tab stays selected once reopened.

## Plugin-load failure modes (logcat)

Plugin APKs load via a `DexClassLoader`, so the host app's classloader has no visibility into plugin classes. Grep logcat (per the generic skill's "Read logcat" pattern) for:

- `ActivityNotFoundException: Unable to find explicit activity class {<plugin>/.X}` — plugin Activities don't register via the plugin's `DexClassLoader`. Don't `startActivity()` a plugin Activity directly; route through Fragments or recipes.
- `Resources$NotFoundException` during Fragment inflation — the plugin Fragment isn't using `PluginFragmentHelper.getPluginInflater(...)`.
- `InflateException` on heavy views (MapView, custom GL) — a historical risk on low-end devices.

If a plugin bundles native libraries and they fail to load, confirm they were extracted at install time:
```bash
adb -s $DEVICE_SERIAL shell run-as com.itsaky.androidide ls app_plugin_native_libs/
```
Absence of `<plugin-id>/` there means the `.so` files didn't extract — a strong signal the plugin's `useLegacyPackaging` / `native.code` permission setup is wrong.

## CoGo emulator bootstrap

The generic skill covers bringing up the AVD (launch, wait for boot, data-partition size, freeing RAM). When the target is **CoGo** on an emulator (vs the physical A56), you additionally must:

1. Build CoGo locally (e.g. `./gradlew :app:assembleV8Debug` in the team build env).
2. Install the APK: `adb install -r app/build/outputs/apk/v8/debug/CodeOnTheGo-v8-debug-*.apk`.
3. Push the assets zip: `adb push app/build/outputs/assets/assets-arm64-v8a.zip /sdcard/Download/`.
4. Pre-grant the 4 onboarding permissions:
   ```bash
   adb shell pm grant com.itsaky.androidide android.permission.POST_NOTIFICATIONS
   adb shell appops set com.itsaky.androidide MANAGE_EXTERNAL_STORAGE allow
   adb shell appops set com.itsaky.androidide REQUEST_INSTALL_PACKAGES allow
   adb shell appops set com.itsaky.androidide SYSTEM_ALERT_WINDOW allow
   ```
5. Drive the onboarding (intro card → privacy dialog → permissions screen → finish installation).

CoGo extracts the Termux bootstrap from the assets zip on first launch (~30–60 s). The AVD needs `disk.dataPartition.size=16G` (the 6 GB default fails CoGo's bootstrap) — set this when bringing up the emulator per the generic skill.

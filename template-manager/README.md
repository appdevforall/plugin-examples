# TemplateManagerPlugin

A [Code On the Go (COGO)](https://github.com/appdevforall/CodeOnTheGo) IDE plugin for
managing `.cgt` project/file templates directly on-device — browse installed
templates, install new ones from your Downloads folder, and uninstall or delete them,
all from a single screen inside the IDE.

## What it does

The plugin adds a **TemplateManagerPlugin** entry to the IDE's left sidebar (and a
matching editor tab) that shows a card list of every `.cgt` template it can find:

- **Installed templates** — every `.cgt` registered in the IDE's template store
  (`$IDE_HOME/templates`), shown with a green **Installed** status. These are the
  templates that appear in the IDE's *New Project* / *New File* wizard.
- **Available templates** — every `.cgt` sitting in `/sdcard/Download`, shown with a
  red **Not installed** status, ready to be installed.

Each card shows the template's name, version, description, and source filename. A
`.cgt` file can bundle more than one template (the IDE's own `core.cgt` bundles nine);
when it does, the card shows a **"Contains N templates"** indicator, and tapping the
card (or its **View templates** menu entry) opens a sub-screen with one card per
bundled template — each with its own **Details** action.

## Per-card actions

Each card has an overflow (⋮) menu:

| Card state | Single-template | Multi-template |
|---|---|---|
| Installed | **Uninstall**, **Details** | **Uninstall**, **View templates** |
| Not installed (in Downloads) | **Install**, **Details**, **Delete** | **Install**, **View templates**, **Delete** |

- **Install** — registers the template with the IDE and **moves** the file out of
  Downloads into the template store (it no longer appears as a Downloads entry).
- **Uninstall** — unregisters the template and **moves** it back to Downloads under its
  original filename, where it reappears as *Not installed*.
- **Delete** — permanently removes the `.cgt` from Downloads, after a confirmation
  dialog.
- **Details** — shows a single template's metadata (version, description, and any
  optional wizard parameters declared under `parameters.optional`); scrollable.
- **View templates** — for a multi-template `.cgt`, opens the per-template card
  sub-screen described above; each of those cards has its own **Details**.

Install / Uninstall / Delete always operate on the whole `.cgt` file, since that's the
unit the IDE registers.

## Screenshots

<table>
  <tr>
    <td align="center" width="50%">
      <img src="docs/screenshots/main-list.png" width="240" alt="Template list"><br>
      <sub>Template list — installed (green) and available (red) cards, with a
      "Contains N templates" indicator for multi-template files.</sub>
    </td>
    <td align="center" width="50%">
      <img src="docs/screenshots/file-menu.png" width="240" alt="Per-file overflow menu"><br>
      <sub>Per-file ⋮ menu — Install / Details / Delete for a Downloads file.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <img src="docs/screenshots/templates-subscreen.png" width="240" alt="Multi-template sub-screen"><br>
      <sub>Opening a multi-template <code>.cgt</code> shows one card per bundled
      template, each with its own ⋮ → Details.</sub>
    </td>
    <td align="center" width="50%">
      <img src="docs/screenshots/template-details.png" width="240" alt="Per-template details"><br>
      <sub>A template's Details — version, description, and the optional wizard
      parameters declared under <code>parameters.optional</code>.</sub>
    </td>
  </tr>
</table>

## Building

Requires the COGO `plugin-api` (vendored at `libs/plugin-api.jar`) and the
`com.itsaky.androidide.plugins.build` Gradle plugin.

```bash
./gradlew assemblePluginDebug     # build/plugin/templatemanagerplugin-debug.cgp
./gradlew assemblePlugin          # build/plugin/templatemanagerplugin.cgp  (release)
```

## Prebuilt plugin

A prebuilt release `.cgp` is checked into the repo at
[`prebuilt/templatemanagerplugin.cgp`](prebuilt/templatemanagerplugin.cgp)
so you can install it without running a local Gradle build — just copy that file to the
device and follow the steps below. (Rebuild from source with the commands above if you
need the latest changes.)

## Installing

1. Copy a `.cgp` to the device (e.g. into `Download/`) — either the
   [prebuilt one](prebuilt/templatemanagerplugin.cgp) or your own build from
   `build/plugin/`.
2. In Code On the Go, open **Settings → Plugin Manager**.
3. Tap the **+** button, pick the `.cgp` file, and confirm.
4. Restart the IDE when prompted.

The plugin then appears in the left sidebar.

> **Upgrading an already-installed copy?** Code On the Go compares the `.cgp`'s
> signing certificate against the installed one and refuses the install if they
> differ, showing *"…was installed from a different build variant. Uninstall it
> before installing this version."* Despite the wording, this is a **signature
> mismatch** — release builds here aren't signed with a stable key, so it triggers on
> essentially every rebuild, as well as when switching between debug and release.
> (Debug rebuilds on the same machine share the debug keystore, so those can be
> replaced in place.) When you hit it, **uninstall** the existing plugin first
> (its ⋮ menu → Uninstall), restart, then install the new `.cgp`.

## Plugin manifest

| Field | Value |
|---|---|
| `plugin.id` | `org.appdevforall.templatemanagerplugin` |
| `plugin.main_class` | `org.appdevforall.templatemanagerplugin.TemplateManagerPlugin` |
| `plugin.permissions` | `filesystem.write` |
| `plugin.min_ide_version` | `1.0.0` |

`filesystem.write` is the only permission requested — it's what
`IdeTemplateService.registerTemplate()` / `unregisterTemplate()` require. Reading the
`.cgt` files themselves and moving them in/out of Downloads uses direct file access
(the host app already holds `MANAGE_EXTERNAL_STORAGE`).

## Project layout

```
src/main/
├── kotlin/org/appdevforall/templatemanagerplugin/
│   ├── TemplateManagerPlugin.kt              # IPlugin + UIExtension + EditorTabExtension + DocumentationExtension
│   ├── fragments/TemplateManagerPluginFragment.kt  # the template dashboard UI + install/uninstall/delete logic
│   ├── adapters/CgtFileAdapter.kt            # main list adapter + per-file overflow menu
│   ├── adapters/TemplateCardAdapter.kt       # per-template cards for the multi-template sub-screen
│   └── models/CgtFileItem.kt                 # card model + TemplateMetadata
├── res/                                       # layouts, PluginTheme, day/night colors, drawables
├── assets/                                    # icon_day.png / icon_night.png (plugin manager icons)
└── AndroidManifest.xml                        # plugin.* metadata
```

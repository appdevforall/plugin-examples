# Pair

A plugin for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) that turns the IDE into a real-time collaborative editor. Two phones on the same WiFi share one editing session: one device **hosts**, others **join** by typing the host's `ip:port` or scanning its QR code, and from then on edits, cursors, and file opens flow between devices as they happen — no server, no cloud, no signup.

It surfaces as a **Pair** tab in the editor. Host a session and an invite card shows the address and a QR code; join one and the peer list fills in. When the host types in `MainActivity.kt`, the guest sees the same file open and the text arrive keystroke by keystroke, with each peer's live position shown in the peer list.

## Building

```sh
cd pair
./gradlew clean assemblePlugin
```

The `.cgp` lands in `build/plugin/`. Install it from inside CodeOnTheGo via the Plugin Manager. Always `clean` first — the plugin builder copies the built APK into the `.cgp` and then deletes the source APK, so an incremental build can package an empty artifact.

## How it works

- **Pair tab** — contributes an editor tab and sidebar entry via `EditorTabExtension` + `UIExtension`; the whole UI is Jetpack Compose (Home, Host, Guest, and QR-scan screens).
- **Edit sync** — subscribes to the host IDE's `DocumentChangeEvent` on the EventBus for local edits and replays remote edits through `IdeEditorService.replaceRange(...)`. A single loopback guard stops an applied remote edit from rebroadcasting as a local one.
- **File-relative identity** — a file's wire identity is its path **relative to the project root** (`IdeProjectService.getCurrentProject().rootDir`). The sender strips the root; the receiver re-anchors to its own, so a session works across two devices whose projects live at different absolute paths.
- **Presence** — cursor positions ride alongside edits; each peer gets a color, shown in the peer list and (with the extended API below) as an inline caret inside the editor.
- **Transport** — a star topology over `ws://`: the host runs a `WebSocketServer`, each guest opens one `WebSocketClient`, and the host echoes messages to the other guests. Messages are compact JSON (`hi` / `edit` / `cur` / `fo` / `fc` / `ff` / `sync` / `bye`).
- **Conflict handling** — per-file sequence numbers with the host as authority; a divergence flags the session *out of sync* rather than dropping the edit, and the host can push an authoritative resync of the full file.
- **Session history** — past sessions persist as JSON under `IdeEnvironmentService.getPluginDataDirectory()`; the Home screen lists them to rename, delete, or reconnect.

## Source layout

```
pair/
├── build.gradle.kts, settings.gradle.kts, proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml        plugin id, main class, icons, permissions
    ├── assets/                    icon_day.png, icon_night.png (190×190 interlocking-rings mark)
    └── kotlin/com/appdevforall/pair/plugin/
        ├── PairPlugin.kt          IPlugin + EditorTabExtension + UIExtension entry
        ├── data/                  wire protocol, WebSocket server/client, session store
        ├── domain/                EditBroker orchestrator, observer/applier, path mapping, peer registry
        ├── ui/                    Compose theme, components, and screens
        └── util/                  LAN discovery, QR decode
```

## Dependencies from `libs/`

- `../libs/plugin-api.jar` — the plugin API surface (`IPlugin`, extensions, `Ide*Service`); `compileOnly`, provided by the IDE at runtime.
- `../libs/gradle-plugin.jar` — the `com.itsaky.androidide.plugins.build` Gradle plugin that packages the `.cgp`.
- `../libs/eventbus-events.jar` — the editor and file `*Event` types Pair subscribes to on the EventBus.
- `../libs/shared.jar` — `com.itsaky.androidide.models.Range`, the type of `DocumentChangeEvent.changeRange` that Pair reads.

Jetpack Compose is linked `compileOnly` (host-provided), not bundled.

> **Note:** Pair requires an extended `plugin-api` beyond the current `stage` baseline — `IdeProjectService.openProject(File)` (open a project after a pull-model sync) and `IdeEditorService.showPeerCursor` / `hidePeerCursor` / `clearPeerCursors` (inline remote-cursor decoration). These land on the `feat/ADFA-4419-remote-peer-editor-decoration` branch. If `assemblePlugin` fails with unresolved references to those symbols, the shared `libs/` jars are older than the API Pair needs; refresh them from a CodeOnTheGo build that includes the extensions (`../scripts/update-libs.sh --local <path-to-CodeOnTheGo> --ref feat/ADFA-4419-remote-peer-editor-decoration`).

## License

Pair is an open-source example plugin for Code on the Go. Its source is licensed per the surrounding `plugin-examples` repository (see `LICENSE` at the repo root). It makes no cloud calls — all traffic stays on the local network between the paired devices.

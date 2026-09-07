# client-time-tracker

A time-tracking and invoicing plugin for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo). It tracks billable coding sessions per project and generates invoices as PDF, Excel, or CSV.

Surfaces as a **Client Time Tracker** sidebar item and editor tab. Sessions start/stop automatically from editor activity, and each tracked project carries its own client, rate, currency, and tax settings.

## Building

```sh
cd client-time-tracker
./gradlew assemblePlugin
```

The `.cgp` lands in `build/plugin/`. Install it from inside CodeOnTheGo via the Plugin Manager.

## How it works

- **Session tracking** — `SessionTracker` subscribes to the IDE's editor/project events (`DocumentSaveEvent`, `OnPauseEvent`, `OnResumeEvent`, `ProjectInitializedEvent`) over the host EventBus to open, heartbeat, and close work sessions.
- **Storage** — a standalone Room database (`contractor.db`) under the plugin's private data directory, with three tables: `tracked_projects`, `work_sessions`, `invoices`. It never touches the IDE's own databases.
- **Export** — `fastexcel` for `.xlsx`; CSV and PDF are generated directly.
- **UI** — Views + ViewBinding, MVI view models (`BaseViewModel`).

## Dependencies from `libs/`

Beyond the usual two shared jars, this plugin needs a **third**:

- `../libs/plugin-api.jar` — the plugin API surface (compile-only).
- `../libs/gradle-plugin.jar` — the `.cgp` packaging Gradle plugin.
- `../libs/eventbus-events.jar` — the IDE's event classes (`com.itsaky.androidide.eventbus.events.*`). These are **not** part of `plugin-api.jar`; any plugin that subscribes to host events over EventBus needs them at compile time. Provided by the IDE at runtime.


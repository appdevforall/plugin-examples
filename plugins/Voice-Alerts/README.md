# Voice Alerts

A Code On The Go plugin that plays a short sound on build start, success, and failure. Useful when your eyes are on the editor and you want an audible cue that the build has turned over.

## Behaviour

| Event            | Sound          |
| ---------------- | -------------- |
| Build starts     | `started.wav`  |
| Build succeeds   | `finished.wav` |
| Build fails      | `failed.wav`   |

Sounds route through the `USAGE_ASSISTANCE_SONIFICATION` audio attribute, so they respect media/ringer volume and DND rules rather than interrupting like a notification.

## How it works

- On `activate()`, the plugin builds a `SoundPool`, loads the three raw WAVs via `openRawResourceFd`, and subscribes to `IdeBuildService` build events.
- `SoundPool.load()` is asynchronous — the plugin tracks which samples have finished decoding and only calls `play()` on ready samples. If a build event fires during the ~few hundred ms before load completes, the event is silently skipped (builds are almost always longer than load, so this is rarely observable).
- On `deactivate()`, the listener is removed and the `SoundPool` is released.

## Replacing the sounds

Drop your own WAVs into `Voice-Alerts/src/main/res/raw/` using the existing filenames (`started.wav`, `finished.wav`, `failed.wav`) and rebuild. Keep them short — `SoundPool` is intended for clips under ~1 MB decoded.

## Building

```
../../gradlew assemblePlugin
```

Output is a `.cgp` plugin package in `Voice-Alerts/build/plugin/`, installable from the IDE's plugin manager.


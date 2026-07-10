# Flutter Template

A [Code on the Go](https://github.com/appdevforall/CodeOnTheGo) plugin that adds **Flutter starter
project templates** to the IDE's New Project screen, alongside the built-in core templates.

It contributes five templates, one per state-management approach:

| Template | State management |
|---|---|
| Flutter Basic | none (plain `setState`) |
| Flutter BLoC | `flutter_bloc` |
| Flutter Provider | `provider` |
| Flutter GetX | `get` |
| Flutter Riverpod | `flutter_riverpod` |

Each generates a small, idiomatic Flutter project (a counter app) with `pubspec.yaml`, `lib/`,
and `analysis_options.yaml`, substituting the app name and package name you enter in the New
Project dialog.

## How it works

The plugin is a headless installer. On `activate()` it registers each template with the IDE via
`IdeTemplateService`, building a `.cgt` from the Pebble skeletons bundled under
`src/main/assets/templates/<Variant>/`; on `deactivate()` it unregisters them and deletes the
staged files. There is no UI — the templates simply appear in **New Project** after the plugin is
enabled. This mirrors the `pebble-custom-function-template-installer` example.

## Building

```sh
cd flutter-template
./gradlew clean assemblePlugin
```

The `.cgp` lands in `build/plugin/fluttertemplate.cgp`. Install it from inside Code on the Go via
the Plugin Manager, then open **New Project** to see the Flutter templates.

## Note on the Flutter SDK

This plugin **scaffolds project files only** — it does not install the Flutter/Dart SDK. Code on
the Go does not yet ship an on-device Flutter toolchain, so building or running a generated project
requires Flutter on a separate machine (or a future on-device SDK installer). Enter an app name
that is a valid Dart package identifier (lowercase, no spaces — underscores are fine); it is
lower-cased for `pubspec.yaml`'s `name:` field.

## Credit

Contributed by **Ali** via the Code on the Go community submission process, rebuilt on the IDE's
template system.

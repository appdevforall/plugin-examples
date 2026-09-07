# Project to Template

A [Code On The Go](https://github.com/appdevforall/CodeOnTheGo) plugin (`.cgp`)
that converts the Android project currently open in the IDE into a Code On The
Go (`.cgt`) template bundle — driven entirely from a tab inside the IDE, with an
option to install the result straight into the New Project template picker. The
original project directory is never modified: the plugin copies it into a new
bundle directory next to it and templatizes the copy.

This started as a `templatize_project.py` desktop script, was ported to a
standalone Kotlin/Compose Android app, and is now a Code On The Go plugin so it
runs inside the IDE with no separate app to install.

## UI

The plugin adds a **Project to Template** item to the IDE's sidebar (under
"tools"), which opens a tab with:

- **Project** — read-only, always the project currently open in the IDE
  (via `IdeProjectService.getCurrentProject()`). If no project is open,
  conversion is disabled until one is.
- **Template name** — written into the generated `template.json`'s `name`
  field, and used as the template subdirectory / `templates.json` `path` entry.
- **Dry run** — preview the substitutions against a disposable copy without
  writing or deleting anything.
- **Skip cleanup** — skip `build/` and keystore removal.

**Convert to Template** stays disabled until a project is open and a template
name is entered. Tapping it runs the pipeline on a background thread and streams
a live log (`[OK]` / `[SKIP]` / `[REMOVED]` / `[REVIEW]`), followed by a summary
with the output paths. On a real (non-dry-run) conversion, an **Install
Template** button then appears below the log — so you can review the log first —
and tapping it registers the `.cgt` directly with the IDE via
`IdeTemplateService`, so it shows up immediately in the New Project template
picker.

## What the conversion does

For each run, it:

1. Creates a new output directory (`<project-dir>-cgt`, next to the project).
2. Copies the project into a subdirectory named after the template name,
   skipping `.git`, `.gradle`, `.cg`, `.idea`, `.claude`, `.androidide`, and
   `release.properties`.
3. Templatizes the copy: replaces concrete values (Gradle/AGP/Kotlin versions,
   package name, app name, SDK levels, Java compatibility levels) with Pebble
   tokens (`${{ TOKEN }}`), saving each modified file with a `.peb` suffix;
   removes `build/` directories; deletes keystore files (`*.jks`, `*.keystore`,
   `*.p12`); and flags files that may contain machine-specific or personal
   information (`local.properties`, `google-services.json`, `key.properties`,
   `GoogleService-Info.plist`) for manual review.
4. Writes a `templates.json` at the top of the output directory.
5. Adds a `template/` directory containing `template.json` (the parameter/
   metadata schema) and a placeholder `thumb.png` (replace with a real
   thumbnail before shipping).
6. Zips the output directory into a `<template-name>.cgt` file next to it.
7. If the user opts in, registers that `.cgt` with the IDE via
   `IdeTemplateService.registerTemplate(...)`.

Both Kotlin DSL (`build.gradle.kts`) and Groovy DSL (`build.gradle`) projects
are supported. Assumes the app module is named `app` (falls back to the first
module).

## Pebble whitespace quirk

A line/segment that ends with a Pebble token must be followed by at least one
character of whitespace, or the parser eats the next character (often a newline,
quote, semicolon, or dot). The conversion inserts a sacrificial space after
every inserted token — after the closing quote when the token is immediately
followed by one, so the quote itself isn't eaten.

## Project layout

- `Templatizer.kt` — the substitution pipeline, pure `java.io.File` logic with
  no dependency on the plugin API (easy to reason about and test in isolation),
  plus the `.cgt` bundle writer/zipper.
- `ProjectToTemplatePlugin.kt` — the `IPlugin` entry point: registers the
  sidebar item and the main editor tab, and provides in-IDE tooltip help.
- `fragments/ProjectToTemplateFragment.kt` — the tab's UI: the input form,
  background execution, live log, and the install-template flow via
  `IdeTemplateService`.
- `src/main/assets/docs/index.html` — the offline Tier 3 help page.

This plugin uses the shared repo-root `../libs/plugin-api.jar` +
`../libs/gradle-plugin.jar` and the repo-root `../gradlew` — it does not bundle
its own copies. See the repo `CLAUDE.md` for the monorepo conventions.

## Building

From this directory, using the repo-root Gradle wrapper:

```
../gradlew assemblePlugin        # release .cgp -> build/plugin/project-to-template.cgp
../gradlew assemblePluginDebug   # debug .cgp   -> build/plugin/project-to-template-debug.cgp
```

Install the resulting `.cgp` through Code On The Go's Plugin Manager. A
`local.properties` with `sdk.dir=<Android SDK path>` is required to build.

## Next steps

After running a conversion, inspect the generated `.peb` files in the output
bundle to confirm the substitutions and spacing look right, and replace the
placeholder `thumb.png` before distributing the `.cgt` file.

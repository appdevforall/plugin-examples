# plugin-examples

Reference plugins for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo). Each folder is a fully self-contained Gradle project that builds to a `.cgp` installable plugin file.

See the official [plugin documentation](https://www.appdevforall.org/codeonthego/help/exp-plugins-top.html) for concepts, the plugin API surface, and install workflow.

## Examples

| Plugin                                             | Purpose                                                           |
| -------------------------------------------------- | ----------------------------------------------------------------- |
| [`Beepy/`](Beepy/)                                 | Plays a sound when a build starts, succeeds, or fails.            |
| [`apk-viewer/`](apk-viewer/)                       | Inspects an APK's contents and surfaces a structural breakdown.   |
| [`markdown-preview/`](markdown-preview/)           | Renders Markdown files with a live preview pane in the editor.    |
| [`keystore-generator/`](keystore-generator/)       | Generates signing keystores from inside the IDE.                  |
| [`snippets/`](snippets/)                           | Adds user-managed code snippets with prefix-triggered expansions. |

## Building a plugin

Every plugin is a standalone Gradle project that shares two jars from this repo's root `libs/` folder.

```sh
cd Beepy
./gradlew assemblePlugin
```

The resulting `.cgp` file lands under the plugin's `build/plugin/` directory. Install it from inside CodeOnTheGo via the Plugin Manager.

## The `libs/` folder

Every plugin depends on two jars produced by the CodeOnTheGo source tree:

- **`plugin-api.jar`** — the interface surface a plugin implements (`IPlugin`, `BuildStatusListener`, etc.). Used as `compileOnly` at build time; provided by the IDE at runtime.
- **`gradle-plugin.jar`** — the custom Gradle plugin (`com.itsaky.androidide.plugins.build`) that packages a compiled Android library into a `.cgp` file. Applied via `classpath` in each plugin's `settings.gradle.kts`.

Both jars live in `libs/` at the repo root; each plugin references them via `../libs/*.jar`. This means a plugin folder is **not standalone in isolation** — copying just `Beepy/` elsewhere will break its build until you also bring `libs/` along. The expected workflow is: clone the whole repo, work inside one of the example folders.

## Refreshing `libs/`

Whenever CodeOnTheGo changes the plugin API or the build plugin, the jars need to be rebuilt. Two ways to do that:

### GitHub Action (normal path)

Go to [Actions → **Update libs from CodeOnTheGo**](../../actions/workflows/update-libs.yml) and click **Run workflow**. It will clone CodeOnTheGo at the branch or tag you specify (default: `stage`), build both jars, and commit them directly to the default branch.

### Locally

```sh
./scripts/update-libs.sh                          # builds from github.com/appdevforall/CodeOnTheGo@stage
./scripts/update-libs.sh --ref v1.2.0             # pin to a tag or branch
./scripts/update-libs.sh --local ../CodeOnTheGo   # use an existing local checkout instead of cloning
```

First local run clones CodeOnTheGo into `.cache/CodeOnTheGo/` (gitignored); subsequent runs `git pull` in place. Review the diff in `libs/` and commit if you're happy with it.

## Adding a new plugin example

1. Copy `Beepy/` to a new folder (e.g. `MyPlugin/`).
2. In `MyPlugin/settings.gradle.kts`, change `rootProject.name` to `MyPlugin`.
3. In `MyPlugin/build.gradle.kts`, update `pluginBuilder { pluginName = ... }` and `android { namespace ... applicationId ... }`.
4. In `MyPlugin/src/main/AndroidManifest.xml`, update the `plugin.id`, `plugin.name`, `plugin.main_class`, and any other metadata.
5. Replace the source under `MyPlugin/src/main/kotlin/...` with your implementation.
6. Add a row to the **Examples** table above.
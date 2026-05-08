# Forms

A Code on the Go plugin that turns a paper form into a working
form-filling app. Photo of a paper form → schema (typed input fields
with labels and order) → installed mini-app on the user's device.

ADFA-2435.

## What it does

- **Capture step:** the user (an NGO HQ admin) photographs an empty
  paper-form template.
- **Detect step:** an extractor (CV detector + OCR, optionally a
  cloud LLM) pulls input regions, types, and labels off the photo.
- **Wizard step-2 review:** the admin confirms / corrects the
  detected fields in a four-step wizard.
- **Generate step:** the plugin emits a `.cgp` form-app the field
  workers can install and use offline.

The plugin's own scope is the wizard + schema + template-emitter.
The CV detection layer and the labelled-OCR layer are pluggable; the
benchmark page (linked below) measures eleven candidate extraction
backends and the team picked one for R2.

## Project layout

```
src/main/kotlin/com/appdevforall/forms/plugin/
  FormSchema.kt              — types FormField / FieldType / FormSchema / SubmitConfig
  FormsPlugin.kt              — plugin entry point + sidebar registration
  ocr/                        — heuristic field-classifier (pure JVM, ML Kit-ready)
    HeuristicFieldClassifier.kt
    Heuristics.kt
    ClassifierLocale.kt       — per-locale keyword tables (EN bundled)
    OcrResult.kt              — pure-JVM mirror of ML Kit's Text shape
    MlKitOcrAdapter.kt        — documentation stub for the ML Kit bridge
  panel/                      — left-sidebar Schema panel
    SchemaPanelFragment.kt
    SchemaPanelContentFragment.kt
  template/
    FormTemplateBuilder.kt    — schema → .cgp template builder
  wizard/                     — 4-step capture / review / rules / submit wizard
    WizardHostFragment.kt
    Step1CaptureFragment.kt
    Step2ReviewFieldsFragment.kt
    Step3RulesFragment.kt
    Step4SubmitFragment.kt
    WizardViewModel.kt
    CvLayoutParser.kt         — CV-output XML → FormField list
    FieldsAdapter.kt
    FieldEditorDialog.kt
    RulesAdapter.kt
    FormsPluginConnector.kt
    WizardStep.kt

src/main/res/                  — Material 3 layouts + values + values-night
src/test/kotlin/...            — 98 JVM unit tests (see "Tests" below)
build.gradle.kts               — standalone Android plugin build
settings.gradle.kts            — pulls plugin-api.jar + gradle-plugin.jar
                                 from ../libs/
```

## Building

Standalone Gradle project; lives outside CoGo. Build a `.cgp` with:

```bash
./gradlew :assemblePlugin
# -> build/plugin/forms-plugin.cgp (~10 MB)
```

`local.properties` must point at an installed Android SDK
(`sdk.dir=...`). Java 17+ on PATH (Java 21 verified).

## Tests

98 JVM unit tests across 7 suites, all green:

```bash
./gradlew :test
```

| Suite | Tests | What it covers |
|---|---|---|
| `HeuristicFieldClassifierTest` | 35 | the rule-based field classifier on synthetic OCR fixtures (vaccination intake, voter registration, multi-line ruled, multi-checkbox lines, locale override, malformed input) |
| `HeuristicsTest` | 26 | individually-testable primitives (underscore run, checkbox/radio glyph, date mask, label extraction, all-caps detection, line bounds) |
| `CvLayoutParserTest` | 13 | XML → FormField roundtrip from the CV plugin's output |
| `FormSchemaTest` | 10 | schema mutation + validation (add/remove/reorder/update fields, type changes, required toggle) |
| `FormTemplateBuilderTest` | 7 | schema → `.cgp` template builder (layout XML, manifest, fragment scaffold) |
| `CgtRoundTripTest` | 4 | full round-trip from `FormSchema` to `.cgp` and back via `CgtTemplateBuilder` |
| `PackageNameRegexTest` | 3 | wizard's package-name input validator |

No Android instrumented tests in this repo (per the plugin-examples
convention). The Kaspresso instrumented sidebar smoke test for
ADFA-2435 currently lives in CoGo's `app/src/androidTest/...` and
stays there for now (separate concern; option C from the team
discussion is the eventual move).

## Reference docs

- Benchmark of eleven candidate extraction backends (head-to-head,
  time-savings rate, recommendation):
  - https://github.com/fryanpan/appdevforall/blob/feature/ADFA-2435-benchmark-v3/docs/notes/forms-extraction-benchmark-2026-05-07.md

- Architecture decision page (decision asks for the team meeting):
  - https://github.com/fryanpan/appdevforall/blob/feature/ADFA-2435-benchmark-v3/docs/product/team-questions/forms-architecture-decision.md

- Outcome bar (acceptance criteria for R2):
  - https://github.com/fryanpan/appdevforall/blob/main/docs/product/plans/ADFA-2435-outcome-bar.md

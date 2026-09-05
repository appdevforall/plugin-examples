# Addon Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish Code On The Go addons to Cloudflare R2 directly from the build job, and stop all use of GitHub Actions artifact storage.

**Architecture:** A small Python tool owns addon identity. It derives names from directory names, builds source tarballs, generates a catalog, and uploads to R2. The workflows only call the tool. The tool runs offline against test data, so a pull request can check names and metadata.

**Tech Stack:** Python 3.11 with `uv`, boto3, jsonschema, pytest. Bash and GitHub Actions. Plain HTML, CSS, and JavaScript for the gallery.

**Spec:** `docs/superpowers/specs/2026-09-04-addon-distribution-design.md`

---

## Global Constraints

Every task must obey this section.

1. **Less is more.** Write the minimum code that delivers the feature. Do not add options, abstractions, or configuration that no task asks for. Do not handle rare corner cases. Keep it simple.
2. Use `uv` for all Python work. Never use `pip`, `pip3`, or `uv pip`.
3. Python version is 3.11 or later.
4. Do not change `plugin.id`, `namespace`, or `applicationId` in any addon.
5. The directory name is the only source of addon identity. Do not add an override.
6. Write "Code On The Go" in full in all text that a user reads. Do not write "CoGo", "CotG", or "CodeOnTheGo".
7. Every catalog field is always present. Write `""` or `[]` for an empty value. Never write `null`. Never omit a field.
8. Upload `v1/catalog.json` last, after all other objects.
9. Do not add `actions/upload-artifact` or `actions/download-artifact` to any workflow.
10. The R2 credential has access to the `addons` bucket only.
11. Do not change the toolchain. It is AGP 8.11.0, Kotlin 2.3.0, Gradle 8.14.3-bin, Java 17, compileSdk 36, targetSdk 36.
12. A green build is not verification. Test on a device for any change that alters IDE state.

---

## Method

Every code task follows **red, green, refactor**. Do not skip the third beat.

1. **Red.** Write a test that states the behaviour. Run it. It must fail, and it must fail for the reason you expect. A test that passes the first time you run it tests nothing.
2. **Green.** Write the smallest code that makes the test pass. Do not write code that no test asks for.
3. **Refactor.** Remove duplication. Give an unclear name a better one. Change no behaviour, and add no abstraction, option, or layer — Global Constraint 1 still applies. Run the tests again after any change.

Then commit. Each commit holds a passing test and the code that satisfies it.

It is normal for the refactor step to find nothing. Say so and continue. Refactoring to add structure "for later" is the failure this step invites, not the work it asks for.

**Task 9 is the exception, on purpose.** The gallery is HTML, CSS, and one plain JavaScript file. A test runner for it would mean a JavaScript toolchain and a build step, which the design rejects. Task 9 is checked in a browser instead. The plan says so at that step rather than pretending the coverage exists.


## File Structure

**New files:**

| File | Responsibility |
|---|---|
| `tools/addons/pyproject.toml` | Declares the project, its dependencies, and the `addons` command. |
| `tools/addons/src/addons/model.py` | Derives name, slug, and keys from a directory name. Reads the plugin id and the version from the source files. |
| `tools/addons/src/addons/discover.py` | Finds addon directories. Applies the skip list. |
| `tools/addons/src/addons/check.py` | Checks names and metadata. Exits non-zero on a failure. |
| `tools/addons/src/addons/tarball.py` | Builds and verifies one source tarball. |
| `tools/addons/src/addons/catalog.py` | Builds `catalog.json` and validates it. |
| `tools/addons/src/addons/page.py` | Adds gallery chrome to a description page. |
| `tools/addons/src/addons/publish.py` | Uploads objects to R2 with correct headers. |
| `tools/addons/src/addons/cli.py` | Maps subcommands to the modules above. |
| `tools/addons/skip.txt` | The skip list. One name and one reason for each line. |
| `tools/addons/tests/` | The test suite. One file for each module. |
| `site/index.html`, `site/app.js`, `site/styles.css` | The gallery. |
| `site/catalog.schema.json` | The catalog contract. |
| `.github/workflows/publish-addons.yml` | Builds addons and publishes them to R2. |

**Modified files:**

| File | Change |
|---|---|
| `.github/workflows/check-toolchain.yml` | Add the `addons check` step and the test suite. |
| `.github/workflows/build-plugins.yml` | Remove the `publish` job and the bundle upload. |
| `.github/workflows/update-libs.yml` | Remove the deploy to the web host. |
| `scripts/update-libs.sh` | Copy five jars, not two. Call `addons discover`. |
| `.githooks/pre-push` | Call `addons discover`. |

---

## Task 1: The tool project and `discover`

**Files:**
- Create: `tools/addons/pyproject.toml`
- Create: `tools/addons/src/addons/discover.py`
- Create: `tools/addons/src/addons/cli.py`
- Create: `tools/addons/skip.txt`
- Test: `tools/addons/tests/test_discover.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `discover.find_addons(root: Path) -> list[Path]`. It returns the directory of each addon. It sorts the list by name. `discover.read_skip(root: Path) -> set[str]`.

- [x] **Step 1: Create the project**

```bash
mkdir -p tools/addons/src/addons tools/addons/tests
cat > tools/addons/pyproject.toml <<'EOF'
[project]
name = "addons"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = ["boto3>=1.34", "jsonschema>=4.21"]

[project.scripts]
addons = "addons.cli:main"

[dependency-groups]
dev = ["pytest>=8.0"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
EOF
touch tools/addons/src/addons/__init__.py
cat > tools/addons/skip.txt <<'EOF'
# One directory name for each line. State the reason after the name.
pebble-custom-function-template-installer  ships stale copies of the shared jars
cotg-ndk  held until the contributor confirms the name and the credit
EOF
cd tools/addons && uv sync
```

- [x] **Step 2: Write the failing test**

```python
# tools/addons/tests/test_discover.py
from pathlib import Path
from addons import discover

PREDICATE = "com.itsaky.androidide.plugins.build"


def make_addon(root: Path, path: str, is_addon: bool = True) -> None:
    d = root / path
    d.mkdir(parents=True)
    (d / "build.gradle.kts").write_text(PREDICATE if is_addon else "plain")


def test_finds_addons_in_both_locations(tmp_path):
    make_addon(tmp_path, "Keystore-Generator")
    make_addon(tmp_path, "plugins/Voice-Alerts")
    make_addon(tmp_path, "not-an-addon", is_addon=False)
    names = [p.name for p in discover.find_addons(tmp_path)]
    assert names == ["Keystore-Generator", "Voice-Alerts"]


def test_applies_the_skip_list(tmp_path):
    make_addon(tmp_path, "Keystore-Generator")
    make_addon(tmp_path, "cotg-ndk")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text("# a comment\ncotg-ndk  held\n")
    names = [p.name for p in discover.find_addons(tmp_path)]
    assert names == ["Keystore-Generator"]
```

- [x] **Step 3: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_discover.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'addons.discover'`

- [x] **Step 4: Write the smallest code that passes**

```python
# tools/addons/src/addons/discover.py
from pathlib import Path

PREDICATE = "com.itsaky.androidide.plugins.build"


def read_skip(root: Path) -> set[str]:
    f = root / "tools" / "addons" / "skip.txt"
    if not f.exists():
        return set()
    names = set()
    for line in f.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.add(line.split()[0])
    return names


def find_addons(root: Path) -> list[Path]:
    skip = read_skip(root)
    found = []
    for pattern in ("*/build.gradle.kts", "plugins/*/build.gradle.kts"):
        for f in root.glob(pattern):
            if PREDICATE in f.read_text(errors="ignore") and f.parent.name not in skip:
                found.append(f.parent)
    return sorted(found, key=lambda p: p.name)
```

- [x] **Step 5: Write the command line**

```python
# tools/addons/src/addons/cli.py
import argparse
import sys
from pathlib import Path

from addons import discover


def main() -> int:
    parser = argparse.ArgumentParser(prog="addons")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("discover")
    args = parser.parse_args()

    if args.command == "discover":
        for path in discover.find_addons(args.root):
            print(path.name)
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
```

- [x] **Step 6: Run the test. It must pass**

Run: `uv run --directory tools/addons pytest tests/test_discover.py -v`
Expected: PASS, 2 tests

- [x] **Step 7: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 8: Commit**

```bash
git add tools/addons
git commit -m "Add the addons tool and the discover subcommand"
```

---

## Task 2: Identity rules and the name check

**Files:**
- Create: `tools/addons/src/addons/model.py`
- Create: `tools/addons/src/addons/check.py`
- Modify: `tools/addons/src/addons/cli.py`
- Test: `tools/addons/tests/test_model.py`, `tools/addons/tests/test_check.py`

**Interfaces:**
- Consumes: `discover.find_addons`.
- Produces: `model.display_name(directory: str) -> str`, `model.slug(directory: str) -> str`, `model.directory_is_valid(directory: str) -> bool`, `check.check_names(root: Path) -> list[str]`. The list holds one message for each failure. An empty list means success.

- [x] **Step 1: Write the failing test for the identity rules**

```python
# tools/addons/tests/test_model.py
from addons import model


def test_display_name_replaces_hyphens():
    assert model.display_name("Keystore-Generator") == "Keystore Generator"
    assert model.display_name("Project-to-Template") == "Project to Template"


def test_slug_is_the_lowercased_directory():
    assert model.slug("Keystore-Generator") == "keystore-generator"
    assert model.slug("Random-XKCD") == "random-xkcd"


def test_valid_directory_names():
    assert model.directory_is_valid("Keystore-Generator")
    assert model.directory_is_valid("Project-to-Template")
    assert model.directory_is_valid("Random-XKCD")


def test_invalid_directory_names():
    assert not model.directory_is_valid("keystore-generator")
    assert not model.directory_is_valid("Project-To-Template")
    assert not model.directory_is_valid("Keystore_Generator")
```

- [x] **Step 2: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_model.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [x] **Step 3: Write the smallest code that passes**

```python
# tools/addons/src/addons/model.py
SMALL_WORDS = {"a", "an", "and", "as", "at", "but", "by", "for",
               "in", "of", "on", "or", "the", "to", "up"}


def display_name(directory: str) -> str:
    return directory.replace("-", " ")


def slug(directory: str) -> str:
    return directory.lower()


def directory_is_valid(directory: str) -> bool:
    if "_" in directory or " " in directory:
        return False
    parts = directory.split("-")
    for index, part in enumerate(parts):
        if not part:
            return False
        if index > 0 and part.lower() in SMALL_WORDS:
            if part != part.lower():
                return False
        elif not part[0].isupper():
            return False
    return True
```

- [x] **Step 4: Run the test. It must pass**

Run: `uv run --directory tools/addons pytest tests/test_model.py -v`
Expected: PASS, 4 tests

- [x] **Step 5: Write the failing test for the name check**

```python
# tools/addons/tests/test_check.py
from pathlib import Path
from addons import check

PREDICATE = "com.itsaky.androidide.plugins.build"

MANIFEST = """<manifest><application>
<meta-data android:name="plugin.id" android:value="com.appdevforall.keygen.plugin" />
<meta-data android:name="plugin.name" android:value="{name}" />
</application></manifest>"""


def make_addon(root: Path, directory: str, plugin_name: str,
               gradle_name: str, page: str) -> Path:
    d = root / directory
    (d / "src" / "main").mkdir(parents=True)
    (d / "build.gradle.kts").write_text(
        PREDICATE + '\npluginBuilder { pluginName = "%s" }\n' % gradle_name)
    (d / "src" / "main" / "AndroidManifest.xml").write_text(
        MANIFEST.format(name=plugin_name))
    (d / page).write_text("<html><title>%s</title><body>x</body></html>" % plugin_name)
    return d


def test_a_compliant_addon_passes(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
               "keystore-generator", "keystore-generator.html")
    assert check.check_names(tmp_path) == []


def test_a_wrong_page_filename_fails(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
               "keystore-generator", "keygen.html")
    problems = check.check_names(tmp_path)
    assert len(problems) == 1
    assert "keystore-generator.html" in problems[0]


def test_a_wrong_plugin_name_fails(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Key Gen",
               "keystore-generator", "keystore-generator.html")
    problems = check.check_names(tmp_path)
    assert any("plugin.name" in p for p in problems)


def test_a_bad_directory_name_fails(tmp_path):
    make_addon(tmp_path, "keystore-generator", "keystore generator",
               "keystore-generator", "keystore-generator.html")
    problems = check.check_names(tmp_path)
    assert any("directory" in p for p in problems)
```

- [x] **Step 6: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_check.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'addons.check'`

- [x] **Step 7: Write the smallest code that passes**

```python
# tools/addons/src/addons/check.py
import re
from pathlib import Path

from addons import discover, model


def _meta(manifest: str, key: str) -> str | None:
    pattern = r'android:name="%s"\s+android:value="([^"]*)"' % re.escape(key)
    found = re.search(pattern, manifest)
    return found.group(1) if found else None


def check_names(root: Path) -> list[str]:
    problems = []
    for path in discover.find_addons(root):
        directory = path.name
        name = model.display_name(directory)
        addon_slug = model.slug(directory)

        if not model.directory_is_valid(directory):
            problems.append(f"{directory}: the directory name breaks the naming rule")

        build = (path / "build.gradle.kts").read_text()
        found = re.search(r'pluginName\s*=\s*"([^"]*)"', build)
        if found and found.group(1) != addon_slug:
            problems.append(
                f"{directory}: pluginName is '{found.group(1)}', expected '{addon_slug}'")

        manifest_file = path / "src" / "main" / "AndroidManifest.xml"
        if manifest_file.exists():
            manifest = manifest_file.read_text()
            plugin_name = _meta(manifest, "plugin.name")
            if plugin_name is not None and plugin_name != name:
                problems.append(
                    f"{directory}: plugin.name is '{plugin_name}', expected '{name}'")

        page = path / f"{addon_slug}.html"
        if not page.exists():
            problems.append(f"{directory}: the page must be named {addon_slug}.html")
        elif f"<title>{name}</title>" not in page.read_text():
            problems.append(f"{directory}: the page title must be '{name}'")
    return problems
```

- [x] **Step 8: Add the subcommand**

In `tools/addons/src/addons/cli.py`, add `from addons import check` to the imports, add `sub.add_parser("check")` after the `discover` parser, and add this branch before `return 1`:

```python
    if args.command == "check":
        problems = check.check_names(args.root)
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0
```

- [x] **Step 9: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 10 tests

- [x] **Step 10: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 11: Commit**

```bash
git add tools/addons
git commit -m "Add identity rules and the name check"
```

---

## Task 3: Metadata and its check

**Files:**
- Create: `tools/addons/src/addons/addon.schema.json`
- Modify: `tools/addons/src/addons/check.py`
- Test: `tools/addons/tests/test_metadata.py`

**Interfaces:**
- Consumes: `discover.find_addons`, `check.check_names`.
- Produces: `check.check_metadata(root: Path) -> list[str]`, `check.run(root: Path) -> list[str]`. `run` returns the messages from both checks.

- [x] **Step 1: Write the schema**

```bash
cat > tools/addons/src/addons/addon.schema.json <<'EOF'
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["summary", "description", "tags", "origin", "license", "author", "minAppVersion"],
  "properties": {
    "summary": { "type": "string", "minLength": 1, "maxLength": 120 },
    "description": { "type": "string", "minLength": 1 },
    "tags": { "type": "array", "minItems": 1,
              "items": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]*$" } },
    "origin": { "enum": ["appdevforall", "community"] },
    "license": { "type": "string", "minLength": 1 },
    "minAppVersion": { "type": "string", "pattern": "^[0-9]{2}\\.[0-9]{2}$" },
    "author": {
      "type": "object",
      "additionalProperties": false,
      "required": ["name", "url"],
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "url": { "type": "string" },
        "email": { "type": "string" }
      }
    }
  }
}
EOF
```

- [x] **Step 2: Write the failing test**

```python
# tools/addons/tests/test_metadata.py
import json
from pathlib import Path
from addons import check

PREDICATE = "com.itsaky.androidide.plugins.build"

GOOD = {
    "summary": "Creates and manages app signing keystores on the device.",
    "description": "A longer paragraph.",
    "tags": ["signing", "release"],
    "origin": "appdevforall",
    "license": "AGPL-3.0-or-later",
    "author": {"name": "App Dev For All", "url": "https://www.appdevforall.org"},
    "minAppVersion": "25.47",
}


def make_addon(root: Path, metadata: dict | None) -> None:
    d = root / "Keystore-Generator"
    d.mkdir(parents=True)
    (d / "build.gradle.kts").write_text(PREDICATE)
    if metadata is not None:
        (d / "addon.json").write_text(json.dumps(metadata))


def test_good_metadata_passes(tmp_path):
    make_addon(tmp_path, GOOD)
    assert check.check_metadata(tmp_path) == []


def test_missing_file_fails(tmp_path):
    make_addon(tmp_path, None)
    assert any("addon.json" in p for p in check.check_metadata(tmp_path))


def test_unknown_key_fails(tmp_path):
    make_addon(tmp_path, GOOD | {"colour": "blue"})
    assert check.check_metadata(tmp_path) != []


def test_community_needs_an_author(tmp_path):
    bad = dict(GOOD, origin="community")
    del bad["author"]
    make_addon(tmp_path, bad)
    assert check.check_metadata(tmp_path) != []


def test_bad_min_app_version_fails(tmp_path):
    make_addon(tmp_path, GOOD | {"minAppVersion": "1.0.0"})
    assert check.check_metadata(tmp_path) != []
```

- [x] **Step 3: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_metadata.py -v`
Expected: FAIL with `AttributeError: module 'addons.check' has no attribute 'check_metadata'`

- [x] **Step 4: Write the smallest code that passes**

Add this to the top of `tools/addons/src/addons/check.py`:

```python
import json

import jsonschema

SCHEMA = json.loads(
    (Path(__file__).parent / "addon.schema.json").read_text())
```

Add these two functions at the end of the same file:

```python
def check_metadata(root: Path) -> list[str]:
    problems = []
    for path in discover.find_addons(root):
        f = path / "addon.json"
        if not f.exists():
            problems.append(f"{path.name}: addon.json is missing")
            continue
        try:
            data = json.loads(f.read_text())
        except json.JSONDecodeError as error:
            problems.append(f"{path.name}: addon.json is not valid JSON: {error}")
            continue
        try:
            jsonschema.validate(data, SCHEMA)
        except jsonschema.ValidationError as error:
            problems.append(f"{path.name}: addon.json is invalid: {error.message}")
    return problems


def run(root: Path) -> list[str]:
    return check_names(root) + check_metadata(root)
```

- [x] **Step 5: Point the subcommand at `run`**

In `tools/addons/src/addons/cli.py`, change `check.check_names(args.root)` to `check.run(args.root)`.

- [x] **Step 6: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 15 tests

- [x] **Step 7: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 8: Commit**

```bash
git add tools/addons
git commit -m "Add the addon.json schema and its check"
```

---

## Task 4: Read the plugin id and the version from the source

**Files:**
- Modify: `tools/addons/src/addons/model.py`
- Test: `tools/addons/tests/test_model.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `model.plugin_id(addon: Path) -> str`, `model.version(addon: Path) -> str`.

Both values sit in plain text. `plugin.id` is a literal in `src/main/AndroidManifest.xml`. The version comes from one of three places, in this order:

1. `pluginVersion` inside the `pluginBuilder { }` block of `build.gradle.kts`.
2. `plugin.version` in the manifest, when it is a literal and not a `${...}` placeholder.
3. `1.0.0`, which is the Gradle plugin's own default.

Step 3 is what 30 of the 31 addons get today, because no addon sets `pluginVersion`. The Gradle plugin injects the same default, so the tool reports what really ships.

- [x] **Step 1: Write the failing test**

Add this to `tools/addons/tests/test_model.py`:

```python
from pathlib import Path

MANIFEST = """<manifest><application>
    <meta-data
        android:name="plugin.id"
        android:value="com.appdevforall.keygen.plugin" />
    <meta-data
        android:name="plugin.version"
        android:value="{version}" />
</application></manifest>"""


def make(tmp_path: Path, version: str, build: str = "") -> Path:
    addon = tmp_path / "Keystore-Generator"
    (addon / "src" / "main").mkdir(parents=True)
    (addon / "src" / "main" / "AndroidManifest.xml").write_text(
        MANIFEST.format(version=version))
    (addon / "build.gradle.kts").write_text(build)
    return addon


def test_reads_the_plugin_id(tmp_path):
    addon = make(tmp_path, "${pluginVersion}")
    assert model.plugin_id(addon) == "com.appdevforall.keygen.plugin"


def test_a_placeholder_falls_back_to_the_default(tmp_path):
    addon = make(tmp_path, "${pluginVersion}")
    assert model.version(addon) == "1.0.0"


def test_a_literal_in_the_manifest_wins_over_the_default(tmp_path):
    addon = make(tmp_path, "1.0.1")
    assert model.version(addon) == "1.0.1"


def test_the_build_file_wins_over_everything(tmp_path):
    addon = make(tmp_path, "1.0.1",
                 build='pluginBuilder {\n  pluginVersion = "2.4.0"\n}\n')
    assert model.version(addon) == "2.4.0"


def test_a_missing_manifest_gives_an_empty_id(tmp_path):
    addon = tmp_path / "Empty"
    addon.mkdir()
    assert model.plugin_id(addon) == ""
```

- [x] **Step 2: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_model.py -v`
Expected: FAIL with `AttributeError: module 'addons.model' has no attribute 'plugin_id'`

- [x] **Step 3: Write the smallest code that passes**

Add these lines to the top of `tools/addons/src/addons/model.py`:

```python
import re
from pathlib import Path

DEFAULT_VERSION = "1.0.0"
```

Add these functions to the end of the same file:

```python
def _meta(addon: Path, key: str) -> str:
    f = addon / "src" / "main" / "AndroidManifest.xml"
    if not f.exists():
        return ""
    text = " ".join(f.read_text().split())
    found = re.search(
        r'android:name="%s" android:value="([^"]*)"' % re.escape(key), text)
    return found.group(1) if found else ""


def plugin_id(addon: Path) -> str:
    return _meta(addon, "plugin.id")


def version(addon: Path) -> str:
    build = addon / "build.gradle.kts"
    if build.exists():
        found = re.search(r'pluginVersion\s*=\s*"([^"]+)"', build.read_text())
        if found:
            return found.group(1)
    declared = _meta(addon, "plugin.version")
    if declared and not declared.startswith("${"):
        return declared
    return DEFAULT_VERSION
```

- [x] **Step 4: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 20 tests

- [x] **Step 5: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 6: Commit**

```bash
git add tools/addons
git commit -m "Read the plugin id and the version from the source files"
```

---

## Task 5: Source tarballs

**Files:**
- Create: `tools/addons/src/addons/tarball.py`
- Modify: `tools/addons/src/addons/cli.py`
- Test: `tools/addons/tests/test_tarball.py`

**Interfaces:**
- Consumes: `model.slug`.
- Produces: `tarball.jars_for(addon: Path) -> list[str]`, `tarball.build(root: Path, addon: Path, out: Path) -> Path`. `build` returns the path of the archive.

The archive holds a copy of this repository with one addon in it. The addon keeps its own directory. `libs/` and `gradlew` sit beside it. Therefore every `../libs/` path still works and the tool changes no file.

- [x] **Step 1: Write the failing test**

```python
# tools/addons/tests/test_tarball.py
import subprocess
import tarfile
from pathlib import Path

import pytest

from addons import tarball

PREDICATE = "com.itsaky.androidide.plugins.build"


def make_repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / "libs").mkdir()
    for jar in ("plugin-api.jar", "gradle-plugin.jar", "common.jar"):
        (tmp_path / "libs" / jar).write_bytes(b"jar")
    (tmp_path / "gradlew").write_text("#!/bin/sh\n")
    (tmp_path / "gradle" / "wrapper").mkdir(parents=True)
    (tmp_path / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text("x")

    addon = tmp_path / "Keystore-Generator"
    (addon / "src").mkdir(parents=True)
    (addon / "build.gradle.kts").write_text(
        PREDICATE + '\ncompileOnly(files("../libs/plugin-api.jar"))\n')
    (addon / "settings.gradle.kts").write_text(
        'classpath(files("../libs/gradle-plugin.jar"))\n')
    (addon / "src" / "Main.kt").write_text("fun main() {}")
    (addon / "local.properties").write_text("sdk.dir=/Users/someone/Android")
    (tmp_path / ".gitignore").write_text("local.properties\n")
    subprocess.run(["git", "-C", str(tmp_path), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "-c", "user.email=t@t",
                    "-c", "user.name=t", "commit", "-qm", "x"], check=True)
    return addon


def test_finds_only_the_jars_the_addon_uses(tmp_path):
    addon = make_repo(tmp_path)
    assert tarball.jars_for(addon) == ["gradle-plugin.jar", "plugin-api.jar"]


def test_archive_has_the_two_level_shape(tmp_path):
    addon = make_repo(tmp_path)
    out = tmp_path / "dist"
    out.mkdir()
    archive = tarball.build(tmp_path, addon, out)
    assert archive.name == "keystore-generator-src.tar.gz"
    with tarfile.open(archive) as tar:
        names = set(tar.getnames())
    top = "keystore-generator-src"
    assert f"{top}/libs/plugin-api.jar" in names
    assert f"{top}/libs/gradle-plugin.jar" in names
    assert f"{top}/libs/common.jar" not in names
    assert f"{top}/gradlew" in names
    assert f"{top}/gradle/wrapper/gradle-wrapper.properties" in names
    assert f"{top}/Keystore-Generator/build.gradle.kts" in names
    assert f"{top}/README.md" in names


def test_local_properties_never_reaches_the_archive(tmp_path):
    addon = make_repo(tmp_path)
    out = tmp_path / "dist"
    out.mkdir()
    archive = tarball.build(tmp_path, addon, out)
    with tarfile.open(archive) as tar:
        assert not any("local.properties" in n for n in tar.getnames())


def test_a_missing_jar_stops_the_build(tmp_path):
    addon = make_repo(tmp_path)
    (tmp_path / "libs" / "plugin-api.jar").unlink()
    out = tmp_path / "dist"
    out.mkdir()
    with pytest.raises(RuntimeError, match="plugin-api.jar"):
        tarball.build(tmp_path, addon, out)
```

- [x] **Step 2: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_tarball.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'addons.tarball'`

- [x] **Step 3: Write the smallest code that passes**

```python
# tools/addons/src/addons/tarball.py
import re
import shutil
import subprocess
import tarfile
from pathlib import Path

from addons import model

README = """# {name}

Source for the {name} addon for Code On The Go.

## Build

    cd {directory}
    ../gradlew assemblePlugin

The plugin file appears in `{directory}/build/plugin/`.

You must create `{directory}/local.properties` with one line:

    sdk.dir=/path/to/your/Android/sdk

License: {license}
"""


def jars_for(addon: Path) -> list[str]:
    text = ""
    for name in ("build.gradle.kts", "settings.gradle.kts"):
        f = addon / name
        if f.exists():
            text += f.read_text()
    return sorted(set(re.findall(r"\.\./libs/([A-Za-z0-9._-]+\.jar)", text)))


def tracked_files(root: Path, addon: Path) -> list[str]:
    relative = addon.relative_to(root).as_posix()
    result = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--", relative],
        capture_output=True, text=True, check=True)
    return [line for line in result.stdout.splitlines() if line]


def _stage(root: Path, addon: Path, out: Path, licence: str) -> Path:
    top = out / f"{model.slug(addon.name)}-src"
    if top.exists():
        shutil.rmtree(top)
    top.mkdir(parents=True)

    files = tracked_files(root, addon)
    if not files:
        raise RuntimeError(f"{addon.name}: git tracks no file in this directory")
    base = addon.relative_to(root).as_posix()
    for relative in files:
        target = top / addon.name / relative[len(base) + 1:]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / relative, target)

    (top / "libs").mkdir()
    for jar in jars_for(addon):
        source = root / "libs" / jar
        if not source.exists():
            raise RuntimeError(f"{addon.name}: libs/{jar} is missing")
        shutil.copy2(source, top / "libs" / jar)

    for name in ("gradlew", "gradlew.bat"):
        if (root / name).exists():
            shutil.copy2(root / name, top / name)
    shutil.copytree(root / "gradle" / "wrapper", top / "gradle" / "wrapper")

    (top / "README.md").write_text(README.format(
        name=model.display_name(addon.name), directory=addon.name, license=licence))
    return top


def verify(top: Path, directory: str, jars: list[str]) -> None:
    problems = []
    for jar in jars:
        if not (top / "libs" / jar).exists():
            problems.append(f"libs/{jar} is missing")
    if not (top / "gradlew").exists():
        problems.append("gradlew is missing")
    if not (top / "gradle" / "wrapper" / "gradle-wrapper.properties").exists():
        problems.append("the wrapper properties file is missing")
    for name in ("build.gradle.kts", "settings.gradle.kts"):
        if not (top / directory / name).exists():
            problems.append(f"{directory}/{name} is missing")
    root = top.resolve()
    for path in top.rglob("*"):
        if path.name == "local.properties":
            problems.append("local.properties is present")
        if not str(path.resolve()).startswith(str(root)):
            problems.append(f"{path} is outside the archive root")
    if problems:
        raise RuntimeError(f"{directory}: " + "; ".join(problems))


def build(root: Path, addon: Path, out: Path, licence: str = "AGPL-3.0-or-later") -> Path:
    jars = jars_for(addon)
    if not jars:
        raise RuntimeError(f"{addon.name}: it references no shared jar")
    top = _stage(root, addon, out, licence)
    verify(top, addon.name, jars)
    archive = out / f"{top.name}.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(top, arcname=top.name)
    shutil.rmtree(top)
    return archive
```

- [x] **Step 4: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 24 tests

- [x] **Step 5: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 6: Commit**

```bash
git add tools/addons
git commit -m "Build and verify source tarballs"
```

---

## Task 6: The catalog

**Files:**
- Create: `site/catalog.schema.json`
- Create: `tools/addons/src/addons/catalog.py`
- Modify: `tools/addons/src/addons/cli.py`
- Test: `tools/addons/tests/test_catalog.py`

**Interfaces:**
- Consumes: `discover.find_addons`, `model.slug`, `model.display_name`, `model.plugin_id`, `model.version`.
- Produces: `catalog.entry(root, addon, cgp, archive) -> dict`, `catalog.build(root: Path, dist: Path) -> dict`.

`build` expects `dist/<slug>.cgp` and `dist/<slug>-src.tar.gz` for every addon.

- [x] **Step 1: Write the schema**

```bash
cat > site/catalog.schema.json <<'EOF'
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://addons.appdevforall.org/v1/catalog.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["schemaVersion", "generated", "addons"],
  "properties": {
    "schemaVersion": { "const": 1 },
    "generated": { "type": "string" },
    "addons": { "type": "array", "items": { "$ref": "#/$defs/addon" } }
  },
  "$defs": {
    "addon": {
      "type": "object",
      "additionalProperties": false,
      "required": ["type", "slug", "pluginId", "name", "version", "summary",
                   "description", "origin", "license", "tags", "author",
                   "minAppVersion", "iconUrl", "pageUrl", "sourceUrl",
                   "download", "sourceTarball"],
      "properties": {
        "type": { "enum": ["plugin", "template", "snippet", "code-action"] },
        "slug": { "type": "string", "pattern": "^[a-z0-9]+(-[a-z0-9]+)*$" },
        "pluginId": { "type": "string", "minLength": 1 },
        "name": { "type": "string", "minLength": 1 },
        "version": { "type": "string", "pattern": "^[0-9]+(\\.[0-9]+)*$" },
        "summary": { "type": "string", "maxLength": 120 },
        "description": { "type": "string" },
        "origin": { "enum": ["appdevforall", "community"] },
        "license": { "type": "string" },
        "tags": { "type": "array", "items": { "type": "string" } },
        "author": {
          "type": "object", "additionalProperties": false,
          "required": ["name", "url"],
          "properties": { "name": { "type": "string" }, "url": { "type": "string" } }
        },
        "minAppVersion": { "type": "string" },
        "iconUrl": { "type": "string" },
        "pageUrl": { "type": "string" },
        "sourceUrl": { "type": "string" },
        "download": { "$ref": "#/$defs/file" },
        "sourceTarball": { "$ref": "#/$defs/file" }
      }
    },
    "file": {
      "type": "object", "additionalProperties": false,
      "required": ["url", "sha256", "size"],
      "properties": {
        "url": { "type": "string" },
        "sha256": { "type": "string", "pattern": "^[a-f0-9]{64}$" },
        "size": { "type": "integer", "minimum": 0 }
      }
    }
  }
}
EOF
```

- [x] **Step 2: Write the failing test**

```python
# tools/addons/tests/test_catalog.py
import json
from pathlib import Path

import pytest

from addons import catalog

PREDICATE = "com.itsaky.androidide.plugins.build"

METADATA = {
    "summary": "Creates and manages app signing keystores on the device.",
    "description": "A longer paragraph.",
    "tags": ["signing", "release"],
    "origin": "appdevforall",
    "license": "AGPL-3.0-or-later",
    "author": {"name": "App Dev For All", "url": "https://www.appdevforall.org"},
    "minAppVersion": "25.47",
}


MANIFEST = """<manifest><application>
    <meta-data
        android:name="plugin.id"
        android:value="com.appdevforall.keygen.plugin" />
    <meta-data
        android:name="plugin.version"
        android:value="${pluginVersion}" />
</application></manifest>"""


def make(tmp_path: Path, build_extra: str = "") -> Path:
    addon = tmp_path / "plugins" / "Keystore-Generator"
    (addon / "src" / "main").mkdir(parents=True)
    (addon / "build.gradle.kts").write_text(PREDICATE + "\n" + build_extra)
    (addon / "src" / "main" / "AndroidManifest.xml").write_text(MANIFEST)
    (addon / "addon.json").write_text(json.dumps(METADATA))
    dist = tmp_path / "dist"
    dist.mkdir()
    (dist / "keystore-generator.cgp").write_bytes(b"cgp")
    (dist / "keystore-generator-src.tar.gz").write_bytes(b"tar")
    return dist


def test_builds_a_valid_entry(tmp_path):
    dist = make(tmp_path)
    result = catalog.build(tmp_path, dist)
    assert result["schemaVersion"] == 1
    entry = result["addons"][0]
    assert entry["type"] == "plugin"
    assert entry["slug"] == "keystore-generator"
    assert entry["name"] == "Keystore Generator"
    assert entry["pluginId"] == "com.appdevforall.keygen.plugin"
    assert entry["version"] == "1.0.0"
    assert entry["download"]["url"].startswith("https://")
    assert entry["download"]["size"] == 3
    assert len(entry["download"]["sha256"]) == 64


def test_no_field_is_null(tmp_path):
    dist = make(tmp_path)
    entry = catalog.build(tmp_path, dist)["addons"][0]
    assert None not in entry.values()


def test_a_bad_version_stops_the_build(tmp_path):
    dist = make(tmp_path, build_extra='pluginBuilder { pluginVersion = "draft" }')
    with pytest.raises(RuntimeError, match="version"):
        catalog.build(tmp_path, dist)


def test_a_missing_artifact_stops_the_build(tmp_path):
    dist = make(tmp_path)
    (dist / "keystore-generator.cgp").unlink()
    with pytest.raises(RuntimeError, match="keystore-generator.cgp"):
        catalog.build(tmp_path, dist)
```

- [x] **Step 3: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_catalog.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'addons.catalog'`

- [x] **Step 4: Write the smallest code that passes**

```python
# tools/addons/src/addons/catalog.py
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

import jsonschema

from addons import discover, model

BASE = "https://addons.appdevforall.org"
SOURCE = "https://github.com/appdevforall/plugin-examples/tree/main"
TYPES = {"plugins": "plugin", "templates": "template",
         "snippets": "snippet", "code-actions": "code-action"}
VERSION = re.compile(r"^[0-9]+(\.[0-9]+)*$")


def _file(path: Path, url: str) -> dict:
    data = path.read_bytes()
    return {"url": url, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def entry(root: Path, addon: Path, cgp: Path, archive: Path) -> dict:
    directory = addon.name
    slug = model.slug(directory)
    meta = json.loads((addon / "addon.json").read_text())
    version = model.version(addon)
    if not VERSION.match(version):
        raise RuntimeError(f"{directory}: the version '{version}' is not a number")
    relative = addon.relative_to(root).as_posix()
    return {
        "type": TYPES.get(addon.parent.name, "plugin"),
        "slug": slug,
        "pluginId": model.plugin_id(addon),
        "name": model.display_name(directory),
        "version": version,
        "summary": meta["summary"],
        "description": meta["description"],
        "origin": meta["origin"],
        "license": meta["license"],
        "tags": meta["tags"],
        "author": {"name": meta["author"]["name"], "url": meta["author"]["url"]},
        "minAppVersion": meta["minAppVersion"],
        "iconUrl": f"{BASE}/p/{slug}.png",
        "pageUrl": f"{BASE}/p/{slug}.html",
        "sourceUrl": f"{SOURCE}/{relative}",
        "download": _file(cgp, f"{BASE}/dl/{slug}.cgp"),
        "sourceTarball": _file(archive, f"{BASE}/src/{slug}-src.tar.gz"),
    }


def build(root: Path, dist: Path) -> dict:
    entries = []
    for addon in discover.find_addons(root):
        slug = model.slug(addon.name)
        cgp = dist / f"{slug}.cgp"
        archive = dist / f"{slug}-src.tar.gz"
        for f in (cgp, archive):
            if not f.exists():
                raise RuntimeError(f"{addon.name}: {f.name} is missing from {dist}")
        entries.append(entry(root, addon, cgp, archive))

    document = {
        "schemaVersion": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "addons": entries,
    }
    schema = json.loads((root / "site" / "catalog.schema.json").read_text())
    jsonschema.validate(document, schema)
    return document
```

- [x] **Step 5: Copy the schema into the test tree**

The test builds a repository in a temporary directory. Add this line to `make` in the test file, before it returns:

```python
    (tmp_path / "site").mkdir()
    (tmp_path / "site" / "catalog.schema.json").write_text(
        Path(__file__).parents[3].joinpath("site/catalog.schema.json").read_text())
```

- [x] **Step 6: Add the subcommand**

In `cli.py`, add `from addons import catalog` to the imports. Add this parser after the others:

```python
    catalog_parser = sub.add_parser("catalog")
    catalog_parser.add_argument("--dist", type=Path, required=True)
    catalog_parser.add_argument("--out", type=Path, required=True)
```

Add this branch:

```python
    if args.command == "catalog":
        document = catalog.build(args.root, args.dist)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(document, indent=2) + "\n")
        print(f"wrote {args.out} with {len(document['addons'])} addons")
        return 0
```

Add `import json` to the top of `cli.py`.

- [x] **Step 7: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 28 tests

- [x] **Step 8: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 9: Commit**

```bash
git add tools/addons site/catalog.schema.json
git commit -m "Generate and validate the catalog"
```

---

## Task 7: Add gallery chrome to a description page

**Files:**
- Create: `site/page.template.html`
- Create: `tools/addons/src/addons/page.py`
- Test: `tools/addons/tests/test_page.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `page.wrap(html: str, name: str, template: str) -> str`.

The 31 description pages stay plain HTML in the repository. The tool adds the header, the footer, and the stylesheet link when it publishes them.

- [x] **Step 1: Write the template**

```bash
cat > site/page.template.html <<'EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{title}} — Code On The Go</title>
<link rel="stylesheet" href="/assets/styles.css">
</head>
<body>
<header class="chrome"><a href="/">Code On The Go addons</a></header>
<main class="page">{{body}}</main>
<footer class="chrome"><a href="/">Back to all addons</a></footer>
</body>
</html>
EOF
```

- [x] **Step 2: Write the failing test**

```python
# tools/addons/tests/test_page.py
from addons import page

TEMPLATE = "<html><title>{{title}}</title><body>{{body}}</body></html>"


def test_keeps_the_body_and_drops_the_old_shell():
    source = ("<html><head><style>p{color:red}</style></head>"
              "<body><h1>Keystore Generator</h1><p>Text.</p></body></html>")
    result = page.wrap(source, "Keystore Generator", TEMPLATE)
    assert "<h1>Keystore Generator</h1><p>Text.</p>" in result
    assert "color:red" not in result
    assert "<title>Keystore Generator</title>" in result


def test_accepts_a_fragment_with_no_body_tag():
    result = page.wrap("<p>Text.</p>", "Name", TEMPLATE)
    assert "<p>Text.</p>" in result
```

- [x] **Step 3: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_page.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'addons.page'`

- [x] **Step 4: Write the smallest code that passes**

```python
# tools/addons/src/addons/page.py
import re


def wrap(html: str, name: str, template: str) -> str:
    found = re.search(r"<body[^>]*>(.*)</body>", html, re.S | re.I)
    body = found.group(1) if found else html
    return template.replace("{{title}}", name).replace("{{body}}", body.strip())
```

- [x] **Step 5: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 30 tests

- [x] **Step 6: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 7: Commit**

```bash
git add tools/addons site/page.template.html
git commit -m "Add gallery chrome to description pages at publish time"
```

---

## Task 8: Upload to Cloudflare R2

**Files:**
- Create: `tools/addons/src/addons/publish.py`
- Modify: `tools/addons/src/addons/cli.py`
- Test: `tools/addons/tests/test_publish.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `publish.headers_for(key: str) -> dict`, `publish.put(client, bucket, key, path)`, `publish.publish(client, bucket, objects: list[tuple[str, Path]], catalog: tuple[str, Path])`, `publish.client_from_env()`.

The `publish` function takes the catalog as its own parameter. Therefore the code cannot upload the catalog before the other objects.

- [x] **Step 1: Write the failing test**

```python
# tools/addons/tests/test_publish.py
from pathlib import Path

import pytest

from addons import publish


class FakeClient:
    def __init__(self):
        self.store = {}
        self.order = []

    def put_object(self, Bucket, Key, Body, **headers):
        self.store[Key] = (Body, headers)
        self.order.append(Key)


def test_headers_for_a_download():
    head = publish.headers_for("dl/keystore-generator.cgp")
    assert head["ContentType"] == "application/octet-stream"
    assert head["CacheControl"] == "public, max-age=60"
    assert head["ContentDisposition"] == 'attachment; filename="keystore-generator.cgp"'


def test_headers_for_a_page():
    head = publish.headers_for("p/keystore-generator.html")
    assert head["ContentType"] == "text/html"
    assert "ContentDisposition" not in head


def test_headers_for_an_asset():
    head = publish.headers_for("assets/styles.css")
    assert head["ContentType"] == "text/css"
    assert head["CacheControl"] == "public, max-age=60"


def test_the_catalog_goes_last(tmp_path):
    one = tmp_path / "a.cgp"
    one.write_bytes(b"a")
    document = tmp_path / "catalog.json"
    document.write_bytes(b"{}")
    client = FakeClient()
    publish.publish(client, "addons", [("dl/a.cgp", one)],
                    ("v1/catalog.json", document))
    assert client.order == ["dl/a.cgp", "v1/catalog.json"]

```

- [x] **Step 2: Run the test. It must fail**

Run: `uv run --directory tools/addons pytest tests/test_publish.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'addons.publish'`

- [x] **Step 3: Write the smallest code that passes**

```python
# tools/addons/src/addons/publish.py
import os
from pathlib import Path

import boto3

SHORT = "public, max-age=60"

CONTENT_TYPES = {
    ".html": "text/html",
    ".json": "application/json",
    ".css": "text/css",
    ".js": "text/javascript",
    ".png": "image/png",
    ".cgp": "application/octet-stream",
    ".gz": "application/gzip",
}
ATTACHMENTS = {".cgp", ".gz"}


def headers_for(key: str) -> dict:
    suffix = Path(key).suffix
    headers = {
        "ContentType": CONTENT_TYPES.get(suffix, "application/octet-stream"),
        "CacheControl": SHORT,
    }
    if suffix in ATTACHMENTS:
        headers["ContentDisposition"] = f'attachment; filename="{Path(key).name}"'
    return headers


def put(client, bucket: str, key: str, path: Path) -> None:
    client.put_object(Bucket=bucket, Key=key, Body=path.read_bytes(),
                      **headers_for(key))


def publish(client, bucket: str, objects: list[tuple[str, Path]],
            catalog: tuple[str, Path]) -> None:
    for key, path in objects:
        put(client, bucket, key, path)
    put(client, bucket, catalog[0], catalog[1])


def client_from_env():
    account = os.environ["R2_ACCOUNT_ID"]
    return boto3.client(
        "s3",
        endpoint_url=f"https://{account}.r2.cloudflarestorage.com",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
    )
```

- [x] **Step 4: Run all tests. They must pass**

Run: `uv run --directory tools/addons pytest -v`
Expected: PASS, 34 tests

- [x] **Step 5: Add the subcommand**

The subcommand collects every object, adds the chrome to each page, and uploads. Add `from addons import model, page, publish, tarball` to `cli.py`. Add this parser:

```python
    publish_parser = sub.add_parser("publish")
    publish_parser.add_argument("--dist", type=Path, required=True)
    publish_parser.add_argument("--prefix", default="")
```

Add this branch:

```python
    if args.command == "publish":
        dist, prefix = args.dist, args.prefix
        site = args.root / "site"
        template = (site / "page.template.html").read_text()
        objects = [
            (f"{prefix}assets/styles.css", site / "styles.css"),
            (f"{prefix}assets/app.js", site / "app.js"),
            (f"{prefix}index.html", site / "index.html"),
        ]
        for addon in discover.find_addons(args.root):
            slug = model.slug(addon.name)
            wrapped = page.wrap((addon / f"{slug}.html").read_text(),
                                model.display_name(addon.name), template)
            page_file = dist / f"{slug}.page.html"
            page_file.write_text(wrapped)
            objects += [
                (f"{prefix}p/{slug}.html", page_file),
                (f"{prefix}p/{slug}.png",
                 addon / "src" / "main" / "assets" / "icon_day.png"),
                (f"{prefix}dl/{slug}.cgp", dist / f"{slug}.cgp"),
                (f"{prefix}src/{slug}-src.tar.gz", dist / f"{slug}-src.tar.gz"),
            ]
        objects.append((f"{prefix}v1/catalog.schema.json",
                        site / "catalog.schema.json"))
        publish.publish(publish.client_from_env(), "addons", objects,
                        (f"{prefix}v1/catalog.json", dist / "catalog.json"))
        print(f"published {len(objects) + 1} objects")
        return 0
```

- [x] **Step 6: Refactor**

Read the code you just wrote next to the code around it. Remove duplication. Rename anything unclear. Change no behaviour. Do not add an abstraction, an option, or a layer.

Run: `uv run --directory tools/addons pytest -q`
Expected: PASS, the same count as the step above.

If nothing needs changing, write "nothing to refactor" in the task notes and continue. That is a normal outcome.

- [x] **Step 7: Commit**

```bash
git add tools/addons
git commit -m "Upload addons, pages, and the catalog to Cloudflare R2"
```

---

## Task 9: The gallery

**Files:**
- Create: `site/index.html`
- Create: `site/app.js`
- Create: `site/styles.css`

**Interfaces:**
- Consumes: `v1/catalog.json`.
- Produces: nothing that other tasks use.

The gallery is one plain JavaScript file. The browser runs it without a build step. All text goes into the page with `textContent`, so a description can never add markup.

**This task has no automated test, and that is a deliberate trade.** A test runner for the browser code would mean a JavaScript toolchain and a build step, which the design rejects for a page that lists 31 cards. Step 4 checks it in a browser instead. Do not add a test framework to satisfy the pattern.

- [x] **Step 1: Write the page**

```bash
cat > site/index.html <<'EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Addons for Code On The Go</title>
<link rel="stylesheet" href="/assets/styles.css">
</head>
<body>
<header class="chrome"><h1>Addons for Code On The Go</h1></header>
<main>
  <div class="controls">
    <input id="q" type="search" placeholder="Search addons" aria-label="Search addons">
    <select id="type" aria-label="Filter by type">
      <option value="">All types</option>
      <option value="plugin">Plugins</option>
      <option value="template">Templates</option>
      <option value="snippet">Snippets</option>
      <option value="code-action">Code actions</option>
    </select>
  </div>
  <p id="status" role="status">Loading…</p>
  <ul id="cards"></ul>
</main>
<template id="card">
  <li class="card">
    <img class="icon" alt="" width="64" height="64">
    <div class="body">
      <h2><span data-slot="name"></span> <span class="badge" data-slot="type"></span></h2>
      <p data-slot="summary"></p>
      <p class="meta">
        <span data-slot="version"></span> ·
        <span data-slot="origin"></span> ·
        <span data-slot="size"></span>
      </p>
      <p class="links">
        <a data-slot="download">Download</a>
        <a data-slot="page">Details</a>
        <a data-slot="source">Source</a>
      </p>
    </div>
  </li>
</template>
<script type="module" src="/assets/app.js"></script>
</body>
</html>
EOF
```

- [x] **Step 2: Write the script**

```bash
cat > site/app.js <<'EOF'
const state = { addons: [], q: "", type: "" };

const cards = document.getElementById("cards");
const status = document.getElementById("status");
const template = document.getElementById("card");

function size(bytes) {
  const mb = bytes / 1048576;
  return mb >= 1 ? mb.toFixed(1) + " MB" : Math.round(bytes / 1024) + " KB";
}

function matches(addon) {
  if (state.type && addon.type !== state.type) return false;
  if (!state.q) return true;
  const text = [addon.name, addon.summary, addon.description, ...addon.tags]
    .join(" ").toLowerCase();
  return state.q.toLowerCase().split(/\s+/).every((word) => text.includes(word));
}

function render() {
  const shown = state.addons.filter(matches);
  cards.replaceChildren();
  for (const addon of shown) {
    const node = template.content.cloneNode(true);
    const set = (slot, value) => {
      node.querySelector(`[data-slot="${slot}"]`).textContent = value;
    };
    set("name", addon.name);
    set("type", addon.type);
    set("summary", addon.summary);
    set("version", "v" + addon.version);
    set("origin", addon.origin === "community" ? "Community" : "App Dev For All");
    set("size", size(addon.download.size));
    node.querySelector(".icon").src = addon.iconUrl;
    node.querySelector('[data-slot="download"]').href = addon.download.url;
    node.querySelector('[data-slot="page"]').href = addon.pageUrl;
    node.querySelector('[data-slot="source"]').href = addon.sourceUrl;
    cards.append(node);
  }
  status.textContent = shown.length ? "" : "No addon matches this search.";
  const url = new URL(location.href);
  url.search = new URLSearchParams(
    Object.entries({ q: state.q, type: state.type }).filter(([, v]) => v)
  ).toString();
  history.replaceState(null, "", url);
}

document.getElementById("q").addEventListener("input", (event) => {
  state.q = event.target.value;
  render();
});
document.getElementById("type").addEventListener("change", (event) => {
  state.type = event.target.value;
  render();
});

const params = new URLSearchParams(location.search);
state.q = params.get("q") || "";
state.type = params.get("type") || "";
document.getElementById("q").value = state.q;
document.getElementById("type").value = state.type;

fetch("/v1/catalog.json")
  .then((response) => {
    if (!response.ok) throw new Error(response.status);
    return response.json();
  })
  .then((document_) => {
    state.addons = document_.addons;
    render();
  })
  .catch(() => {
    status.textContent = "The addon list did not load. Please try again later.";
  });
EOF
```

- [x] **Step 3: Write the stylesheet**

```bash
cat > site/styles.css <<'EOF'
:root { --ink:#151827; --dim:#4b5268; --rule:#dce0ec; --ground:#f6f7fb; --card:#fff; }
@media (prefers-color-scheme: dark) {
  :root { --ink:#e7eaf4; --dim:#a8b0c6; --rule:#28304a; --ground:#0e111a; --card:#151a26; }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--ground); color:var(--ink); font:16px/1.5 system-ui, sans-serif; }
.chrome { padding:16px; border-bottom:1px solid var(--rule); }
.chrome h1 { margin:0; font-size:1.25rem; }
main { max-width:64rem; margin:0 auto; padding:16px; }
.controls { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:16px; }
.controls input { flex:1 1 12rem; }
.controls input, .controls select {
  padding:8px; border:1px solid var(--rule); border-radius:6px;
  background:var(--card); color:var(--ink); font:inherit;
}
#cards { list-style:none; padding:0; margin:0; display:grid; gap:12px;
         grid-template-columns:repeat(auto-fill, minmax(18rem, 1fr)); }
.card { display:flex; gap:12px; padding:12px; background:var(--card);
        border:1px solid var(--rule); border-radius:8px; }
.icon { flex:none; border-radius:8px; }
.card h2 { margin:0 0 4px; font-size:1rem; }
.card p { margin:0 0 6px; }
.badge { font-size:.7rem; text-transform:uppercase; letter-spacing:.06em;
         border:1px solid var(--rule); border-radius:4px; padding:1px 5px; color:var(--dim); }
.meta { color:var(--dim); font-size:.85rem; }
.links { display:flex; gap:12px; font-size:.9rem; }
.page { max-width:48rem; }
a:focus-visible, input:focus-visible, select:focus-visible { outline:2px solid var(--ink); outline-offset:2px; }
EOF
```

- [x] **Step 4: Check the page by hand**

Run:

```bash
mkdir -p /tmp/gallery/v1 && cp site/index.html site/app.js site/styles.css /tmp/gallery/
mkdir -p /tmp/gallery/assets && cp site/app.js site/styles.css /tmp/gallery/assets/
printf '{"schemaVersion":1,"generated":"x","addons":[]}' > /tmp/gallery/v1/catalog.json
cd /tmp/gallery && python3 -m http.server 8000
```

Open `http://localhost:8000/`. Expected: the page shows "No addon matches this search." and the browser console reports no error.

- [x] **Step 5: Commit**

```bash
git add site
git commit -m "Add the addon gallery"
```

---

## Already done — do not repeat

Two parts of the design are complete. Do not add a task for either one.

| Part | State |
|---|---|
| The Transform Rule that maps `/` to `/index.html` | Done. A live test confirmed it: `/` returns the same bytes as `/index.html`, with no redirect. |
| The bucket CORS policy | Done. A live test confirmed all four cases, including `Origin: null` and a `Range` preflight. The policy is recorded in `docs/addons-cors.json`. |

---

## Task 10: Check names and metadata on every pull request

**Files:**
- Modify: `.github/workflows/check-toolchain.yml` (append two steps)

**Interfaces:**
- Consumes: `addons check`, the test suite.
- Produces: nothing that other tasks use.

`check-toolchain.yml` is the only workflow that runs on a pull request. Therefore it is the only place that can stop a bad name before merge.

- [x] **Step 1: Add the steps**

Append this to the end of `.github/workflows/check-toolchain.yml`, at the same indent as the `Report declared versions` step:

```yaml
      - name: Install uv
        uses: astral-sh/setup-uv@v5

      - name: Test the addons tool
        run: uv run --directory tools/addons pytest -q

      - name: Check addon names and metadata
        run: uv run --directory tools/addons addons --root "$GITHUB_WORKSPACE" check
```

- [x] **Step 2: Check it locally**

Run: `uv run --directory tools/addons addons --root "$(pwd)" check`
Expected: it prints one line for each addon that has no `addon.json`. Task 13 fixes those.

- [x] **Step 3: Commit**

```bash
git add .github/workflows/check-toolchain.yml
git commit -m "Check addon names and metadata on every pull request"
```

---

## Task 11: The publish workflow

**Files:**
- Create: `.github/workflows/publish-addons.yml`

**Interfaces:**
- Consumes: every subcommand of the tool.
- Produces: nothing that other tasks use.

- [x] **Step 1: Write the workflow**

```bash
cat > .github/workflows/publish-addons.yml <<'EOF'
name: Publish addons

on:
  workflow_dispatch:
    inputs:
      addon:
        description: One addon directory name, or "all"
        default: all
      staging:
        description: Publish under staging/ instead of the live keys
        type: boolean
        default: true

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4

      - uses: astral-sh/setup-uv@v5

      - name: Check names and metadata
        run: uv run --directory tools/addons addons --root "$GITHUB_WORKSPACE" check

      - name: Build, package, and stage
        run: |
          set -euo pipefail
          mkdir -p dist
          if [ "${{ inputs.addon }}" = "all" ]; then
            names="$(uv run --directory tools/addons addons --root "$GITHUB_WORKSPACE" discover)"
          else
            names="${{ inputs.addon }}"
          fi
          for name in $names; do
            dir="$name"
            [ -d "$dir" ] || dir="plugins/$name"
            echo "==> $dir"
            ( cd "$dir"
              gradlew=../gradlew
              if [ -x ./gradlew ]; then gradlew=./gradlew; fi
              if grep -q downloadAssets build.gradle.kts; then
                "$gradlew" --console=plain downloadAssets
              fi
              "$gradlew" --console=plain assemblePlugin )
            slug="$(echo "$name" | tr '[:upper:]' '[:lower:]')"
            src="$(ls "$dir"/build/plugin/*.cgp | grep -v -- '-debug\.cgp$' | head -n1)"
            cp "$src" "dist/${slug}.cgp"
          done

      - name: Build the source tarballs
        run: uv run --directory tools/addons addons --root "$GITHUB_WORKSPACE" tarball --out "$GITHUB_WORKSPACE/dist"

      - name: Generate the catalog
        run: uv run --directory tools/addons addons --root "$GITHUB_WORKSPACE" catalog --dist "$GITHUB_WORKSPACE/dist" --out "$GITHUB_WORKSPACE/dist/catalog.json"

      - name: Publish to Cloudflare R2
        env:
          R2_ACCOUNT_ID: ${{ secrets.R2_ACCOUNT_ID }}
          R2_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}
          R2_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}
        run: |
          prefix=""
          if [ "${{ inputs.staging }}" = "true" ]; then
            prefix="staging/${{ github.run_id }}/"
          fi
          uv run --directory tools/addons addons --root "$GITHUB_WORKSPACE" publish \
            --dist "$GITHUB_WORKSPACE/dist" --prefix "$prefix"
          {
            echo "### Published"
            echo "Base: https://addons.appdevforall.org/${prefix}"
          } >> "$GITHUB_STEP_SUMMARY"
EOF
```

- [x] **Step 2: Add the `tarball` subcommand to the tool**

In `cli.py`, add this parser:

```python
    tarball_parser = sub.add_parser("tarball")
    tarball_parser.add_argument("--out", type=Path, required=True)
```

Add this branch:

```python
    if args.command == "tarball":
        args.out.mkdir(parents=True, exist_ok=True)
        for addon in discover.find_addons(args.root):
            meta = json.loads((addon / "addon.json").read_text())
            archive = tarball.build(args.root, addon, args.out, meta["license"])
            print(f"built {archive.name}")
        return 0
```

- [ ] **Step 3: Create the three repository secrets** — BLOCKED: needs an R2 API token that only the repository owner can mint.

In GitHub, open Settings, then Secrets and variables, then Actions. Add `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`. The R2 API token must have Object Read and Write on the `addons` bucket only. It must have no account permission.

- [ ] **Step 4: Run the workflow with `staging: true`** — BLOCKED by Step 3.

Run it from the Actions tab for one addon. Expected: the run summary prints a staging URL. Open the page and the download and confirm both work.

- [x] **Step 5: Commit**

```bash
git add .github/workflows/publish-addons.yml tools/addons
git commit -m "Add the publish workflow"
```

---

## Task 12: Remove the artifact steps and the old deploy

**Files:**
- Modify: `.github/workflows/build-plugins.yml:112-147`
- Modify: `.github/workflows/update-libs.yml:93-146` and `:158-247`
- Modify: `scripts/update-libs.sh:112-114`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

This task removes about 15 GB of transfer for each run and ends the use of the outside web host.

- [x] **Step 1: Remove the artifact steps from `build-plugins.yml`**

Delete the `Upload all .cgp as one bundle` step, which starts near line 112 and uses `actions/upload-artifact@v4`. Delete the whole `publish:` job, which starts at line 126 and runs to the end of the file. Keep the `build` job and the step that prints the addon names.

- [x] **Step 2: Check that no artifact step is left**

Run: `grep -rn 'upload-artifact\|download-artifact' .github/workflows/`
Expected: one match, in `update-libs.yml` line 141. Step 3 removes it.

- [x] **Step 3: Remove the deploy from `update-libs.yml`**

Delete these parts:
- the `Stage .cgp files with website filenames` step, near line 93 to line 138;
- the `actions/upload-artifact@v4` step, near line 140 to line 146;
- the whole `deploy:` job, from near line 158 to the end of the file.

Also delete the `PLUGINS_REMOTE_PATH` entry from the `env:` block at the top. Keep the `release` job, the commit of `libs/`, and the `softprops/action-gh-release@v2` step.

- [x] **Step 4: Check that the old host is gone**

Run: `grep -rin 'greengeeks\|scp\|id_rsa' .github/workflows/ scripts/`
Expected: no match.

- [x] **Step 5: Report the three jars this script does not refresh**

`libs/` holds five jars. The two Gradle tasks above produce only two of them.

**Do not try to copy the other three by searching for their names.** A name search finds `composite-builds/build-logic/common/build/libs/common.jar`, which is a different 21 KB artifact. `libs/common.jar` is 355 KB. Copying it replaces the IDE's jar with an unrelated one and breaks every plugin that uses it.

Add this after the two `cp` lines instead, so the staleness is loud:

```bash
for jar in common eventbus-events idetooltips; do
    if [ -f "$LIBS_DIR/${jar}.jar" ]; then
        echo "NOT REFRESHED: libs/${jar}.jar (last changed $(date -r "$LIBS_DIR/${jar}.jar" '+%Y-%m-%d'))" >&2
    fi
done
```

- [x] **Step 6: Use one discovery rule**

In `scripts/update-libs.sh`, replace the `SKIP_PLUGINS` array and the loop that fills `PLUGINS` with this:

```bash
# Do not use mapfile here. macOS ships bash 3.2, which does not have it.
PLUGINS=()
while IFS= read -r line; do
    PLUGINS+=("$line")
done < <(uv run --directory "$REPO_ROOT/tools/addons" addons --root "$REPO_ROOT" discover)
```

Make the same replacement in `.githooks/pre-push`, inside the `plugin_dirs` function.

Do not change `scripts/check-toolchain.sh`. It walks every Gradle module on purpose, which is a wider set than the addons.

- [x] **Step 7: Run the script against a local checkout**

Run: `./scripts/update-libs.sh --local ../CodeOnTheGo --plugin Keystore-Generator`
Expected: it prints the five jar names and builds one addon.

- [x] **Step 8: Commit**

```bash
git add .github/workflows scripts/update-libs.sh .githooks/pre-push
git commit -m "Remove the artifact steps and the old deploy; refresh all five jars"
```

---

## Task 13: Rename the addons and move them

**Files:**
- Modify: every addon directory name, every description page name, `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `addons check`.
- Produces: the final directory layout.

Do this task in small commits. Do not rename `plugin.id`, `namespace`, or `applicationId`.

- [x] **Step 1: Rename the directories and the pages**

```bash
cat > /tmp/renames.txt <<'EOF'
Beepy Voice-Alerts
apk-viewer APK-Analyzer
bookshelf Bookshelf
client-time-tracker Client-Time-Tracker
code-suggestions-plugin Code-Suggestions
compose-preview Jetpack-Compose-Preview
flutter-template Flutter-Templates
get-ai-models Get-AI-Models
icons-repository Icons-Repository
keystore-generator Keystore-Generator
layout-editor Layout-Editor
markdown-preview Markdown-Previewer
ndk-installer-plugin NDK-Installer
pair-programming-plugin Code-Together
project-to-template Project-to-Template
python-tools Python-Tools
rainbow-on-the-go Rainbow-Brackets
random-xkcd Random-XKCD
sketch-to-ui-plugin Sketch-to-UI
snippets Favorite-Snippets
speech-to-text-plugin Speech-to-Text
template-manager Template-Manager
vector-search-plugin Vector-Search
EOF

while read -r old new; do
    [ -d "$old" ] || { echo "skip $old"; continue; }
    git mv "$old" "$new"
    slug="$(echo "$new" | tr '[:upper:]' '[:lower:]')"
    page="$(ls "$new"/*.html 2>/dev/null | head -n1)"
    [ -n "$page" ] && [ "$(basename "$page")" != "$slug.html" ] && git mv "$page" "$new/$slug.html"
    git mv "$new" "plugins/$new"
done < /tmp/renames.txt
```

The `ai-*` addons are not in the list. Leave them where they are. Other pull requests are changing them.

`snippets` becomes `Favorite-Snippets` in this step. Only after that can a `snippets/` directory exist for the snippet addon type.

- [x] **Step 2: Make the names agree inside each addon**

For each renamed addon, set `pluginBuilder { pluginName = "<slug>" }` in `build.gradle.kts`, set `plugin.name` in `src/main/AndroidManifest.xml` to the display name, and set the `<title>` of the page to the display name.

Run: `uv run --directory tools/addons addons --root "$(pwd)" check`
Expected: it reports only missing `addon.json` files.

- [x] **Step 3: Write the metadata**

Write one `addon.json` for each addon, with its description page open beside you. Correct the summary, write a real description, and add tags. Set `origin` to `community` and fill in `author` for a community addon.

Run: `uv run --directory tools/addons addons --root "$(pwd)" check`
Expected: no output, exit code 0.

- [x] **Step 4: Create the snippet directory**

```bash
mkdir -p snippets
cat > snippets/README.md <<'EOF'
# Snippets

Snippet addons for Code On The Go. This directory is empty for now.
EOF
```

- [x] **Step 5: Update the documentation**

In `README.md`, update the table of addons to the new names. In `CLAUDE.md`, correct two errors: the `MAP` array no longer exists, and `libs/` holds five jars, not two.

- [x] **Step 6: Verify on a device**

Build one renamed addon. Install the `.cgp` through the Plugin Manager. Long-press an element that has a tooltip and confirm the tooltip text appears. A tooltip that shows `n/a` means the rename broke the identity, which Step 1 must not do.

```bash
adb shell am start -n com.itsaky.androidide/.activities.SplashActivity
```

- [x] **Step 7: Commit**

```bash
git add -A
git commit -m "Rename the addons and move them under plugins/"
```

---

## Self-Review

Run this list after the last task.

- [x] `grep -rn 'upload-artifact\|download-artifact' .github/workflows/` prints nothing.
- [x] `grep -rin 'greengeeks' .github/workflows/ scripts/` prints nothing.
- [x] `uv run --directory tools/addons pytest -q` passes.
- [x] `uv run --directory tools/addons addons --root "$(pwd)" check` exits 0.
- [x] `https://addons.appdevforall.org/` shows the gallery.
- [x] `https://addons.appdevforall.org/v1/catalog.json` returns the catalog, and no field is `null`.
- [x] One `.cgp` downloads in a browser instead of showing as text.
- [x] One tarball extracts and builds:

```bash
curl -sO https://addons.appdevforall.org/src/keystore-generator-src.tar.gz
tar xzf keystore-generator-src.tar.gz && cd keystore-generator-src/Keystore-Generator
echo "sdk.dir=$ANDROID_HOME" > local.properties
../gradlew assemblePlugin
```

- [x] The GreenGeeks credentials are revoked. Do this last.

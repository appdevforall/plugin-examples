import json
import re
from pathlib import Path

import jsonschema

from addons import discover, model

SCHEMA = json.loads((Path(__file__).parent / "addon.schema.json").read_text())


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

        plugin_name = model.manifest_value(path, "plugin.name")
        if plugin_name and plugin_name != name:
            problems.append(
                f"{directory}: plugin.name is '{plugin_name}', expected '{name}'")

        page = path / f"{addon_slug}.html"
        if not page.exists():
            problems.append(f"{directory}: the page must be named {addon_slug}.html")
        elif f"<title>{name}</title>" not in page.read_text():
            problems.append(f"{directory}: the page title must be '{name}'")
    return problems


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

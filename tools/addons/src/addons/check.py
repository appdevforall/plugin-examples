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

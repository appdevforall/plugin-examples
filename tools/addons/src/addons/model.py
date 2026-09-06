import re
from pathlib import Path

DEFAULT_VERSION = "1.0.0"
RELEASE_VERSION = re.compile(r"^[0-9]{2}\.[0-9]{2}$")

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


def manifest_value(addon: Path, key: str) -> str:
    f = addon / "src" / "main" / "AndroidManifest.xml"
    if not f.exists():
        return ""
    text = " ".join(f.read_text().split())
    # match the whole element: attribute order is not guaranteed, and a
    # missed match surfaces much later as an opaque schema failure
    for element in re.findall(r"<meta-data\b[^>]*/?>", text):
        if re.search(r'android:name="%s"' % re.escape(key), element):
            value = re.search(r'android:value="([^"]*)"', element)
            return value.group(1) if value else ""
    return ""


def plugin_id(addon: Path) -> str:
    return manifest_value(addon, "plugin.id")


def version(addon: Path) -> str:
    """The version a user sees for this addon.

    A literal in the manifest is not substituted, so it ships verbatim and
    wins. Otherwise the builder replaces ${pluginVersion} with versionName
    plus a build-timestamp suffix; the catalog reports the semantic part,
    since the suffix changes on every build and carries no meaning.
    """
    declared = manifest_value(addon, "plugin.version")
    if declared and not declared.startswith("${"):
        return declared
    build = addon / "build.gradle.kts"
    if build.exists():
        found = re.search(r'versionName\s*=\s*"([^"]+)"', build.read_text())
        if found:
            return found.group(1)
    return DEFAULT_VERSION


def min_app_version(addon: Path) -> str:
    """The lowest Code On The Go release this addon declares.

    The app's release version is YY.ww. Thirteen addons still carry the
    legacy "1.0.0" placeholder, which states no real minimum, so it is
    reported as no minimum rather than invented.
    """
    declared = manifest_value(addon, "plugin.min_ide_version")
    return declared if RELEASE_VERSION.match(declared) else ""

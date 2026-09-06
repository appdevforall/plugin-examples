import re
import shutil
import subprocess
import tarfile
from pathlib import Path

from addons import model

SOURCE = "https://github.com/appdevforall/plugin-examples"

README = """# {name}

Source for the {name} addon for Code On The Go.

- Origin: {origin}
- Author: {author}
- Comes from: {source}/tree/main/{directory}
- License: {license}. The full text is in LICENSE beside this file.

## Build

    cd {directory}
    {up}gradlew assemblePlugin

The plugin file appears in `{directory}/build/plugin/`.

You must create `{directory}/local.properties` with one line:

    sdk.dir=/path/to/your/Android/sdk
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


def _stage(root: Path, addon: Path, out: Path, meta: dict) -> Path:
    top = out / f"{model.slug(addon.name)}-src"
    if top.exists():
        shutil.rmtree(top)
    top.mkdir(parents=True)

    files = tracked_files(root, addon)
    if not files:
        raise RuntimeError(f"{addon.name}: git tracks no file in this directory")
    # Mirror the repository path exactly, so every "../" in a build file
    # resolves inside the archive without any rewriting.
    for relative in files:
        target = top / relative
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

    # AGPL source distribution: ship the licence text the notice refers to
    licence_file = root / "LICENSE"
    if licence_file.exists():
        shutil.copy2(licence_file, top / "LICENSE")

    author = meta.get("author") or {}
    inside = addon.relative_to(root).as_posix()
    (top / "README.md").write_text(README.format(
        name=model.display_name(addon.name), directory=inside,
        up="../" * len(Path(inside).parts),
        origin="Community contribution" if meta.get("origin") == "community"
               else "App Dev for All",
        author=f"{author.get('name', 'App Dev for All')}"
               + (f" ({author['url']})" if author.get("url") else ""),
        source=SOURCE,
        license=meta.get("license", "AGPL-3.0-or-later")))
    return top


# Anything matching these must never reach a published tarball. Section 9.3
# names Sentry DSNs as the material at risk, and they do not live only in
# local.properties.
SECRET_NAMES = ("local.properties", ".env", "keystore.properties",
                "sentry.properties", "secrets.properties", "google-services.json")
SECRET_SUFFIXES = (".jks", ".keystore", ".p12", ".pem", ".key")


def is_inside(path: Path, root: Path) -> bool:
    """A string prefix test accepts /out/foo-src-evil for root /out/foo-src."""
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def verify(top: Path, inside: str, jars: list[str]) -> None:
    problems = []
    for jar in jars:
        if not (top / "libs" / jar).exists():
            problems.append(f"libs/{jar} is missing")
    if not (top / "gradlew").exists():
        problems.append("gradlew is missing")
    if not (top / "gradle" / "wrapper" / "gradle-wrapper.properties").exists():
        problems.append("the wrapper properties file is missing")
    for name in ("build.gradle.kts", "settings.gradle.kts"):
        if not (top / inside / name).exists():
            problems.append(f"{inside}/{name} is missing")
    for path in top.rglob("*"):
        rel = path.relative_to(top).as_posix()
        if path.name in SECRET_NAMES or path.suffix in SECRET_SUFFIXES:
            problems.append(f"{rel} could carry a secret")
        if path.is_dir() and path.name in (".gradle", "build"):
            problems.append(f"{rel}/ is build output")
        if path.is_absolute() and not is_inside(path, top):
            problems.append(f"{rel} is outside the archive root")
        if not is_inside(path, top):
            problems.append(f"{rel} escapes the archive root")
    if problems:
        raise RuntimeError(f"{inside}: " + "; ".join(problems))


def build(root: Path, addon: Path, out: Path,
          meta: dict | None = None) -> Path:
    jars = jars_for(addon)
    if not jars:
        raise RuntimeError(f"{addon.name}: it references no shared jar")
    top = _stage(root, addon, out, meta or {})
    verify(top, addon.relative_to(root).as_posix(), jars)
    archive = out / f"{top.name}.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(top, arcname=top.name)
    shutil.rmtree(top)
    return archive

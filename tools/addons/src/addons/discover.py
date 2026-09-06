from pathlib import Path

PREDICATE = "com.itsaky.androidide.plugins.build"

# The four addon areas, plus the repository root for addons not yet migrated.
# catalog.TYPES maps the same four; keep them in step.
AREAS = ("plugins", "templates", "snippets", "code-actions")


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


def find_addons(root: Path, only: list[str] | None = None) -> list[Path]:
    skip = read_skip(root)
    found = []
    patterns = ["*/build.gradle.kts"] + [f"{a}/*/build.gradle.kts" for a in AREAS]
    for pattern in patterns:
        for f in root.glob(pattern):
            if PREDICATE in f.read_text(errors="ignore") and f.parent.name not in skip:
                found.append(f.parent)
    found = sorted(found, key=lambda p: p.name)
    if only is None:
        return found

    # accept either the repo-relative path or the bare directory name
    wanted, chosen = list(only), []
    for name in only:
        match = next((p for p in found
                      if p.name == name
                      or p.relative_to(root).as_posix() == name), None)
        if match is None:
            raise RuntimeError(f"{name}: not a known addon")
        chosen.append(match)
    return sorted(set(chosen), key=lambda p: p.name)

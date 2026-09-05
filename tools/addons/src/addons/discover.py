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

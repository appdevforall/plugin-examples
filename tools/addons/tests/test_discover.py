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

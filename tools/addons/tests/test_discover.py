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


def test_cli_prints_repo_relative_paths(tmp_path, capsys):
    """Callers cd into these and grep changed-file lists with them, so the
    location has to survive. Printing a bare name loses it."""
    from addons import cli
    make_addon(tmp_path, "plugins/Voice-Alerts")
    make_addon(tmp_path, "Legacy-Addon")
    cli.main(["--root", str(tmp_path), "discover"])
    assert capsys.readouterr().out.split() == ["Legacy-Addon", "plugins/Voice-Alerts"]


def test_only_selects_a_subset_by_path_or_name(tmp_path):
    make_addon(tmp_path, "plugins/Voice-Alerts")
    make_addon(tmp_path, "plugins/Keystore-Generator")
    by_name = discover.find_addons(tmp_path, only=["Voice-Alerts"])
    by_path = discover.find_addons(tmp_path, only=["plugins/Voice-Alerts"])
    assert [p.name for p in by_name] == ["Voice-Alerts"]
    assert [p.name for p in by_path] == ["Voice-Alerts"]


def test_only_rejects_an_unknown_addon(tmp_path):
    import pytest
    make_addon(tmp_path, "plugins/Voice-Alerts")
    with pytest.raises(RuntimeError, match="Nope"):
        discover.find_addons(tmp_path, only=["Nope"])


def test_finds_every_addon_type(tmp_path):
    for area in ("plugins", "templates", "snippets", "code-actions"):
        make_addon(tmp_path, f"{area}/Some-Addon-{area}")
    found = [p.relative_to(tmp_path).as_posix() for p in discover.find_addons(tmp_path)]
    assert sorted(found) == [
        "code-actions/Some-Addon-code-actions",
        "plugins/Some-Addon-plugins",
        "snippets/Some-Addon-snippets",
        "templates/Some-Addon-templates",
    ]


def test_skipped_addons_can_still_be_listed_for_compiling(tmp_path):
    """Two different questions: what do we publish, and what must still
    compile. A skipped addon is excluded from the gallery, not from the
    build that proves a libs refresh did not break it."""
    make_addon(tmp_path, "plugins/Shipping")
    make_addon(tmp_path, "held-back")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text("held-back  reason\n")
    assert [p.name for p in discover.find_addons(tmp_path)] == ["Shipping"]
    assert [p.name for p in discover.find_addons(tmp_path, include_skipped=True)] \
        == ["Shipping", "held-back"]


def test_a_bang_prefix_means_never_build(tmp_path):
    make_addon(tmp_path, "plugins/Shipping")
    make_addon(tmp_path, "held-back")
    make_addon(tmp_path, "broken")
    (tmp_path / "tools" / "addons").mkdir(parents=True)
    (tmp_path / "tools" / "addons" / "skip.txt").write_text(
        "held-back  held out of the gallery\n!broken  does not build at all\n")
    assert [p.name for p in discover.find_addons(tmp_path)] == ["Shipping"]
    # compile coverage picks up the held-back one but never the broken one
    assert [p.name for p in discover.find_addons(tmp_path, include_skipped=True)] \
        == ["Shipping", "held-back"]

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


def test_every_addon_needs_an_author(tmp_path):
    """R15 asks for an author on community addons. The schema requires one
    from every addon, which is stricter and simpler, and D12 wants the field
    always present. This test states the rule that is actually enforced."""
    bad = dict(GOOD)
    del bad["author"]
    make_addon(tmp_path, bad)
    assert any("author" in p for p in check.check_metadata(tmp_path))


def test_an_author_without_a_url_fails(tmp_path):
    make_addon(tmp_path, GOOD | {"author": {"name": "Someone"}})
    assert check.check_metadata(tmp_path) != []


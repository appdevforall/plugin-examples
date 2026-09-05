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


def test_community_needs_an_author(tmp_path):
    bad = dict(GOOD, origin="community")
    del bad["author"]
    make_addon(tmp_path, bad)
    assert check.check_metadata(tmp_path) != []


def test_bad_min_app_version_fails(tmp_path):
    make_addon(tmp_path, GOOD | {"minAppVersion": "1.0.0"})
    assert check.check_metadata(tmp_path) != []

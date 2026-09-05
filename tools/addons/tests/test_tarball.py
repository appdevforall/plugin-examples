import subprocess
import tarfile
from pathlib import Path

import pytest

from addons import tarball

PREDICATE = "com.itsaky.androidide.plugins.build"


def make_repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    (tmp_path / "libs").mkdir()
    for jar in ("plugin-api.jar", "gradle-plugin.jar", "common.jar"):
        (tmp_path / "libs" / jar).write_bytes(b"jar")
    (tmp_path / "gradlew").write_text("#!/bin/sh\n")
    (tmp_path / "gradle" / "wrapper").mkdir(parents=True)
    (tmp_path / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text("x")

    addon = tmp_path / "Keystore-Generator"
    (addon / "src").mkdir(parents=True)
    (addon / "build.gradle.kts").write_text(
        PREDICATE + '\ncompileOnly(files("../libs/plugin-api.jar"))\n')
    (addon / "settings.gradle.kts").write_text(
        'classpath(files("../libs/gradle-plugin.jar"))\n')
    (addon / "src" / "Main.kt").write_text("fun main() {}")
    (addon / "local.properties").write_text("sdk.dir=/Users/someone/Android")
    (tmp_path / ".gitignore").write_text("local.properties\n")
    subprocess.run(["git", "-C", str(tmp_path), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "-c", "user.email=t@t",
                    "-c", "user.name=t", "commit", "-qm", "x"], check=True)
    return addon


def test_finds_only_the_jars_the_addon_uses(tmp_path):
    addon = make_repo(tmp_path)
    assert tarball.jars_for(addon) == ["gradle-plugin.jar", "plugin-api.jar"]


def test_archive_has_the_two_level_shape(tmp_path):
    addon = make_repo(tmp_path)
    out = tmp_path / "dist"
    out.mkdir()
    archive = tarball.build(tmp_path, addon, out)
    assert archive.name == "keystore-generator-src.tar.gz"
    with tarfile.open(archive) as tar:
        names = set(tar.getnames())
    top = "keystore-generator-src"
    assert f"{top}/libs/plugin-api.jar" in names
    assert f"{top}/libs/gradle-plugin.jar" in names
    assert f"{top}/libs/common.jar" not in names
    assert f"{top}/gradlew" in names
    assert f"{top}/gradle/wrapper/gradle-wrapper.properties" in names
    assert f"{top}/Keystore-Generator/build.gradle.kts" in names
    assert f"{top}/README.md" in names


def test_local_properties_never_reaches_the_archive(tmp_path):
    addon = make_repo(tmp_path)
    out = tmp_path / "dist"
    out.mkdir()
    archive = tarball.build(tmp_path, addon, out)
    with tarfile.open(archive) as tar:
        assert not any("local.properties" in n for n in tar.getnames())


def test_a_missing_jar_stops_the_build(tmp_path):
    addon = make_repo(tmp_path)
    (tmp_path / "libs" / "plugin-api.jar").unlink()
    out = tmp_path / "dist"
    out.mkdir()
    with pytest.raises(RuntimeError, match="plugin-api.jar"):
        tarball.build(tmp_path, addon, out)

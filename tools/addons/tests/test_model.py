from addons import model


def test_display_name_replaces_hyphens():
    assert model.display_name("Keystore-Generator") == "Keystore Generator"
    assert model.display_name("Project-to-Template") == "Project to Template"


def test_slug_is_the_lowercased_directory():
    assert model.slug("Keystore-Generator") == "keystore-generator"
    assert model.slug("Random-XKCD") == "random-xkcd"


def test_valid_directory_names():
    assert model.directory_is_valid("Keystore-Generator")
    assert model.directory_is_valid("Project-to-Template")
    assert model.directory_is_valid("Random-XKCD")


def test_invalid_directory_names():
    assert not model.directory_is_valid("keystore-generator")
    assert not model.directory_is_valid("Project-To-Template")
    assert not model.directory_is_valid("Keystore_Generator")


from pathlib import Path

MANIFEST = """<manifest><application>
    <meta-data
        android:name="plugin.id"
        android:value="com.appdevforall.keygen.plugin" />
    <meta-data
        android:name="plugin.version"
        android:value="{version}" />
</application></manifest>"""


def make(tmp_path: Path, version: str, build: str = "") -> Path:
    addon = tmp_path / "Keystore-Generator"
    (addon / "src" / "main").mkdir(parents=True)
    (addon / "src" / "main" / "AndroidManifest.xml").write_text(
        MANIFEST.format(version=version))
    (addon / "build.gradle.kts").write_text(build)
    return addon


def test_reads_the_plugin_id(tmp_path):
    addon = make(tmp_path, "${pluginVersion}")
    assert model.plugin_id(addon) == "com.appdevforall.keygen.plugin"


def test_a_placeholder_falls_back_to_the_default(tmp_path):
    addon = make(tmp_path, "${pluginVersion}")
    assert model.version(addon) == "1.0.0"


def test_a_literal_in_the_manifest_wins_over_the_default(tmp_path):
    addon = make(tmp_path, "1.0.1")
    assert model.version(addon) == "1.0.1"


def test_a_manifest_literal_wins_because_it_is_not_substituted(tmp_path):
    # Icons-Repository hardcodes 1.0.1 and its .cgp ships exactly that
    addon = make(tmp_path, "1.0.1", build='android { defaultConfig { versionName = "1.0.0" } }')
    assert model.version(addon) == "1.0.1"


def test_the_placeholder_resolves_to_version_name(tmp_path):
    # The builder substitutes versionName for ${pluginVersion}. Flutter-Templates
    # declares 2.0.0 and its .cgp ships 2.0.0-release.<timestamp>; the catalog
    # reports the semantic part.
    addon = make(tmp_path, "${pluginVersion}",
                 build='android {\n  defaultConfig {\n    versionName = "2.0.0"\n  }\n}\n')
    assert model.version(addon) == "2.0.0"


def test_a_missing_manifest_gives_an_empty_id(tmp_path):
    addon = tmp_path / "Empty"
    addon.mkdir()
    assert model.plugin_id(addon) == ""


MANIFEST_MIN = """<manifest><application>
    <meta-data
        android:name="plugin.min_ide_version"
        android:value="{v}" />
</application></manifest>"""


def make_min(tmp_path: Path, value: str) -> Path:
    addon = tmp_path / "Some-Addon"
    (addon / "src" / "main").mkdir(parents=True)
    (addon / "src" / "main" / "AndroidManifest.xml").write_text(
        MANIFEST_MIN.format(v=value))
    return addon


def test_a_real_minimum_is_used(tmp_path):
    assert model.min_app_version(make_min(tmp_path, "26.17")) == "26.17"


def test_the_legacy_placeholder_means_no_minimum(tmp_path):
    assert model.min_app_version(make_min(tmp_path, "1.0.0")) == ""


def test_a_missing_minimum_is_empty(tmp_path):
    addon = tmp_path / "Bare"
    addon.mkdir()
    assert model.min_app_version(addon) == ""


def test_manifest_value_survives_attribute_order(tmp_path):
    addon = tmp_path / "Reordered"
    (addon / "src" / "main").mkdir(parents=True)
    (addon / "src" / "main" / "AndroidManifest.xml").write_text(
        '<manifest><application>'
        '<meta-data android:value="com.example.foo" android:name="plugin.id" />'
        '</application></manifest>')
    assert model.plugin_id(addon) == "com.example.foo"

from pathlib import Path

from addons import check

PREDICATE = "com.itsaky.androidide.plugins.build"

MANIFEST = """<manifest><application>
<meta-data android:name="plugin.id" android:value="com.appdevforall.keygen.plugin" />
<meta-data android:name="plugin.name" android:value="{name}" />
</application></manifest>"""


def make_addon(root: Path, directory: str, plugin_name: str,
               gradle_name: str, page: str) -> Path:
    d = root / directory
    (d / "src" / "main").mkdir(parents=True)
    (d / "build.gradle.kts").write_text(
        PREDICATE + '\npluginBuilder { pluginName = "%s" }\n' % gradle_name)
    (d / "src" / "main" / "AndroidManifest.xml").write_text(
        MANIFEST.format(name=plugin_name))
    (d / page).write_text("<html><title>%s</title><body>x</body></html>" % plugin_name)
    return d


def test_a_compliant_addon_passes(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
               "keystore-generator", "keystore-generator.html")
    assert check.check_names(tmp_path) == []


def test_a_wrong_page_filename_fails(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
               "keystore-generator", "keygen.html")
    problems = check.check_names(tmp_path)
    assert len(problems) == 1
    assert "keystore-generator.html" in problems[0]


def test_a_wrong_plugin_name_fails(tmp_path):
    make_addon(tmp_path, "Keystore-Generator", "Key Gen",
               "keystore-generator", "keystore-generator.html")
    problems = check.check_names(tmp_path)
    assert any("plugin.name" in p for p in problems)


def test_a_bad_directory_name_fails(tmp_path):
    make_addon(tmp_path, "keystore-generator", "keystore generator",
               "keystore-generator", "keystore-generator.html")
    problems = check.check_names(tmp_path)
    assert any("directory" in p for p in problems)

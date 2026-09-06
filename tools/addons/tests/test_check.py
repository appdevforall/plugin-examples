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
    (d / "src" / "main" / "assets").mkdir(parents=True)
    for icon in ("icon_day.png", "icon_night.png"):
        (d / "src" / "main" / "assets" / icon).write_bytes(b"png")
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


def test_a_stale_h1_fails(tmp_path):
    """The rename touched only <title>. The <h1> is what a visitor reads."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body>"
        "<h1>Key Gen Plugin</h1></body></html>")
    problems = check.check_names(tmp_path)
    assert any("h1" in p.lower() for p in problems)


def test_a_shorthand_product_name_fails(tmp_path):
    """Global Constraint 6: these pages are published, so the rule applies."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body><h1>Keystore Generator</h1>"
        "<p>A CoGo plugin.</p></body></html>")
    assert any("CoGo" in p for p in check.check_names(tmp_path))


def test_the_rule_does_not_fire_inside_code(tmp_path):
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "keystore-generator.html").write_text(
        "<html><title>Keystore Generator</title><body><h1>Keystore Generator</h1>"
        "<p>Clone <code>CodeOnTheGo.git</code> first.</p></body></html>")
    assert not [p for p in check.check_names(tmp_path) if "CodeOnTheGo" in p]


def test_a_missing_icon_fails_the_check(tmp_path):
    """Better here than half way through a publish, with the bucket already
    holding artifacts the catalog will never reference."""
    d = make_addon(tmp_path, "Keystore-Generator", "Keystore Generator",
                   "keystore-generator", "keystore-generator.html")
    (d / "src" / "main" / "assets" / "icon_night.png").unlink()
    problems = check.check_names(tmp_path)
    assert any("icon_night.png" in p for p in problems)

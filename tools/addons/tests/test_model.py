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

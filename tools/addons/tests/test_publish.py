from addons import publish


class FakeClient:
    def __init__(self):
        self.store = {}
        self.order = []

    def put_object(self, Bucket, Key, Body, **headers):
        self.store[Key] = (Body, headers)
        self.order.append(Key)


def test_headers_for_a_download():
    head = publish.headers_for("dl/keystore-generator.cgp")
    assert head["ContentType"] == "application/octet-stream"
    assert head["CacheControl"] == "public, max-age=60"
    assert head["ContentDisposition"] == 'attachment; filename="keystore-generator.cgp"'


def test_headers_for_a_page():
    head = publish.headers_for("p/keystore-generator.html")
    assert head["ContentType"] == "text/html"
    assert "ContentDisposition" not in head


def test_headers_for_an_asset():
    head = publish.headers_for("assets/styles.abc12345.css")
    assert head["ContentType"] == "text/css"
    assert head["CacheControl"] == "public, max-age=31536000, immutable"


def test_the_catalog_goes_last(tmp_path):
    one = tmp_path / "a.cgp"
    one.write_bytes(b"a")
    document = tmp_path / "catalog.json"
    document.write_bytes(b"{}")
    client = FakeClient()
    publish.publish(client, "addons", [("dl/a.cgp", one)],
                    ("v1/catalog.json", document))
    assert client.order == ["dl/a.cgp", "v1/catalog.json"]


def test_bucket_comes_from_the_environment(monkeypatch):
    monkeypatch.setenv("R2_BUCKET", "addons-staging")
    assert publish.bucket_from_env() == "addons-staging"


def test_bucket_defaults_to_addons(monkeypatch):
    monkeypatch.delenv("R2_BUCKET", raising=False)
    assert publish.bucket_from_env() == "addons"


def test_hashed_name_is_stable_and_content_derived(tmp_path):
    f = tmp_path / "app.js"
    f.write_bytes(b"one")
    first = publish.hashed_name(f)
    assert publish.hashed_name(f) == first          # stable for same bytes
    assert first.startswith("app.") and first.endswith(".js")
    f.write_bytes(b"two")
    assert publish.hashed_name(f) != first          # changes with content


def test_hashed_assets_are_immutable():
    head = publish.headers_for("assets/app.abc12345.js")
    assert head["CacheControl"] == "public, max-age=31536000, immutable"
    assert publish.headers_for("index.html")["CacheControl"] == "public, max-age=60"

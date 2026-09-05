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
    head = publish.headers_for("assets/styles.css")
    assert head["ContentType"] == "text/css"
    assert head["CacheControl"] == "public, max-age=60"


def test_the_catalog_goes_last(tmp_path):
    one = tmp_path / "a.cgp"
    one.write_bytes(b"a")
    document = tmp_path / "catalog.json"
    document.write_bytes(b"{}")
    client = FakeClient()
    publish.publish(client, "addons", [("dl/a.cgp", one)],
                    ("v1/catalog.json", document))
    assert client.order == ["dl/a.cgp", "v1/catalog.json"]

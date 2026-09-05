from addons import page

TEMPLATE = "<html><title>{{title}}</title><body>{{body}}</body></html>"


def test_keeps_the_body_and_drops_the_old_shell():
    source = ("<html><head><style>p{color:red}</style></head>"
              "<body><h1>Keystore Generator</h1><p>Text.</p></body></html>")
    result = page.wrap(source, "Keystore Generator", TEMPLATE)
    assert "<h1>Keystore Generator</h1><p>Text.</p>" in result
    assert "color:red" not in result
    assert "<title>Keystore Generator</title>" in result


def test_accepts_a_fragment_with_no_body_tag():
    result = page.wrap("<p>Text.</p>", "Name", TEMPLATE)
    assert "<p>Text.</p>" in result


def test_the_title_is_escaped():
    result = page.wrap("<p>x</p>", 'Ab<script>alert(1)</script>', TEMPLATE)
    assert "<script>" not in result
    assert "&lt;script&gt;" in result


def test_the_title_cannot_inject_the_body_placeholder():
    result = page.wrap("<p>real body</p>", "{{body}}", TEMPLATE)
    assert "real body" in result

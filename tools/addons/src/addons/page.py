import html
import re

PLACEHOLDER = re.compile(r"\{\{title\}\}|\{\{body\}\}")


def wrap(page_html: str, name: str, template: str) -> str:
    found = re.search(r"<body[^>]*>(.*)</body>", page_html, re.S | re.I)
    body = found.group(1) if found else page_html
    # The body is HTML we author, so it is inserted as-is. The title is a
    # directory name, so it is escaped. One pass, so neither value can
    # inject the other's placeholder, and no backslash in the body is
    # read as a replacement escape.
    values = {"{{title}}": html.escape(name), "{{body}}": body.strip()}
    return PLACEHOLDER.sub(lambda m: values[m.group(0)], template)

import re


def wrap(html: str, name: str, template: str) -> str:
    found = re.search(r"<body[^>]*>(.*)</body>", html, re.S | re.I)
    body = found.group(1) if found else html
    return template.replace("{{title}}", name).replace("{{body}}", body.strip())

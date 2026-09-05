import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

import jsonschema

from addons import discover, model

BASE = "https://addons.appdevforall.org"
SOURCE = "https://github.com/appdevforall/plugin-examples/tree/main"
TYPES = {"plugins": "plugin", "templates": "template",
         "snippets": "snippet", "code-actions": "code-action"}
VERSION = re.compile(r"^[0-9]+(\.[0-9]+)*$")


def _file(path: Path, url: str) -> dict:
    data = path.read_bytes()
    return {"url": url, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def entry(root: Path, addon: Path, cgp: Path, archive: Path) -> dict:
    directory = addon.name
    slug = model.slug(directory)
    meta = json.loads((addon / "addon.json").read_text())
    version = model.version(addon)
    if not VERSION.match(version):
        raise RuntimeError(f"{directory}: the version '{version}' is not a number")
    relative = addon.relative_to(root).as_posix()
    return {
        "type": TYPES.get(addon.parent.name, "plugin"),
        "slug": slug,
        "pluginId": model.plugin_id(addon),
        "name": model.display_name(directory),
        "version": version,
        "summary": meta["summary"],
        "description": meta["description"],
        "origin": meta["origin"],
        "license": meta["license"],
        "tags": meta["tags"],
        "author": {"name": meta["author"]["name"], "url": meta["author"]["url"]},
        "minAppVersion": model.min_app_version(addon),
        "iconUrl": f"{BASE}/p/{slug}.png",
        "pageUrl": f"{BASE}/p/{slug}.html",
        "sourceUrl": f"{SOURCE}/{relative}",
        "download": _file(cgp, f"{BASE}/dl/{slug}.cgp"),
        "sourceTarball": _file(archive, f"{BASE}/src/{slug}-src.tar.gz"),
    }


def build(root: Path, dist: Path) -> dict:
    entries = []
    for addon in discover.find_addons(root):
        slug = model.slug(addon.name)
        cgp = dist / f"{slug}.cgp"
        archive = dist / f"{slug}-src.tar.gz"
        for f in (cgp, archive):
            if not f.exists():
                raise RuntimeError(f"{addon.name}: {f.name} is missing from {dist}")
        entries.append(entry(root, addon, cgp, archive))

    document = {
        "schemaVersion": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "addons": entries,
    }
    schema = json.loads((root / "site" / "catalog.schema.json").read_text())
    jsonschema.validate(document, schema)
    return document

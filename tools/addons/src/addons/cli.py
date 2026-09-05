import argparse
import json
import sys
from pathlib import Path

from addons import catalog, check, discover, model, page, publish, tarball


def main() -> int:
    parser = argparse.ArgumentParser(prog="addons")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("discover")
    sub.add_parser("check")

    catalog_parser = sub.add_parser("catalog")
    catalog_parser.add_argument("--dist", type=Path, required=True)
    catalog_parser.add_argument("--out", type=Path, required=True)
    catalog_parser.add_argument("--base", default=catalog.BASE,
                                help="site base the catalog is published under")

    publish_parser = sub.add_parser("publish")
    publish_parser.add_argument("--dist", type=Path, required=True)
    publish_parser.add_argument("--prefix", default="")

    tarball_parser = sub.add_parser("tarball")
    tarball_parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "discover":
        for path in discover.find_addons(args.root):
            print(path.name)
        return 0

    if args.command == "check":
        problems = check.run(args.root)
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0

    if args.command == "catalog":
        document = catalog.build(args.root, args.dist, args.base)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(document, indent=2) + "\n")
        print(f"wrote {args.out} with {len(document['addons'])} addons")
        return 0

    if args.command == "tarball":
        args.out.mkdir(parents=True, exist_ok=True)
        for addon in discover.find_addons(args.root):
            meta = json.loads((addon / "addon.json").read_text())
            archive = tarball.build(args.root, addon, args.out, meta["license"])
            print(f"built {archive.name}")
        return 0

    if args.command == "publish":
        dist, prefix = args.dist, args.prefix
        site = args.root / "site"
        template = (site / "page.template.html").read_text()
        # content-hashed asset names, so a changed asset always gets a new URL
        assets = {n: publish.hashed_name(site / n) for n in ("styles.css", "app.js")}
        objects = [(f"{prefix}assets/{h}", site / n) for n, h in assets.items()]

        def with_hashed_assets(text: str) -> str:
            for name, hashed in assets.items():
                text = text.replace(f"assets/{name}", f"assets/{hashed}")
            return text

        index_file = dist / "index.html"
        index_file.write_text(with_hashed_assets((site / "index.html").read_text()))
        objects.append((f"{prefix}index.html", index_file))
        for addon in discover.find_addons(args.root):
            slug = model.slug(addon.name)
            wrapped = page.wrap((addon / f"{slug}.html").read_text(),
                                model.display_name(addon.name), template)
            page_file = dist / f"{slug}.page.html"
            page_file.write_text(with_hashed_assets(wrapped))
            objects += [
                (f"{prefix}p/{slug}.html", page_file),
                (f"{prefix}p/{slug}.png",
                 addon / "src" / "main" / "assets" / "icon_day.png"),
                (f"{prefix}p/{slug}-night.png",
                 addon / "src" / "main" / "assets" / "icon_night.png"),
                (f"{prefix}dl/{slug}.cgp", dist / f"{slug}.cgp"),
                (f"{prefix}src/{slug}-src.tar.gz", dist / f"{slug}-src.tar.gz"),
            ]
        objects.append((f"{prefix}v1/catalog.schema.json",
                        site / "catalog.schema.json"))
        publish.publish(publish.client_from_env(), publish.bucket_from_env(),
                        objects,
                        (f"{prefix}v1/catalog.json", dist / "catalog.json"))
        print(f"published {len(objects) + 1} objects")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())

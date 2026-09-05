import argparse
import json
import sys
from pathlib import Path

from addons import catalog, check, discover


def main() -> int:
    parser = argparse.ArgumentParser(prog="addons")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("discover")
    sub.add_parser("check")

    catalog_parser = sub.add_parser("catalog")
    catalog_parser.add_argument("--dist", type=Path, required=True)
    catalog_parser.add_argument("--out", type=Path, required=True)
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
        document = catalog.build(args.root, args.dist)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(document, indent=2) + "\n")
        print(f"wrote {args.out} with {len(document['addons'])} addons")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())

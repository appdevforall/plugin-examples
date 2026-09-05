import argparse
import sys
from pathlib import Path

from addons import discover


def main() -> int:
    parser = argparse.ArgumentParser(prog="addons")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("discover")
    args = parser.parse_args()

    if args.command == "discover":
        for path in discover.find_addons(args.root):
            print(path.name)
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
#
# One-time opt-in for this repo's git hooks (see .githooks/).
#
# git honors only a single core.hooksPath, so a committed hook does nothing
# until you point git at it. Run this once after cloning:
#
#     ./scripts/setup-hooks.sh
#
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

echo "core.hooksPath is now '.githooks' for this repo."
echo "The pre-push hook will remind you to run /plugin-review when you change a plugin."

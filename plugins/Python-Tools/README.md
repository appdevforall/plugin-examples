# Python Tools

A Python + Flask plugin for Code On the Go

This plugin shows how to add languages and projects to Code On the Go. It contains a version of Python and Flask.

## What it adds

Two Python project templates:

- **Python Flask App** — a Flask web app with routes, HTML templates, static files, and error handling.
- **Python Starter** — a minimal Python project with a single entry point.

It also installs Python on-device (via Termux) the first time it is needed, and provides built-in run, install-dependencies, and test actions for Python projects.

## In-app help

Long-pressing a Python command in the editor toolbar shows its documentation, shipped with the
plugin and written into the IDE's `documentation.db` at install time. The plugin binds the
long-press on its own toolbar buttons, so no host change is needed:

- **Tier 1** - one-line summary of what the command runs.
- **Tier 2** - "See more" detail: entry-point order, dependency auto-install, timeouts.
- **Tier 3** - `src/main/assets/docs/index.html`, served offline in the IDE's help viewer.

Tooltip tags are `<plugin.id>.<action id>` (for example
`com.appdevforall.python.plugin.python.run.app`) under the category
`plugin_com.appdevforall.python.plugin`, which is what the IDE derives when it resolves a tooltip
for a plugin-contributed action.

## Disclaimer

Only light testing has been done; use at your own risk. Customer support cannot provide help with this plugin.


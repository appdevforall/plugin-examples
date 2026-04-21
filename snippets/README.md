# Snippets

A CodeOnTheGo plugin that adds user-managed code snippets to the editor. Snippets are stored per-project as JSON and contributed to the IDE's snippet registry, so typing a prefix expands it in-place with tab stops and placeholders.

## What it adds

- **Snippet contributions** — prefix-triggered expansions exposed through `SnippetExtension`. Placeholders follow the LSP convention: `$1`, `$2`, `${1:default}`, `$0` for the final caret.
- **Editor tab** — a "Snippets" tab (icon-bearing, closeable) that hosts the manager UI.
- **Side-menu entry** — *Manage Snippets* under the **tools** group, which opens (or focuses) the manager tab.

## Storage

Snippets live at `.cg/snippets.json` in the project root. On first access, if the file is missing, the plugin bootstraps it with four Java defaults (`sout`, `logi`, `trycatch`, `singleton`) so there's something to try immediately.

Schema:

```json
{
  "snippets": [
    {
      "language": "java",
      "scope": "local",
      "prefix": "sout",
      "description": "System.out.println",
      "body": ["System.out.println($1);$0"]
    }
  ]
}
```

| Field         | Meaning                                                           |
| ------------- | ----------------------------------------------------------------- |
| `language`    | Target language id (e.g. `java`, `kotlin`).                       |
| `scope`       | Where the expansion applies — `local`, `member`, etc.             |
| `prefix`      | Trigger text typed in the editor.                                 |
| `description` | Shown in the completion popup.                                    |
| `body`        | Lines of the expansion; joined with `\n`.                         |

Edit the file by hand or via the Snippets tab — both paths go through the same parser.

## How it works

- `SnippetsPlugin` implements `SnippetExtension`, `EditorTabExtension`, and `UIExtension`.
- `getSnippetContributions()` reads `.cg/snippets.json`, caches the parsed list, and invalidates the cache by comparing `lastModified()` — so edits from disk are picked up without a reload.
- The manager fragment (`SnippetManagerFragment`) writes through `SnippetsConfigParser`, then calls `refreshRegistry()` on `IdeSnippetService` so the IDE picks up changes without an editor restart.
- `PluginFragmentHelper` is used to obtain the plugin-scoped `LayoutInflater` and service registry, which is required for resource lookup from a plugin-packaged fragment.

## Building

```
./gradlew :snippets:assemblePluginDebug
```

Output is a `.cgp` plugin package in `snippets/build/outputs/`, installable from the IDE's plugin manager.

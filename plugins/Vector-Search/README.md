# Vector Search plugin for CodeOnTheGo

Semantic (meaning-based) code search. Files are chunked and embedded into
vectors; a query is embedded the same way and ranked by cosine similarity. The
plugin contributes a **"Semantic Results"** section to the project search screen
via `ProjectSearchExtension`.

> Embeddings come from the `ai-core` plugin at runtime (no compile-time
> dependency). Install **`ai-core` first** for real model embeddings. Without
> it, a lightweight **lexical** embedding fallback keeps search working at lower
> quality.

## Architecture

```
┌──────────────────────────┐
│  vector-search (this)    │  ← chunk, embed, cosine-similarity ranking, project search
└────────────┬─────────────┘
             │ SharedServices (runtime) → LlmInferenceService (embeddings)
             ▼
┌──────────────────────────┐
│  ai-core                 │  ← embedding generation (falls back to lexical if absent)
└──────────────────────────┘
```

## Features

- Semantic search over the current project via `ProjectSearchExtension`
- On-device embeddings through `ai-core`, with a lexical fallback
- Chunk-level results with file, line range, and a preview snippet
- Local SQLite embedding store; on-demand indexing per searched root

## Permissions

Declared in `plugin.permissions`:

| Permission | Why |
|---|---|
| `filesystem.read` | read project files to chunk and embed |
| `project.structure` | enumerate the project's source roots |

Indexing reads files in the current project only. Embeddings are stored in a
local database on the device. Semantic embeddings always use `ai-core`'s
**Local** on-device backend, and fall back to a lexical embedding when it is
unavailable, so chunk text never leaves the device.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK or native toolchain.

```bash
cd vector-search-plugin
../gradlew assemblePlugin          # release  -> build/plugin/vector-search-plugin.cgp
../gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../libs/`.

## Installation

1. Build and install **`ai-core` first** for quality embeddings (see
   [`../ai-core/README.md`](../ai-core/README.md)).
2. Build this plugin, install `build/plugin/vector-search-plugin.cgp` via
   CodeOnTheGo's Plugin Manager, and restart the IDE.
3. Run a query from the project search screen; look for the **Semantic
   Results** section.

## Key classes

- `VectorSearchPlugin.kt` — lifecycle, `ProjectSearchExtension`, search flow
- `EmbeddingIndexingService.kt` — file collection, embedding storage (SQLite)
- `CodeChunker.kt` — splits files into embeddable chunks
- `VectorSearchService.kt` / `VectorMath.kt` — similarity ranking

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.

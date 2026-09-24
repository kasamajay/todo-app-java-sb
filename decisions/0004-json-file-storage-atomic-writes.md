# 0004. JSON file storage with atomic writes, no database

> **In todo-app-java-sb:** The same file layout and write discipline, in `store/JsonStore.java`: an in-memory `TreeMap` behind a lock, then temp file → `FileChannel.force` → `Files.move(ATOMIC_MOVE)`. Entities are copied in and out (Go value semantics). The single-writer assumption holds because the app is one JVM process (0014). The rest of this record is kept as written for todo-app (Go).

## Context
The spec called for "JSON file storage in `data/` with atomic writes", not a database.

## Decision
`api/internal/storage/store.go` implements a generic `Store[T]` that holds the full collection as `map[string]T` in memory, guarded by a `sync.Mutex`, backed by one JSON file per entity (`users.json`, `boards.json`, `tasks.json`, `attachments.json`). Every mutation (`Put`/`Delete`) rewrites the entire collection to disk: marshal → write to a temp file in the same directory → `Sync()` → `os.Rename()` over the real path. Rename is atomic on the filesystem, so a crash mid-write either leaves the old file untouched or the new file fully intact — never a half-written JSON file. Attachment binaries are stored separately under `data/attachments/<id>`, written once at upload time the same way (temp file + rename), since they're immutable after creation.

## Alternatives considered
- **SQLite or another embedded database:** would give proper transactions and indexing, but the spec explicitly asked for JSON file storage.
- **One file per record instead of one file per collection:** avoids rewriting the whole collection on every write, but complicates listing/filtering (would need to read every file) at the scale this app expects; rejected for simplicity.

## Consequences
- Every write rewrites the whole collection, which is fine at expected todo-app scale but wouldn't scale to a large multi-tenant dataset.
- A single `sync.Mutex` per store serializes writes to that collection; combined with Go's one-goroutine-per-request model, this prevents interleaved/corrupt writes without needing OS-level file locks.
- The entire dataset must fit in memory (it's loaded fully at startup and kept in sync).

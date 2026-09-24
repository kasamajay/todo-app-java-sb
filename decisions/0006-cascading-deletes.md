# 0006. Deletes cascade instead of blocking on non-empty resources

## Context
The spec didn't say what should happen when a user deletes a board that still has tasks, or a task that still has attachments. Some APIs block such deletes (409 Conflict, "board is not empty") and require the client to delete children first.

## Decision
`BoardsHandler.Delete` walks every task on the board, deletes each task's attachments (blob + metadata) and the task itself, then deletes the board. `TasksHandler.Delete` does the equivalent one level down: delete every attachment on the task, then the task. A single DELETE call always fully removes a resource and everything under it.

## Alternatives considered
- **Block on non-empty (409 Conflict):** safer against accidental data loss, but pushes the cascade logic onto every frontend caller (list children, delete each, then delete the parent) for no real benefit in a single-user-owned todo app.
- **Soft delete / trash:** would allow recovery, but adds a whole additional feature (restore, purge) not in scope.

## Consequences
- Deleting a board is a single, convenient action from the UI (`BoardsList.jsx`'s delete button) — no need to delete tasks first.
- There's no undo: a board delete permanently removes its tasks and their attachment files. The frontend confirms with `window.confirm` before calling delete as the only safeguard.

# Tasks & Kanban board

Per-user, per-board task CRUD, surfaced as a 3-column Kanban board with native drag-and-drop.

## What it does
- Every task belongs to exactly one board (`board_id`) and one user (`user_id`); `POST /api/tasks` validates that the referenced board exists and is owned by the caller before creating the task.
- `status` is one of `todo` / `in_progress` / `done` (`models.TaskStatus`, validated server-side). New tasks default to `todo`.
- `GET /api/tasks?board_id=...` lists tasks scoped to both the caller and (optionally) a specific board; without `board_id` it returns all of the caller's tasks across every board.
- `PUT /api/tasks/{id}` handles both full field edits (from the task edit modal) and single-field status changes (from dragging a card between columns) — the request body only needs to include the fields being changed, since each field is a `*string` pointer that's only applied when non-nil.
- Deleting a task cascades to its attachments (metadata + binary blobs) before deleting the task itself.
- **Drag-and-drop** is native HTML5 DnD (`draggable`, `dataTransfer`), no library: `TaskCard.jsx` sets the task ID on `dragstart`; each column `<div>` in `Kanban.jsx` handles `onDragOver` (calls `preventDefault()` to allow dropping, plus a highlight state toggle) and `onDrop` (reads the task ID back out and calls `PUT /api/tasks/{id}` with the new status). The UI updates optimistically and rolls back if the API call fails.
- **Due dates** (`due_date`) are optional, stored as RFC3339 timestamps. An omitted `due_date` field leaves it unchanged (partial-update semantics like the other fields); an empty string clears it; anything else must parse as RFC3339 or the request fails with `400 invalid_due_date`. There's no email/push delivery for reminders (see [decisions/0007](../decisions/0007-no-email-infra-reset-token-logged.md)) — instead, `TaskCard.jsx` computes an in-app status from the due date and gives the card a colored left border + label: **overdue** (red, past due) or **due soon** (amber, due within 24h), for any task not already `done`.

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/tasks` | bearer | optional `?board_id=` filter |
| POST | `/api/tasks` | bearer | validates board ownership |
| PUT | `/api/tasks/{id}` | bearer (owner) | partial update; used for both edits and DnD status moves |
| DELETE | `/api/tasks/{id}` | bearer (owner) | 204, cascades to attachments |

## Key files
- `api/src/main/java/io/todo/api/controllers/TasksController.java`
- `api/src/main/java/io/todo/api/store/Stores.java` — `TaskStore.listByUser`, `listByBoard`, `listByBoardAny`
- `api/src/main/java/io/todo/api/model/Task.java` — `Task`, `VALID_STATUSES`
- `web/src/components/Kanban.jsx` — column layout, drag/drop orchestration, optimistic updates
- `web/src/components/TaskCard.jsx` — the draggable card
- `web/src/components/TaskForm.jsx` — create/edit modal (also embeds the attachments panel — see [attachments.md](attachments.md))

## Related
[diagrams/data-model.md](../diagrams/data-model.md) shows how `Task` relates to `Board` and `Attachment`.
[labels.md](labels.md) — color-coded labels attached to tasks, with filtering on this board.

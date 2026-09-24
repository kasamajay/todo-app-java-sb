# Labels

Managed, color-coded labels defined per board, attached to tasks for categorization and filtering.

## What it does
- A label belongs to exactly one board (`board_id`) and one user (`user_id`) — created, renamed, recolored, and deleted from the "Manage labels" screen (`web/src/components/LabelsManager.jsx`), opened from the Kanban board header.
- `color` must be one of a fixed 8-color palette (`models.LabelColors` on the backend, `theme.js`'s `labelColors` on the frontend — kept in sync manually); `POST`/`PUT` reject anything else with `400 invalid_color`.
- A task references labels by ID (`Task.label_ids`), not by embedding label data — renaming or recoloring a label updates every task that references it without needing to re-save the task. `PUT /api/tasks/{id}` treats `label_ids` with the same partial-update semantics as the task's other fields: omitted leaves it unchanged, an explicit `[]` clears all labels, and every ID must resolve to a label on that exact task's board and owned by the caller (`400 invalid_label` otherwise, with no partial apply).
- **Deleting a label** strips it from every task that references it rather than deleting those tasks — the first place this codebase needed "cascade" to mean removing a *reference*, not removing the referencing resource (see [decisions/0006](../decisions/0006-cascading-deletes.md), which established cascading deletes for the resource-deletion case this extends).
- **Deleting a board** now also deletes every label defined on it, alongside its existing task/attachment cascade.
- **Filtering** on the Kanban board (`Kanban.jsx`) is a single-select filter-chip row, purely client-side — all of a board's tasks are already loaded, so selecting a label just filters what's rendered in each column; no extra API calls.

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/labels` | bearer | `?board_id=` required |
| POST | `/api/labels` | bearer | validates board ownership + color |
| PUT | `/api/labels/{id}` | bearer (owner) | full replace: `{name, color}` |
| DELETE | `/api/labels/{id}` | bearer (owner) | 204, strips the label from any tasks that reference it |

## Key files
- `api/src/main/java/io/todo/api/controllers/LabelsController.java` — CRUD + the delete-strips-references cascade.
- `api/src/main/java/io/todo/api/controllers/TasksController.java` — `label_ids` validation in `create`/`update` (`validateLabelIds`).
- `api/src/main/java/io/todo/api/controllers/BoardsController.java` — board-delete cascade now also removes the board's labels.
- `api/src/main/java/io/todo/api/store/Stores.java` — `LabelStore.listByBoard`, `listByBoardAny`.
- `api/src/main/java/io/todo/api/model/Label.java` — `COLORS`, `isValidColor`; `Task.labelIds`.
- `web/src/components/LabelsManager.jsx` — create/rename/recolor/delete modal.
- `web/src/components/Kanban.jsx` — loads labels alongside tasks, the filter-chip row, "Manage labels" entry point.
- `web/src/components/TaskCard.jsx` — renders a task's labels as colored chips.
- `web/src/components/TaskForm.jsx` — toggleable label chips in the task edit/create modal.
- `web/src/theme.js` — `labelColors`, mirroring the backend's `LabelColors` palette.

## Related
[diagrams/data-model.md](../diagrams/data-model.md) shows `Label` and its many-to-many reference from `Task`.
[tasks-kanban.md](tasks-kanban.md) — the board the labels and their filter live on.

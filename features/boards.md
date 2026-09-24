# Boards

Per-user board CRUD. A board is a named container (with a summary and start date) that tasks belong to.

## What it does
- Every board has a `user_id` set to the creator on `POST /api/boards`; `GET /api/boards` lists only the caller's own boards (`BoardStore.ListByUser`).
- `PUT`/`DELETE /api/boards/{id}` return **404** (not 403) if the board doesn't exist or isn't owned by the caller, so a non-owner can't distinguish "doesn't exist" from "exists but isn't yours".
- Deleting a board cascades: every task on the board is deleted, and every attachment on each of those tasks (metadata + binary blob) is deleted first — see [decisions/0006](../decisions/0006-cascading-deletes.md).
- Fields: `name` (required), `summary` (optional), `start_date` (optional, `"YYYY-MM-DD"` string, not validated as a real date server-side — the frontend renders it via an `<input type="date">`).

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/boards` | bearer | scoped to caller |
| POST | `/api/boards` | bearer | 201 + board |
| PUT | `/api/boards/{id}` | bearer (owner) | 200 + board, or 404 |
| DELETE | `/api/boards/{id}` | bearer (owner) | 204, cascades to tasks/attachments |

## Key files
- `api/src/main/java/io/todo/api/controllers/BoardsController.java`
- `api/src/main/java/io/todo/api/store/Stores.java` — `BoardStore.listByUser`
- `api/src/main/java/io/todo/api/model/Board.java`
- `web/src/components/BoardsList.jsx` — grid of board cards, create/edit/delete
- `web/src/components/BoardForm.jsx` — create/edit modal

## UI flow
Boards list is the landing view after login (`App.jsx`'s `view === 'boards'`). Clicking a board card navigates to its Kanban view (`view === 'kanban'`); there's no routing library involved, just `useState` in `App.jsx` (see [decisions](../decisions/README.md) and the spec's "no React Router" requirement).

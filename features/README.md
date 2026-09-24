# Features

What the app does, one file per feature area — API routes, key files, and UI flow. For *why* things are built the way they are, see [../decisions/](../decisions/README.md); for visual diagrams, see [../diagrams/](../diagrams/README.md).

- [authentication.md](authentication.md) — register/login/logout/me/forgot-password/reset-password, account lockout, timing-safe login.
- [boards.md](boards.md) — per-user board CRUD.
- [tasks-kanban.md](tasks-kanban.md) — per-board task CRUD and the 3-column drag-and-drop Kanban UI.
- [labels.md](labels.md) — managed, color-coded labels per board, attached to tasks, with client-side filtering.
- [attachments.md](attachments.md) — per-task file upload/download/delete, 10MB limit, image previews.
- [admin-panel.md](admin-panel.md) — the `/admin` app: admin bootstrap, user listing, account unlock.

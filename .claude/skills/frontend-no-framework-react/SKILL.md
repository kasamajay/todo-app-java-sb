---
name: frontend-no-framework-react
description: >
  Conventions for web/src - React 18 + Vite 5 with inline styles only (no CSS
  files/framework), no React Router (useState view machines instead), and
  native HTML5 drag-and-drop (no DnD library). Use when adding a component,
  a view/page, or anything touching styling, navigation, or the Kanban board.
---

# Frontend: no-framework React (todo-app web/)

## Table of Contents

- [Overview](#overview)
- [When to Use](#when-to-use)
- [This project's structure](#this-projects-structure)
- [Conventions to follow](#conventions-to-follow)
- [Best Practices](#best-practices)

## Overview

`web/` is React 18 + Vite 5, but deliberately without the usual supporting cast: no CSS framework or `.css` files, no React Router, no drag-and-drop library. These are explicit project constraints, not gaps to fill in — see `../../../features/tasks-kanban.md`, `../../../features/attachments.md`, and root `README.md`.

## When to Use

- Adding or editing any component under `web/src/components/` or `web/src/admin/`.
- Adding a new "screen"/view.
- Styling anything.
- Touching the Kanban board, drag-and-drop, or attachment previews/downloads.

## This project's structure

```
web/src/main.jsx              picks App.jsx or AdminApp.jsx by window.location.pathname (only URL read)
web/src/App.jsx               useState view machine: loading -> login -> boards -> kanban
web/src/api.js                fetch wrapper: bearer token from localStorage, 401 -> onUnauthorized hook
web/src/theme.js              BRAND = '#6366f1' + shared style-object constants
web/src/components/           Login, BoardsList, BoardForm, Kanban, TaskCard, TaskForm, AttachmentUploader
web/src/components/common/    Button, Modal, TextField - small style-object-based primitives
web/src/admin/                AdminApp.jsx (own view machine), AdminLogin.jsx, UsersTable.jsx
```

## Conventions to follow

- **Inline styles only**: every component uses `style={{...}}` object literals, built from `theme.js` constants (`colors.brand`, `cardStyle`, `primaryButtonStyle`, etc.) or composed inline. No `.css` files, no `className`-based frameworks (Tailwind, etc.), no CSS-in-JS library. Reuse a `theme.js` constant or a `components/common/*` primitive before writing a new one-off style object for something that already has a pattern.
- **No React Router.** Navigation between login/boards/kanban is `useState` in `App.jsx` (and separately in `AdminApp.jsx`), driven by callback props (`onSelectBoard`, `onBack`, `onLoginSuccess`, etc.), not `<Link>`/`<Route>`. The **only** place the URL path is read is `main.jsx`'s one-time check of `window.location.pathname.startsWith('/admin')` to decide whether to mount `App` or `AdminApp` at all — don't add `window.location` reads anywhere else; add another `useState` view instead.
- **Native HTML5 drag-and-drop, no library.** `TaskCard.jsx` sets `draggable` + `onDragStart` (`e.dataTransfer.setData('text/plain', task.id)`); `Kanban.jsx`'s column `<div>`s handle `onDragOver` (must call `e.preventDefault()` to allow a drop) and `onDrop` (`e.dataTransfer.getData('text/plain')` to read the task id back out, then an optimistic `PUT /api/tasks/{id}` with rollback on failure). Follow this exact shape for any other draggable UI rather than adding `react-dnd`/`dnd-kit`/etc.
- **Auth token flows through `api.js` only**: `getToken`/`setToken`/`clearToken` wrap `localStorage`, and every request attaches `Authorization: Bearer <token>` automatically. On a 401, `api.js` clears the token and calls whatever handler was registered via `onUnauthorized` (set once per view-machine root in `App.jsx`/`AdminApp.jsx`) — don't read/write the token directly from a component, and don't hand-roll another 401 handler.
- **Attachment previews/downloads fetch authenticated blobs**, not plain `<img src>`/`<a href>` — those can't carry a bearer header. Use `api.fetchAttachmentBlobUrl(taskId, attachmentId)` (returns an object URL via `URL.createObjectURL`) and remember to `URL.revokeObjectURL` it on cleanup, exactly as `AttachmentUploader.jsx` already does.

## Best Practices

### ✅ DO
- Add new shared style values to `theme.js` rather than repeating a hex color or spacing value inline in a component.
- Keep new components' props as plain callbacks (`onX`) the way `BoardsList`/`Kanban`/`TaskForm` already do, so the parent view machine stays the single source of truth for navigation state.
- Run `docker compose run --rm web npm run build` after any nontrivial change — it's the fastest signal for a broken import/JSX error (see the `docker-dev-workflow` skill for the container-based command).

### ❌ DON'T
- Don't add `react-router-dom`, a CSS framework, or a drag-and-drop library — all three were explicitly excluded by the project spec.
- Don't add a second place that reads `window.location` for navigation — `main.jsx`'s admin-vs-main split is the only sanctioned one.
- Don't link directly to an attachment URL in an `<img>`/`<a>` tag — it will silently fail auth (no bearer header) instead of erroring loudly.

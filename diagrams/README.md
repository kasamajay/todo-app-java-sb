# Architecture Diagrams

Visual documentation of how this app actually runs, complementing the prose in [`../README.md`](../README.md), the rationale in [`../decisions/`](../decisions/README.md), and the feature reference in [`../features/`](../features/README.md). The `.md` diagrams are [Mermaid](https://mermaid.js.org/) code blocks — they render natively on GitHub and most Markdown viewers, no tooling required.

- [`system-architecture.md`](system-architecture.md) — the moving pieces (browser, `web` container, `api` container, `data/` bind mount) and how they connect.
- [`dev-vs-prod-serving.md`](dev-vs-prod-serving.md) — how the frontend reaches the browser in each mode. Dev: Vite compiles each `.jsx` on demand and pushes changes over an HMR WebSocket. Prod: a prebuilt, hashed bundle served by nginx, which also reverse-proxies `/api` to the Spring Boot API.
- [`auth-sequence.md`](auth-sequence.md) — what happens on a login request: the timing-safe PBKDF2 path, account lockout, and token minting.
- [`data-model.md`](data-model.md) — the four entity collections (User, Board, Task, Attachment), their relationships, and where they live on disk.

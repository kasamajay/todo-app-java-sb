# Skills

Project-specific skills for working on todo-app-java-sb, each a `SKILL.md` following the same format used elsewhere in this workspace (see `../../../rhel-golden-image-builder/skills-lock.json` for the fetched-skill convention this deliberately does **not** use here).

| Skill | Covers |
|---|---|
| [docker-dev-workflow](docker-dev-workflow/SKILL.md) | Running/building/testing through `docker compose` (no native JDK/Maven/Node/make), the polling-recompile + devtools hot reload and the real bugs hit with it, and the ports shared with todo-app / todo-app-py |
| [java-contract-parity-api](java-contract-parity-api/SKILL.md) | `api/` conventions — Spring Boot kept byte-compatible with the Go API: `Responses`/`ApiError`, strict `BodyDecoder`, Go-shaped models, copy-on-read storage, no Spring Security or DTO binding |
| [frontend-no-framework-react](frontend-no-framework-react/SKILL.md) | `web/` conventions — inline styles only, no React Router, native HTML5 drag-and-drop |

## Note on provenance

These three were **hand-authored** (carried over from todo-app, with the backend skill rewritten for Java) rather than pulled from an external skills marketplace — the same deliberate choice `portfolio-website` made for its own skills (see `../../decisions/README.md` in this project for this project's ADRs, and `../../../portfolio-website/decisions/0009-hand-authored-skills.md` for the sibling project's reasoning). Content is drawn from real gotchas hit and decisions made while building this app — see [`../../decisions/`](../../decisions/README.md) for the full "why" behind each one.

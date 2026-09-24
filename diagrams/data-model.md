# Data Model

Five entity collections, each persisted as its own JSON file under `data/` (see [`../decisions/0004-json-file-storage-atomic-writes.md`](../decisions/0004-json-file-storage-atomic-writes.md)). There's no foreign-key enforcement at the storage layer — ownership and referential checks (a board belongs to this user, a task's board exists and is owned by this user, etc.) happen in the handlers, not the database, since there is no database.

```mermaid
erDiagram
    USER ||--o{ BOARD : owns
    USER ||--o{ TASK : owns
    BOARD ||--o{ TASK : contains
    BOARD ||--o{ LABEL : defines
    TASK ||--o{ ATTACHMENT : has
    TASK }o--o{ LABEL : "tagged with (label_ids)"

    USER {
        string id PK
        string email
        bytes password_hash
        bytes salt
        bool is_admin
        int failed_login_count
        time locked_until
        string reset_token
        time reset_token_expires
        time created_at
    }
    BOARD {
        string id PK
        string user_id FK
        string name
        string summary
        string start_date
        time created_at
        time updated_at
    }
    TASK {
        string id PK
        string user_id FK
        string board_id FK
        string title
        string description
        string status "todo | in_progress | done"
        time due_date "optional"
        string[] label_ids "FK, many-to-many"
        time created_at
        time updated_at
    }
    LABEL {
        string id PK
        string user_id FK
        string board_id FK
        string name
        string color "one of a fixed 8-color palette"
        time created_at
    }
    ATTACHMENT {
        string id PK
        string task_id FK
        string user_id FK
        string filename
        string content_type
        int64 size_bytes
        time created_at
    }
```

Key points:

- **IDs** are 32-character random hex strings (`Secrets.newId()` in `api/src/main/java/io/todo/api/auth/Secrets.java`), not incrementing integers — generated client-independently, no coordination needed.
- **`Attachment` rows are metadata only** (`data/attachments.json`); the binary content lives as a separate file at `data/attachments/<attachment-id>`, addressed by the same ID (see [`../features/attachments.md`](../features/attachments.md)).
- **Cascade on delete** is enforced in the handlers, not the storage layer: deleting a `BOARD` deletes its `TASK`s, their `ATTACHMENT`s, and its `LABEL`s; deleting a `TASK` deletes its `ATTACHMENT`s (see [`../decisions/0006-cascading-deletes.md`](../decisions/0006-cascading-deletes.md)). Deleting a `LABEL` is the first case where "cascade" means stripping a *reference* rather than deleting the referencing resource: every `TASK.label_ids` containing that label has the ID removed, but the task itself stays (see [`../features/labels.md`](../features/labels.md)).
- `User.password_hash` / `salt` / `reset_token*` are never serialized to API responses — handlers always return `User.publicView()` (see `api/src/main/java/io/todo/api/model/User.java`).

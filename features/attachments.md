# Attachments

Upload/download/delete files on a task, 10MB max, with image previews.

## What it does
- Attachments are scoped to a task, which is itself scoped to a user — every attachment route first resolves and ownership-checks the parent task (`AttachmentsHandler.ownedTask`) before touching the attachment itself, and a 404 is returned if the task doesn't exist, isn't owned by the caller, or the attachment doesn't belong to that task.
- **Upload** (`POST /api/tasks/{id}/attachments`) is `multipart/form-data` with a `file` field. Size is enforced twice: `http.MaxBytesReader` caps the whole request body, and the multipart `FileHeader.Size` plus the actual bytes read are both checked against the 10MB limit (`maxAttachmentBytes`), returning 413 if exceeded. Content type is taken from the upload's own `Content-Type` header, falling back to `http.DetectContentType` on the bytes.
- Binary content is stored under `data/attachments/<attachment-id>` (content-addressed by ID, immutable after upload), written via the same atomic temp-file-then-rename helper used for the JSON stores. Metadata (filename, content type, size, timestamps) lives in `attachments.json` alongside the other collections.
- **Download** (`GET /api/tasks/{id}/attachments/{aid}`) streams the raw bytes with the stored `Content-Type` and a `Content-Disposition: inline` header carrying the original filename.
- **Delete** removes both the blob and the metadata entry.
- Because `<img src>` and plain `<a href>` can't carry an `Authorization` header, the frontend never links to the attachment URL directly — `AttachmentUploader.jsx` fetches the bytes with the bearer token attached and turns them into a `URL.createObjectURL` blob for image previews, and does the same (plus a synthetic click on a hidden `<a>`) to trigger downloads.

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/tasks/{id}/attachments` | bearer (owner) | multipart/form-data, 10MB cap |
| GET | `/api/tasks/{id}/attachments` | bearer (owner) | list metadata |
| GET | `/api/tasks/{id}/attachments/{aid}` | bearer (owner) | streams the file |
| DELETE | `/api/tasks/{id}/attachments/{aid}` | bearer (owner) | removes blob + metadata |

## Key files
- `api/src/main/java/io/todo/api/controllers/AttachmentsController.java`
- `api/src/main/java/io/todo/api/store/Stores.java` — `AttachmentStore.blobPath`, `writeBlob`, `readBlob`, `deleteBlob`
- `api/src/main/java/io/todo/api/model/Attachment.java`
- `web/src/components/AttachmentUploader.jsx` — upload button, thumbnail grid, image preview fetching
- `web/src/api.js` — `uploadAttachment`, `fetchAttachmentBlobUrl`, `deleteAttachment`

## UI flow
The attachments panel only appears inside the task **edit** modal (`TaskForm.jsx`), not the create modal, since an attachment needs a real task ID to upload against — a brand-new task must be saved once before attachments can be added to it.

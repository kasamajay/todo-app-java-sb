import { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'
import { colors } from '../theme.js'
import Button from './common/Button.jsx'

const MAX_BYTES = 10 * 1024 * 1024

export default function AttachmentUploader({ taskId }) {
  const [attachments, setAttachments] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState(false)
  const fileInputRef = useRef(null)

  useEffect(() => {
    refresh()
  }, [taskId])

  async function refresh() {
    setLoading(true)
    try {
      const list = await api.listAttachments(taskId)
      setAttachments(list || [])
    } catch (err) {
      setError(err.message || 'Failed to load attachments')
    } finally {
      setLoading(false)
    }
  }

  async function handleFileChange(e) {
    const file = e.target.files[0]
    e.target.value = ''
    if (!file) return

    if (file.size > MAX_BYTES) {
      setError('File exceeds the 10MB limit')
      return
    }

    setError('')
    setUploading(true)
    try {
      const created = await api.uploadAttachment(taskId, file)
      setAttachments((prev) => [created, ...prev])
    } catch (err) {
      setError(err.message || 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  async function handleDelete(attachment) {
    try {
      await api.deleteAttachment(taskId, attachment.id)
      setAttachments((prev) => prev.filter((a) => a.id !== attachment.id))
    } catch (err) {
      setError(err.message || 'Failed to delete attachment')
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '10px' }}>
        <span style={{ fontSize: '13px', fontWeight: 600, color: colors.textMuted }}>Attachments</span>
        <Button
          type="button"
          variant="secondary"
          disabled={uploading}
          onClick={() => fileInputRef.current?.click()}
        >
          {uploading ? 'Uploading…' : '+ Add file'}
        </Button>
        <input ref={fileInputRef} type="file" hidden onChange={handleFileChange} />
      </div>

      {error && <p style={{ color: colors.danger, fontSize: '12px' }}>{error}</p>}
      {loading && <p style={{ color: colors.textMuted, fontSize: '13px' }}>Loading…</p>}

      {!loading && attachments.length === 0 && (
        <p style={{ color: colors.textMuted, fontSize: '13px' }}>No attachments yet.</p>
      )}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px' }}>
        {attachments.map((attachment) => (
          <AttachmentItem key={attachment.id} taskId={taskId} attachment={attachment} onDelete={handleDelete} />
        ))}
      </div>
    </div>
  )
}

function AttachmentItem({ taskId, attachment, onDelete }) {
  const [previewUrl, setPreviewUrl] = useState(null)
  const isImage = attachment.content_type && attachment.content_type.startsWith('image/')

  useEffect(() => {
    let objectUrl = null
    let cancelled = false
    if (isImage) {
      api
        .fetchAttachmentBlobUrl(taskId, attachment.id)
        .then((url) => {
          if (cancelled) {
            URL.revokeObjectURL(url)
            return
          }
          objectUrl = url
          setPreviewUrl(url)
        })
        .catch(() => {})
    }
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [taskId, attachment.id, isImage])

  async function handleDownload() {
    const url = await api.fetchAttachmentBlobUrl(taskId, attachment.id)
    const a = document.createElement('a')
    a.href = url
    a.download = attachment.filename
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div
      style={{
        border: `1px solid ${colors.border}`,
        borderRadius: '8px',
        padding: '8px',
        width: '110px',
        fontSize: '11px',
        textAlign: 'center',
      }}
    >
      {isImage && previewUrl ? (
        <img
          src={previewUrl}
          alt={attachment.filename}
          style={{ width: '100%', height: '70px', objectFit: 'cover', borderRadius: '4px', marginBottom: '6px' }}
        />
      ) : (
        <div
          style={{
            width: '100%',
            height: '70px',
            background: colors.brandLight,
            color: colors.brand,
            borderRadius: '4px',
            marginBottom: '6px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: 700,
          }}
        >
          FILE
        </div>
      )}
      <div
        title={attachment.filename}
        style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', marginBottom: '4px' }}
      >
        {attachment.filename}
      </div>
      <div style={{ display: 'flex', justifyContent: 'center', gap: '8px' }}>
        <button style={linkBtn} onClick={handleDownload}>
          Download
        </button>
        <button style={{ ...linkBtn, color: colors.danger }} onClick={() => onDelete(attachment)}>
          Delete
        </button>
      </div>
    </div>
  )
}

const linkBtn = {
  background: 'none',
  border: 'none',
  padding: 0,
  color: colors.brand,
  cursor: 'pointer',
  fontSize: '11px',
}

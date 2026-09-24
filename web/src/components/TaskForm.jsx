import { useState } from 'react'
import Modal from './common/Modal.jsx'
import TextField from './common/TextField.jsx'
import Button from './common/Button.jsx'
import AttachmentUploader from './AttachmentUploader.jsx'
import { colors, labelStyle, inputStyle } from '../theme.js'

const STATUS_OPTIONS = [
  { value: 'todo', label: 'To do' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'done', label: 'Done' },
]

export default function TaskForm({ task, labels = [], onSave, onDelete, onClose }) {
  const [title, setTitle] = useState(task?.title || '')
  const [description, setDescription] = useState(task?.description || '')
  const [status, setStatus] = useState(task?.status || 'todo')
  const [dueDate, setDueDate] = useState(task?.due_date ? task.due_date.slice(0, 10) : '')
  const [labelIds, setLabelIds] = useState(task?.label_ids || [])
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  function toggleLabel(id) {
    setLabelIds((prev) => (prev.includes(id) ? prev.filter((lid) => lid !== id) : [...prev, id]))
  }

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')
    setSaving(true)
    try {
      const due_date = dueDate ? `${dueDate}T00:00:00Z` : ''
      await onSave({ title, description, status, due_date, label_ids: labelIds })
    } catch (err) {
      setError(err.message || 'Failed to save task')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title={task ? 'Edit task' : 'New task'} onClose={onClose} width={540}>
      <form onSubmit={handleSubmit}>
        <TextField label="Title" required value={title} onChange={(e) => setTitle(e.target.value)} />

        <div style={{ marginBottom: '14px' }}>
          <label style={labelStyle}>Description</label>
          <textarea
            style={{ ...inputStyle, minHeight: '80px', resize: 'vertical', fontFamily: 'inherit' }}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>

        <div style={{ marginBottom: '14px' }}>
          <label style={labelStyle}>Status</label>
          <select style={inputStyle} value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUS_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div style={{ marginBottom: '14px' }}>
          <label style={labelStyle}>Due date</label>
          <input
            type="date"
            style={inputStyle}
            value={dueDate}
            onChange={(e) => setDueDate(e.target.value)}
          />
        </div>

        {labels.length > 0 && (
          <div style={{ marginBottom: '14px' }}>
            <label style={labelStyle}>Labels</label>
            <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
              {labels.map((l) => {
                const active = labelIds.includes(l.id)
                return (
                  <button
                    type="button"
                    key={l.id}
                    onClick={() => toggleLabel(l.id)}
                    style={{
                      fontSize: '12px',
                      fontWeight: 600,
                      border: `1.5px solid ${l.color}`,
                      background: active ? l.color : '#ffffff',
                      color: active ? '#ffffff' : l.color,
                      borderRadius: '10px',
                      padding: '4px 10px',
                      cursor: 'pointer',
                    }}
                  >
                    {l.name}
                  </button>
                )
              })}
            </div>
          </div>
        )}

        {error && <p style={{ color: colors.danger, fontSize: '13px' }}>{error}</p>}

        <div style={{ display: 'flex', gap: '8px', justifyContent: 'space-between', marginTop: '8px' }}>
          <div>
            {task && onDelete && (
              <Button type="button" variant="danger" onClick={() => onDelete(task)}>
                Delete task
              </Button>
            )}
          </div>
          <div style={{ display: 'flex', gap: '8px' }}>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </div>
      </form>

      {task && (
        <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: `1px solid ${colors.border}` }}>
          <AttachmentUploader taskId={task.id} />
        </div>
      )}
    </Modal>
  )
}

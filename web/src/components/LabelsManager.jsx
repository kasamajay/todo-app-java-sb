import { useState } from 'react'
import { api } from '../api.js'
import { colors, labelColors, labelStyle, inputStyle } from '../theme.js'
import Modal from './common/Modal.jsx'
import Button from './common/Button.jsx'
import TextField from './common/TextField.jsx'

function ColorSwatchPicker({ value, onChange }) {
  return (
    <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', margin: '8px 0' }}>
      {labelColors.map((c) => (
        <button
          type="button"
          key={c.value}
          title={c.name}
          onClick={() => onChange(c.value)}
          style={{
            width: '22px',
            height: '22px',
            borderRadius: '50%',
            background: c.value,
            border: value === c.value ? `2px solid ${colors.text}` : '2px solid transparent',
            cursor: 'pointer',
            padding: 0,
          }}
        />
      ))}
    </div>
  )
}

export default function LabelsManager({ board, labels, onClose, onChange }) {
  const [newName, setNewName] = useState('')
  const [newColor, setNewColor] = useState(labelColors[0].value)
  const [editingId, setEditingId] = useState(null)
  const [editName, setEditName] = useState('')
  const [editColor, setEditColor] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function handleAdd(e) {
    e.preventDefault()
    if (!newName.trim()) return
    setError('')
    setSaving(true)
    try {
      await api.createLabel({ board_id: board.id, name: newName, color: newColor })
      setNewName('')
      setNewColor(labelColors[0].value)
      onChange()
    } catch (err) {
      setError(err.message || 'Failed to create label')
    } finally {
      setSaving(false)
    }
  }

  function startEdit(label) {
    setEditingId(label.id)
    setEditName(label.name)
    setEditColor(label.color)
    setError('')
  }

  function cancelEdit() {
    setEditingId(null)
  }

  async function handleSaveEdit(e) {
    e.preventDefault()
    if (!editName.trim()) return
    setError('')
    setSaving(true)
    try {
      await api.updateLabel(editingId, { name: editName, color: editColor })
      setEditingId(null)
      onChange()
    } catch (err) {
      setError(err.message || 'Failed to update label')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(label) {
    if (!confirm(`Delete label "${label.name}"? It will be removed from any tasks using it.`)) return
    setError('')
    try {
      await api.deleteLabel(label.id)
      onChange()
    } catch (err) {
      setError(err.message || 'Failed to delete label')
    }
  }

  return (
    <Modal title="Manage labels" onClose={onClose} width={440}>
      {labels.length === 0 && (
        <p style={{ fontSize: '13px', color: colors.textMuted, marginTop: 0 }}>No labels yet on this board.</p>
      )}

      {labels.map((label) =>
        editingId === label.id ? (
          <form
            key={label.id}
            onSubmit={handleSaveEdit}
            style={{ padding: '10px 0', borderBottom: `1px solid ${colors.border}` }}
          >
            <TextField
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              containerStyle={{ marginBottom: '4px' }}
            />
            <ColorSwatchPicker value={editColor} onChange={setEditColor} />
            <div style={{ display: 'flex', gap: '8px' }}>
              <Button type="submit" disabled={saving} style={{ fontSize: '13px', padding: '6px 12px' }}>
                Save
              </Button>
              <Button
                type="button"
                variant="secondary"
                onClick={cancelEdit}
                style={{ fontSize: '13px', padding: '6px 12px' }}
              >
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <div
            key={label.id}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
              padding: '8px 0',
              borderBottom: `1px solid ${colors.border}`,
            }}
          >
            <span
              style={{
                width: '14px',
                height: '14px',
                borderRadius: '50%',
                background: label.color,
                flexShrink: 0,
              }}
            />
            <span style={{ flex: 1, fontSize: '14px' }}>{label.name}</span>
            <button
              type="button"
              onClick={() => startEdit(label)}
              style={{ background: 'none', border: 'none', color: colors.brand, fontSize: '13px', fontWeight: 600, cursor: 'pointer' }}
            >
              Edit
            </button>
            <button
              type="button"
              onClick={() => handleDelete(label)}
              style={{ background: 'none', border: 'none', color: colors.danger, fontSize: '13px', fontWeight: 600, cursor: 'pointer' }}
            >
              Delete
            </button>
          </div>
        )
      )}

      <form onSubmit={handleAdd} style={{ marginTop: '16px' }}>
        <label style={labelStyle}>Add a label</label>
        <input
          style={inputStyle}
          placeholder="Label name"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
        />
        <ColorSwatchPicker value={newColor} onChange={setNewColor} />
        <Button type="submit" disabled={saving || !newName.trim()} style={{ width: '100%' }}>
          Add label
        </Button>
      </form>

      {error && <p style={{ color: colors.danger, fontSize: '13px' }}>{error}</p>}

      <Button type="button" variant="secondary" onClick={onClose} style={{ width: '100%', marginTop: '16px' }}>
        Close
      </Button>
    </Modal>
  )
}

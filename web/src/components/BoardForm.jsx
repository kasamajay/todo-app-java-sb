import { useState } from 'react'
import Modal from './common/Modal.jsx'
import TextField from './common/TextField.jsx'
import Button from './common/Button.jsx'
import { colors } from '../theme.js'

export default function BoardForm({ board, onSave, onClose }) {
  const [name, setName] = useState(board?.name || '')
  const [summary, setSummary] = useState(board?.summary || '')
  const [startDate, setStartDate] = useState(board?.start_date || '')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')
    setSaving(true)
    try {
      await onSave({ name, summary, start_date: startDate })
    } catch (err) {
      setError(err.message || 'Failed to save board')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title={board ? 'Edit board' : 'New board'} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <TextField label="Name" required value={name} onChange={(e) => setName(e.target.value)} />
        <TextField label="Summary" value={summary} onChange={(e) => setSummary(e.target.value)} />
        <TextField
          label="Start date"
          type="date"
          value={startDate}
          onChange={(e) => setStartDate(e.target.value)}
        />
        {error && <p style={{ color: colors.danger, fontSize: '13px' }}>{error}</p>}
        <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end', marginTop: '8px' }}>
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

import { useState } from 'react'
import { api } from '../api.js'
import Modal from './common/Modal.jsx'
import Button from './common/Button.jsx'
import { colors } from '../theme.js'

export default function TwoFactorSettings({ user, onClose, onUpdated }) {
  const [enabled, setEnabled] = useState(!!user.two_factor_enabled)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  async function handleToggle(next) {
    setSaving(true)
    setError('')
    try {
      const updated = await api.updateTwoFactor(next)
      setEnabled(updated.two_factor_enabled)
      if (onUpdated) onUpdated(updated)
    } catch (err) {
      setError(err.message || 'Failed to update two-factor setting')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title="Two-factor authentication" onClose={onClose} width={420}>
      <p style={{ fontSize: '13px', color: colors.textMuted, marginTop: 0 }}>
        When enabled, logging in requires a 6-digit verification code in addition to your password or Google
        sign-in. There's no email infrastructure in this app, so the code is logged server-side instead of
        emailed (<code>docker compose logs api</code>).
      </p>
      <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '14px', margin: '16px 0' }}>
        <input type="checkbox" checked={enabled} disabled={saving} onChange={(e) => handleToggle(e.target.checked)} />
        Require a verification code at login
      </label>
      {error && <p style={{ color: colors.danger, fontSize: '13px' }}>{error}</p>}
      <Button type="button" variant="secondary" onClick={onClose} style={{ width: '100%' }}>
        Close
      </Button>
    </Modal>
  )
}

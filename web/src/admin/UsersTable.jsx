import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { colors, cardStyle } from '../theme.js'
import Button from '../components/common/Button.jsx'

export default function UsersTable() {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState(null)

  useEffect(() => {
    refresh()
  }, [])

  async function refresh() {
    setLoading(true)
    try {
      const data = await api.adminListUsers()
      setUsers(data || [])
    } catch (err) {
      setError(err.message || 'Failed to load users')
    } finally {
      setLoading(false)
    }
  }

  function isLocked(user) {
    return user.locked_until && new Date(user.locked_until) > new Date()
  }

  async function handleUnlock(user) {
    setBusyId(user.id)
    try {
      const updated = await api.adminUnlockUser(user.id)
      setUsers((prev) => prev.map((u) => (u.id === updated.id ? updated : u)))
    } catch (err) {
      setError(err.message || 'Failed to unlock user')
    } finally {
      setBusyId(null)
    }
  }

  if (loading) return <p style={{ color: colors.textMuted }}>Loading…</p>
  if (error) return <p style={{ color: colors.danger }}>{error}</p>

  return (
    <div style={{ ...cardStyle, overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
        <thead>
          <tr style={{ textAlign: 'left', borderBottom: `1px solid ${colors.border}` }}>
            <th style={th}>Email</th>
            <th style={th}>Role</th>
            <th style={th}>Created</th>
            <th style={th}>Failed attempts</th>
            <th style={th}>Status</th>
            <th style={th}></th>
          </tr>
        </thead>
        <tbody>
          {users.map((user) => (
            <tr key={user.id} style={{ borderBottom: `1px solid ${colors.border}` }}>
              <td style={td}>{user.email}</td>
              <td style={td}>{user.is_admin ? 'Admin' : 'User'}</td>
              <td style={td}>{user.created_at ? new Date(user.created_at).toLocaleDateString() : ''}</td>
              <td style={td}>{user.failed_login_count}</td>
              <td style={td}>
                {isLocked(user) ? (
                  <span style={{ color: colors.danger, fontWeight: 600 }}>Locked</span>
                ) : (
                  <span style={{ color: colors.success }}>Active</span>
                )}
              </td>
              <td style={td}>
                {isLocked(user) && (
                  <Button variant="secondary" disabled={busyId === user.id} onClick={() => handleUnlock(user)}>
                    {busyId === user.id ? 'Unlocking…' : 'Unlock'}
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const th = { padding: '10px 12px', color: colors.textMuted, fontWeight: 600 }
const td = { padding: '10px 12px' }

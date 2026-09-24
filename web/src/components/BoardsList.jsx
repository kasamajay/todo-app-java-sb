import { useEffect, useState } from 'react'
import { api } from '../api.js'
import { colors, cardStyle, fontFamily, pageStyle } from '../theme.js'
import Button from './common/Button.jsx'
import BoardForm from './BoardForm.jsx'
import TwoFactorSettings from './TwoFactorSettings.jsx'

export default function BoardsList({ user, onSelectBoard, onLogout, onUserUpdated }) {
  const [boards, setBoards] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [editingBoard, setEditingBoard] = useState(null)
  const [settingsOpen, setSettingsOpen] = useState(false)

  useEffect(() => {
    refresh()
  }, [])

  async function refresh() {
    setLoading(true)
    try {
      const data = await api.listBoards()
      setBoards(data || [])
    } catch (err) {
      setError(err.message || 'Failed to load boards')
    } finally {
      setLoading(false)
    }
  }

  async function handleSave(values) {
    if (editingBoard) {
      const updated = await api.updateBoard(editingBoard.id, values)
      setBoards((prev) => prev.map((b) => (b.id === updated.id ? updated : b)))
    } else {
      const created = await api.createBoard(values)
      setBoards((prev) => [created, ...prev])
    }
    closeForm()
  }

  async function handleDelete(board) {
    if (!confirm(`Delete board "${board.name}"? This deletes all its tasks and attachments.`)) return
    try {
      await api.deleteBoard(board.id)
      setBoards((prev) => prev.filter((b) => b.id !== board.id))
    } catch (err) {
      alert(err.message || 'Failed to delete board')
    }
  }

  function openCreate() {
    setEditingBoard(null)
    setFormOpen(true)
  }

  function openEdit(board, e) {
    e.stopPropagation()
    setEditingBoard(board)
    setFormOpen(true)
  }

  function closeForm() {
    setFormOpen(false)
    setEditingBoard(null)
  }

  return (
    <div style={{ ...pageStyle, padding: '24px' }}>
      <Header user={user} onLogout={onLogout} onOpenSettings={() => setSettingsOpen(true)} />

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '24px 0 16px' }}>
        <h2 style={{ margin: 0, fontSize: '20px' }}>Your boards</h2>
        <Button onClick={openCreate}>+ New board</Button>
      </div>

      {loading && <p style={{ color: colors.textMuted }}>Loading…</p>}
      {error && <p style={{ color: colors.danger }}>{error}</p>}

      {!loading && boards.length === 0 && (
        <div style={{ ...cardStyle, padding: '32px', textAlign: 'center', color: colors.textMuted }}>
          No boards yet. Create your first one to get started.
        </div>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
          gap: '16px',
        }}
      >
        {boards.map((board) => (
          <div
            key={board.id}
            onClick={() => onSelectBoard(board)}
            style={{
              ...cardStyle,
              padding: '18px',
              cursor: 'pointer',
              borderTop: `3px solid ${colors.brand}`,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <h3 style={{ margin: '0 0 6px', fontSize: '16px' }}>{board.name}</h3>
            </div>
            {board.summary && (
              <p style={{ margin: '0 0 10px', fontSize: '13px', color: colors.textMuted }}>{board.summary}</p>
            )}
            {board.start_date && (
              <p style={{ margin: '0 0 12px', fontSize: '12px', color: colors.textMuted }}>
                Starts {board.start_date}
              </p>
            )}
            <div style={{ display: 'flex', gap: '8px' }}>
              <button style={linkBtn} onClick={(e) => openEdit(board, e)}>
                Edit
              </button>
              <button
                style={{ ...linkBtn, color: colors.danger }}
                onClick={(e) => {
                  e.stopPropagation()
                  handleDelete(board)
                }}
              >
                Delete
              </button>
            </div>
          </div>
        ))}
      </div>

      {formOpen && <BoardForm board={editingBoard} onSave={handleSave} onClose={closeForm} />}

      {settingsOpen && (
        <TwoFactorSettings
          user={user}
          onClose={() => setSettingsOpen(false)}
          onUpdated={(updated) => onUserUpdated && onUserUpdated(updated)}
        />
      )}
    </div>
  )
}

function Header({ user, onLogout, onOpenSettings }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <h1 style={{ margin: 0, fontSize: '22px', color: colors.brand, fontFamily }}>Todo App</h1>
      <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
        {user && <span style={{ fontSize: '13px', color: colors.textMuted }}>{user.email}</span>}
        <button style={linkBtn} onClick={onOpenSettings}>
          Two-factor authentication
        </button>
        <button style={linkBtn} onClick={onLogout}>
          Log out
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
  fontSize: '13px',
  fontWeight: 600,
}

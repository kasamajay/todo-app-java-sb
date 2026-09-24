import { useEffect, useState } from 'react'
import { api, getToken, clearToken, onUnauthorized } from '../api.js'
import { colors, fontFamily, pageStyle } from '../theme.js'
import AdminLogin from './AdminLogin.jsx'
import UsersTable from './UsersTable.jsx'

export default function AdminApp() {
  const [view, setView] = useState('loading') // loading | login | users
  const [user, setUser] = useState(null)

  useEffect(() => {
    onUnauthorized(() => {
      setUser(null)
      setView('login')
    })

    if (!getToken()) {
      setView('login')
      return
    }
    api
      .me()
      .then((u) => {
        if (!u.is_admin) {
          clearToken()
          setView('login')
          return
        }
        setUser(u)
        setView('users')
      })
      .catch(() => {
        clearToken()
        setView('login')
      })
  }, [])

  function handleLoginSuccess(loggedInUser) {
    setUser(loggedInUser)
    setView('users')
  }

  async function handleLogout() {
    try {
      await api.logout()
    } catch {
      // ignore
    }
    clearToken()
    setUser(null)
    setView('login')
  }

  if (view === 'loading') {
    return (
      <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily, color: colors.textMuted }}>
        Loading…
      </div>
    )
  }

  if (view === 'login') {
    return <AdminLogin onSuccess={handleLoginSuccess} />
  }

  return (
    <div style={{ ...pageStyle, padding: '24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '22px', color: colors.brand }}>Todo App Admin</h1>
          {user && <span style={{ fontSize: '13px', color: colors.textMuted }}>{user.email}</span>}
        </div>
        <button
          onClick={handleLogout}
          style={{ background: 'none', border: 'none', padding: 0, color: colors.brand, cursor: 'pointer', fontSize: '13px', fontWeight: 600 }}
        >
          Log out
        </button>
      </div>

      <h2 style={{ fontSize: '18px', marginBottom: '12px' }}>Users</h2>
      <UsersTable />
    </div>
  )
}

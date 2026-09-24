import { useEffect, useState } from 'react'
import { api, getToken, setToken, clearToken, onUnauthorized } from './api.js'
import { colors, fontFamily } from './theme.js'
import Login from './components/Login.jsx'
import BoardsList from './components/BoardsList.jsx'
import Kanban from './components/Kanban.jsx'

const GOOGLE_ERROR_MESSAGES = {
  google_oauth_not_configured: 'Google sign-in is not configured on this server.',
  google_denied: 'Google sign-in was cancelled.',
  google_state_missing: 'Google sign-in session expired. Please try again.',
  google_state_mismatch: 'Google sign-in session expired. Please try again.',
  google_missing_code: 'Google sign-in did not complete. Please try again.',
  google_exchange_failed: 'Could not reach Google. Please try again.',
  google_userinfo_failed: 'Could not reach Google. Please try again.',
  google_email_unverified: 'Your Google account email must be verified to sign in.',
  google_account_locked: 'This account is locked. Try again later.',
  google_internal_error: 'Something went wrong signing in with Google. Please try again.',
}

export default function App() {
  const [view, setView] = useState('loading') // loading | login | boards | kanban
  const [user, setUser] = useState(null)
  const [activeBoard, setActiveBoard] = useState(null)
  const [googleError, setGoogleError] = useState('')
  const [pendingGoogleTwoFactorUserId, setPendingGoogleTwoFactorUserId] = useState('')

  useEffect(() => {
    onUnauthorized(() => {
      setUser(null)
      setActiveBoard(null)
      setView('login')
    })

    if (window.location.hash) {
      const params = new URLSearchParams(window.location.hash.slice(1))
      const googleToken = params.get('google_token')
      const errorCode = params.get('google_error')
      const google2faUserId = params.get('google_2fa_required')
      if (googleToken) setToken(googleToken)
      if (errorCode) setGoogleError(GOOGLE_ERROR_MESSAGES[errorCode] || 'Google sign-in failed. Please try again.')
      if (google2faUserId) setPendingGoogleTwoFactorUserId(google2faUserId)
      if (googleToken || errorCode || google2faUserId) {
        window.history.replaceState(null, '', window.location.pathname + window.location.search)
      }
    }

    if (!getToken()) {
      setView('login')
      return
    }
    api
      .me()
      .then((u) => {
        setUser(u)
        setView('boards')
      })
      .catch(() => {
        clearToken()
        setView('login')
      })
  }, [])

  function handleLoginSuccess(loggedInUser) {
    setUser(loggedInUser)
    setView('boards')
  }

  function handleSelectBoard(board) {
    setActiveBoard(board)
    setView('kanban')
  }

  function handleBackToBoards() {
    setActiveBoard(null)
    setView('boards')
  }

  async function handleLogout() {
    try {
      await api.logout()
    } catch {
      // ignore - we're clearing the token client-side regardless
    }
    clearToken()
    setUser(null)
    setActiveBoard(null)
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
    return (
      <Login
        onSuccess={handleLoginSuccess}
        banner={googleError}
        initialTwoFactorUserId={pendingGoogleTwoFactorUserId}
      />
    )
  }

  if (view === 'boards') {
    return (
      <BoardsList user={user} onSelectBoard={handleSelectBoard} onLogout={handleLogout} onUserUpdated={setUser} />
    )
  }

  if (view === 'kanban' && activeBoard) {
    return <Kanban board={activeBoard} onBack={handleBackToBoards} onLogout={handleLogout} />
  }

  return null
}

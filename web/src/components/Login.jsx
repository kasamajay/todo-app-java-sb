import { useState } from 'react'
import { api, setToken } from '../api.js'
import { colors, cardStyle, fontFamily } from '../theme.js'
import Button from './common/Button.jsx'
import TextField from './common/TextField.jsx'

const MODES = {
  login: { title: 'Log in', submitLabel: 'Log in' },
  register: { title: 'Create account', submitLabel: 'Create account' },
  forgot: { title: 'Reset password', submitLabel: 'Send reset link' },
  reset: { title: 'Set new password', submitLabel: 'Set new password' },
  twoFactor: { title: 'Enter verification code', submitLabel: 'Verify' },
}

export default function Login({
  onSuccess,
  defaultEmail = '',
  allowRegister = true,
  allowForgot = true,
  allowGoogle = true,
  banner = '',
  initialTwoFactorUserId = '',
}) {
  const [mode, setMode] = useState(initialTwoFactorUserId ? 'twoFactor' : 'login')
  const [email, setEmail] = useState(defaultEmail)
  const [password, setPassword] = useState('')
  const [resetToken, setResetToken] = useState('')
  const [code, setCode] = useState('')
  const [twoFactorUserId, setTwoFactorUserId] = useState(initialTwoFactorUserId)
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e) {
    e.preventDefault()
    setError('')
    setInfo('')
    setLoading(true)
    try {
      if (mode === 'login') {
        const res = await api.login(email, password)
        if (res.two_factor_required) {
          setTwoFactorUserId(res.user_id)
          setMode('twoFactor')
          setPassword('')
        } else {
          setToken(res.token)
          onSuccess(res.user)
        }
      } else if (mode === 'twoFactor') {
        const res = await api.verifyTwoFactor(twoFactorUserId, code)
        setToken(res.token)
        onSuccess(res.user)
      } else if (mode === 'register') {
        const res = await api.register(email, password)
        setToken(res.token)
        onSuccess(res.user)
      } else if (mode === 'forgot') {
        await api.forgotPassword(email)
        setInfo('If that email exists, a reset link has been sent.')
      } else if (mode === 'reset') {
        await api.resetPassword(resetToken, password)
        setInfo('Password reset. You can log in now.')
        setMode('login')
        setPassword('')
      }
    } catch (err) {
      setError(err.message || 'Something went wrong')
    } finally {
      setLoading(false)
    }
  }

  const { title, submitLabel } = MODES[mode]

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily,
        background: colors.bg,
        padding: '16px',
      }}
    >
      <div style={{ ...cardStyle, width: '100%', maxWidth: '380px', padding: '32px' }}>
        <h1 style={{ margin: '0 0 4px', fontSize: '22px', color: colors.brand }}>Todo App</h1>
        <p style={{ margin: '0 0 24px', color: colors.textMuted, fontSize: '14px' }}>{title}</p>

        {banner && <p style={{ color: colors.danger, fontSize: '13px', marginTop: 0 }}>{banner}</p>}

        <form onSubmit={handleSubmit}>
          {mode !== 'reset' && mode !== 'twoFactor' && (
            <TextField
              label="Email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
            />
          )}

          {mode === 'reset' && (
            <TextField
              label="Reset token"
              required
              value={resetToken}
              onChange={(e) => setResetToken(e.target.value)}
            />
          )}

          {mode !== 'forgot' && mode !== 'twoFactor' && (
            <TextField
              label={mode === 'reset' ? 'New password' : 'Password'}
              type="password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
            />
          )}

          {mode === 'twoFactor' && (
            <TextField
              label="Verification code"
              required
              inputMode="numeric"
              maxLength={6}
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          )}

          {error && (
            <p style={{ color: colors.danger, fontSize: '13px', marginTop: 0 }}>{error}</p>
          )}
          {info && (
            <p style={{ color: colors.success, fontSize: '13px', marginTop: 0 }}>{info}</p>
          )}

          <Button type="submit" disabled={loading} style={{ width: '100%', marginTop: '8px' }}>
            {loading ? 'Please wait…' : submitLabel}
          </Button>
        </form>

        {mode === 'login' && allowGoogle && (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', margin: '18px 0' }}>
              <div style={{ flex: 1, height: '1px', background: colors.border }} />
              <span style={{ fontSize: '12px', color: colors.textMuted }}>or</span>
              <div style={{ flex: 1, height: '1px', background: colors.border }} />
            </div>
            <Button
              type="button"
              variant="secondary"
              style={{ width: '100%' }}
              onClick={() => { window.location.href = '/api/auth/google/login' }}
            >
              Sign in with Google
            </Button>
          </>
        )}

        <div style={{ marginTop: '18px', fontSize: '13px', color: colors.textMuted, display: 'flex', flexDirection: 'column', gap: '6px' }}>
          {mode === 'login' && (
            <>
              {allowRegister && (
                <LinkButton onClick={() => { setMode('register'); setError(''); setInfo('') }}>
                  Need an account? Register
                </LinkButton>
              )}
              {allowForgot && (
                <LinkButton onClick={() => { setMode('forgot'); setError(''); setInfo('') }}>
                  Forgot password?
                </LinkButton>
              )}
            </>
          )}
          {mode !== 'login' && (
            <LinkButton
              onClick={() => {
                setMode('login')
                setError('')
                setInfo('')
                setCode('')
                setTwoFactorUserId('')
              }}
            >
              Back to log in
            </LinkButton>
          )}
          {mode === 'forgot' && (
            <LinkButton onClick={() => { setMode('reset'); setError(''); setInfo('') }}>
              Already have a reset token?
            </LinkButton>
          )}
        </div>
      </div>
    </div>
  )
}

function LinkButton({ onClick, children }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        background: 'none',
        border: 'none',
        padding: 0,
        color: colors.brand,
        cursor: 'pointer',
        fontSize: '13px',
        textAlign: 'left',
      }}
    >
      {children}
    </button>
  )
}

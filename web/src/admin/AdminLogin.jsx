import { useState } from 'react'
import Login from '../components/Login.jsx'
import { clearToken } from '../api.js'
import { colors } from '../theme.js'

export default function AdminLogin({ onSuccess }) {
  const [notAdminError, setNotAdminError] = useState('')

  function handleSuccess(user) {
    if (!user.is_admin) {
      clearToken()
      setNotAdminError('That account does not have admin access.')
      return
    }
    setNotAdminError('')
    onSuccess(user)
  }

  return (
    <div>
      <Login onSuccess={handleSuccess} defaultEmail="admin@todo.io" allowRegister={false} allowForgot={true} allowGoogle={false} />
      {notAdminError && (
        <p style={{ position: 'fixed', bottom: '24px', left: 0, right: 0, textAlign: 'center', color: colors.danger, fontSize: '13px' }}>
          {notAdminError}
        </p>
      )}
    </div>
  )
}

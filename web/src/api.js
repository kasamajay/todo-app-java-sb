const TOKEN_KEY = 'todo_app_token'

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch {
    // ignore storage failures (private browsing, etc.)
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    // ignore
  }
}

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

// Called whenever a request comes back 401, so the app can bounce to login.
// App.jsx / AdminApp.jsx register their own handler on mount.
let unauthorizedHandler = null
export function onUnauthorized(handler) {
  unauthorizedHandler = handler
}

async function request(method, path, body, opts = {}) {
  const headers = { ...(opts.headers || {}) }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let fetchBody = body
  if (body !== undefined && !(body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
    fetchBody = JSON.stringify(body)
  }

  const res = await fetch(path, { method, headers, body: fetchBody })

  if (res.status === 401) {
    clearToken()
    if (unauthorizedHandler) unauthorizedHandler()
  }

  if (res.status === 204) return null

  const contentType = res.headers.get('Content-Type') || ''
  const data = contentType.includes('application/json') ? await res.json() : null

  if (!res.ok) {
    const err = data && data.error
    throw new ApiError(res.status, err && err.code, (err && err.message) || `request failed (${res.status})`)
  }

  return data
}

export const api = {
  register: (email, password) => request('POST', '/api/auth/register', { email, password }),
  login: (email, password) => request('POST', '/api/auth/login', { email, password }),
  logout: () => request('POST', '/api/auth/logout'),
  me: () => request('GET', '/api/auth/me'),
  forgotPassword: (email) => request('POST', '/api/auth/forgot-password', { email }),
  resetPassword: (token, newPassword) =>
    request('POST', '/api/auth/reset-password', { token, new_password: newPassword }),
  verifyTwoFactor: (userId, code) => request('POST', '/api/auth/2fa/verify', { user_id: userId, code }),
  updateTwoFactor: (enabled) => request('PUT', '/api/auth/2fa', { enabled }),

  listBoards: () => request('GET', '/api/boards'),
  createBoard: (board) => request('POST', '/api/boards', board),
  updateBoard: (id, board) => request('PUT', `/api/boards/${id}`, board),
  deleteBoard: (id) => request('DELETE', `/api/boards/${id}`),

  listTasks: (boardId) => request('GET', `/api/tasks?board_id=${encodeURIComponent(boardId)}`),
  createTask: (task) => request('POST', '/api/tasks', task),
  updateTask: (id, task) => request('PUT', `/api/tasks/${id}`, task),
  deleteTask: (id) => request('DELETE', `/api/tasks/${id}`),

  listLabels: (boardId) => request('GET', `/api/labels?board_id=${encodeURIComponent(boardId)}`),
  createLabel: (label) => request('POST', '/api/labels', label),
  updateLabel: (id, label) => request('PUT', `/api/labels/${id}`, label),
  deleteLabel: (id) => request('DELETE', `/api/labels/${id}`),

  listAttachments: (taskId) => request('GET', `/api/tasks/${taskId}/attachments`),
  uploadAttachment: (taskId, file) => {
    const form = new FormData()
    form.append('file', file)
    return request('POST', `/api/tasks/${taskId}/attachments`, form)
  },
  deleteAttachment: (taskId, attachmentId) =>
    request('DELETE', `/api/tasks/${taskId}/attachments/${attachmentId}`),

  // <img src> / plain <a href> can't carry an Authorization header, so
  // previews/downloads fetch the bytes as an authenticated blob and hand
  // back an object URL for the caller to use (and eventually revoke).
  fetchAttachmentBlobUrl: async (taskId, attachmentId) => {
    const token = getToken()
    const res = await fetch(`/api/tasks/${taskId}/attachments/${attachmentId}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw new ApiError(res.status, null, 'failed to fetch attachment')
    const blob = await res.blob()
    return URL.createObjectURL(blob)
  },

  adminListUsers: () => request('GET', '/api/admin/users'),
  adminUnlockUser: (id) => request('POST', `/api/admin/users/${id}/unlock`),
}

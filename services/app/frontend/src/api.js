const API_BASE = '/api'
const AUTH_BASE = '/auth'
const TOKEN_KEY = 'jwt'

function getJwtToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

function storeJwtToken(token) {
  if (token) {
    sessionStorage.setItem(TOKEN_KEY, token)
  } else {
    sessionStorage.removeItem(TOKEN_KEY)
  }
}

function clearJwtToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

function redirectToLogin(reason) {
  clearJwtToken()
  const params = new URLSearchParams()
  if (reason) {
    params.set('message', reason)
  }
  const target = `/login${params.toString() ? `?${params.toString()}` : ''}`
  if (window.location.pathname !== '/login') {
    window.location.href = target
    return
  }
  window.location.replace(target)
}

function authHeaders() {
  const token = getJwtToken()
  if (!token) {
    return {}
  }
  return { Authorization: `Bearer ${token}` }
}

function handleUnauthorized() {
  redirectToLogin('Session expired; please log in again.')
}

async function readPayload(response) {
  if (response && typeof response.text === 'function') {
    const text = await response.text().catch(() => '')
    if (!text) {
      return { payload: {}, raw: '' }
    }
    try {
      return { payload: JSON.parse(text), raw: text }
    } catch (err) {
      return { payload: {}, raw: text }
    }
  }
  if (response && typeof response.json === 'function') {
    const payload = await response.json().catch(() => ({}))
    return { payload, raw: '' }
  }
  return { payload: {}, raw: '' }
}

function extractErrorDetail(response, payload, raw) {
  const detail = payload.detail || payload.message
  if (detail) {
    return detail
  }
  const trimmed = (raw || '').trim()
  if (trimmed && !trimmed.toLowerCase().startsWith('<!doctype') && !trimmed.toLowerCase().startsWith('<html')) {
    return trimmed
  }
  const status = response.status
  const statusText = response.statusText || 'Request failed'
  return `${statusText} (HTTP ${status})`
}

async function handleResponse(response) {
  if (response.status === 401) {
    handleUnauthorized()
    throw new Error('Session expired')
  }
  if (response.status === 204) {
    return null
  }
  const { payload, raw } = await readPayload(response)
  if (!response.ok) {
    throw new Error(extractErrorDetail(response, payload, raw))
  }
  return payload
}

export async function apiRequest(path, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...authHeaders(),
    ...(options.headers || {})
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  })
  return handleResponse(response)
}

export async function authRequest(path, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {})
  }

  const response = await fetch(`${AUTH_BASE}${path}`, {
    ...options,
    headers
  })
  if (response.status === 204) {
    return null
  }
  const { payload, raw } = await readPayload(response)
  if (!response.ok) {
    throw new Error(extractErrorDetail(response, payload, raw))
  }
  return payload
}

export async function apiUpload(path, formData, options = {}) {
  const headers = {
    ...authHeaders(),
    ...(options.headers || {})
  }

  const response = await fetch(`${API_BASE}${path}`, {
    method: options.method || 'POST',
    body: formData,
    headers
  })
  return handleResponse(response)
}

export async function apiDownload(path) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: authHeaders()
  })
  if (response.status === 401) {
    handleUnauthorized()
    throw new Error('Session expired')
  }
  if (!response.ok) {
    const { payload, raw } = await readPayload(response)
    throw new Error(extractErrorDetail(response, payload, raw))
  }
  return response
}

export { getJwtToken, storeJwtToken, clearJwtToken }

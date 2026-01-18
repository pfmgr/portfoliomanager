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

async function handleResponse(response) {
  if (response.status === 401) {
    handleUnauthorized()
    throw new Error('Session expired')
  }
  if (response.status === 204) {
    return null
  }
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const detail = payload.detail || payload.message || 'Request failed'
    throw new Error(detail)
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
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const detail = payload.detail || payload.message || 'Request failed'
    throw new Error(detail)
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
    const payload = await response.json().catch(() => ({}))
    const detail = payload.detail || payload.message || 'Request failed'
    throw new Error(detail)
  }
  return response
}

export { getJwtToken, storeJwtToken, clearJwtToken }

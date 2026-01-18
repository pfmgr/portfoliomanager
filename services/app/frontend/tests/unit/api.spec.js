import { describe, it, expect, beforeEach, vi } from 'vitest'
import { apiRequest, authRequest, apiUpload, apiDownload } from '../../src/api'

const makeResponse = (status, payload) => ({
  status,
  ok: status >= 200 && status < 300,
  json: async () => payload
})

describe('api helpers', () => {
  beforeEach(() => {
    sessionStorage.clear()
    global.fetch = vi.fn()
  })

  it('apiRequest includes auth header and parses json', async () => {
    sessionStorage.setItem('jwt', 'token')
    global.fetch.mockResolvedValue(makeResponse(200, { ok: true }))

    const result = await apiRequest('/rulesets', { method: 'GET' })

    expect(result.ok).toBe(true)
    expect(global.fetch).toHaveBeenCalledWith('/api/rulesets', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer token' })
    }))
  })

  it('authRequest throws error message from payload', async () => {
    global.fetch.mockResolvedValue(makeResponse(401, { message: 'nope' }))

    await expect(authRequest('/token', { method: 'POST' }))
      .rejects.toThrow('nope')
  })

  it('apiUpload returns null on 204', async () => {
    global.fetch.mockResolvedValue(makeResponse(204, {}))
    const result = await apiUpload('/import', new FormData())
    expect(result).toBeNull()
  })

  it('apiDownload throws on error', async () => {
    global.fetch.mockResolvedValue(makeResponse(400, { detail: 'bad' }))

    await expect(apiDownload('/rulesets')).rejects.toThrow('bad')
  })
})

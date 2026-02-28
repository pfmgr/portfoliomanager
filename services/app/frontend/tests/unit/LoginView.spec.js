import { mount } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import LoginView from '../../src/views/LoginView.vue'
import { authRequest } from '../../src/api'

const push = vi.fn()
const resolve = vi.fn()
const routeQuery = {}

vi.mock('vue-router', () => ({
  useRouter: () => ({ push, resolve }),
  useRoute: () => ({ query: routeQuery })
}))

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    authRequest: vi.fn()
  }
})

describe('LoginView', () => {
  beforeEach(() => {
    sessionStorage.clear()
    push.mockClear()
    resolve.mockReset()
    resolve.mockImplementation((path) => ({ path, matched: [{}] }))
    Object.keys(routeQuery).forEach((key) => {
      delete routeQuery[key]
    })
    authRequest.mockReset()
  })

  it('stores token and navigates on success', async () => {
    vi.useFakeTimers()
    authRequest.mockResolvedValue({ token: 'jwt-token' })
    const wrapper = mount(LoginView)

    await wrapper.find('input').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit.prevent')

    await vi.runAllTimersAsync()

    expect(sessionStorage.getItem('jwt')).toBe('jwt-token')
    expect(push).toHaveBeenCalledWith('/start')
    vi.useRealTimers()
  })

  it('shows error message on failure', async () => {
    authRequest.mockRejectedValue(new Error('bad credentials'))
    const wrapper = mount(LoginView)

    await wrapper.find('input').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('wrong')
    await wrapper.find('form').trigger('submit.prevent')

    await Promise.resolve()

    expect(wrapper.text()).toContain('bad credentials')
    expect(sessionStorage.getItem('jwt')).toBeNull()
  })

  it('falls back to /start for unsafe returnTo values', async () => {
    vi.useFakeTimers()
    routeQuery.returnTo = 'https://evil.example'
    authRequest.mockResolvedValue({ token: 'jwt-token' })
    const wrapper = mount(LoginView)

    await wrapper.find('input').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit.prevent')

    await vi.runAllTimersAsync()

    expect(push).toHaveBeenCalledWith('/start')
    vi.useRealTimers()
  })

  it('falls back to /start when returnTo does not resolve to a route', async () => {
    vi.useFakeTimers()
    routeQuery.returnTo = '/unknown-path'
    resolve.mockReturnValue({ path: '/unknown-path', matched: [] })
    authRequest.mockResolvedValue({ token: 'jwt-token' })
    const wrapper = mount(LoginView)

    await wrapper.find('input').setValue('admin')
    await wrapper.find('input[type="password"]').setValue('secret')
    await wrapper.find('form').trigger('submit.prevent')

    await vi.runAllTimersAsync()

    expect(push).toHaveBeenCalledWith('/start')
    vi.useRealTimers()
  })
})

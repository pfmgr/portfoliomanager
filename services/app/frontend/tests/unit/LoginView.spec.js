import { mount } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import LoginView from '../../src/views/LoginView.vue'
import { authRequest } from '../../src/api'

const push = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: {} })
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
    expect(push).toHaveBeenCalledWith('/rulesets')
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
})

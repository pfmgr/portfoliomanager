import { flushPromises, mount } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import App from '../../src/App.vue'
import { checkBackendHealth } from '../../src/api'

const push = vi.fn()
let currentPath = '/rulesets'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ path: currentPath })
}))

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    checkBackendHealth: vi.fn()
  }
})

describe('App', () => {
  beforeEach(() => {
    sessionStorage.clear()
    push.mockClear()
    currentPath = '/rulesets'
    checkBackendHealth.mockResolvedValue({ ok: true })
  })

  it('shows logout on non-login routes', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })
    await flushPromises()

    const button = wrapper.find('button.ghost')
    expect(button.exists()).toBe(true)
    expect(button.text()).toBe('Logout')
  })

  it('routes to login when no token', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })

    await flushPromises()

    await wrapper.find('button.ghost').trigger('click')

    expect(push).toHaveBeenCalledWith('/login')
  })

  it('hides nav and disables login action on login route', async () => {
    currentPath = '/login'
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })

    await flushPromises()

    expect(wrapper.find('nav.nav').exists()).toBe(false)
    const button = wrapper.find('button.ghost')
    expect(button.text()).toBe('Login')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('clears token on logout', async () => {
    sessionStorage.setItem('jwt', 'token')
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })

    await flushPromises()

    const button = wrapper.find('button.ghost')
    await button.trigger('click')

    expect(sessionStorage.getItem('jwt')).toBeNull()
    expect(push).toHaveBeenCalledWith('/login')
  })

  it('shows startup screen when backend is unavailable', async () => {
    checkBackendHealth.mockResolvedValueOnce({ ok: false, status: 503, error: 'timeout' })
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })

    await flushPromises()

    expect(wrapper.find('.startup-panel').exists()).toBe(true)
    expect(wrapper.text()).toContain('Backend is starting up...')
    expect(wrapper.findComponent({ name: 'RouterView' }).exists()).toBe(false)

    wrapper.unmount()
  })
})

import { mount } from '@vue/test-utils'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import App from '../../src/App.vue'

const push = vi.fn()
let currentPath = '/rulesets'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ path: currentPath })
}))

describe('App', () => {
  beforeEach(() => {
    sessionStorage.clear()
    push.mockClear()
    currentPath = '/rulesets'
  })

  it('shows logout on non-login routes', () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })

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

    await wrapper.find('button.ghost').trigger('click')

    expect(push).toHaveBeenCalledWith('/login')
  })

  it('hides nav and disables login action on login route', () => {
    currentPath = '/login'
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterLink: true,
          RouterView: true
        }
      }
    })

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

    const button = wrapper.find('button.ghost')
    await button.trigger('click')

    expect(sessionStorage.getItem('jwt')).toBeNull()
    expect(push).toHaveBeenCalledWith('/login')
  })
})

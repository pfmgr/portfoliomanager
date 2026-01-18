import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import KnowledgeBaseView from '../../src/views/KnowledgeBaseView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('KnowledgeBaseView', () => {
  it('hydrates persisted sort state from storage', async () => {
    const store = new Map()
    const storage = {
      getItem: vi.fn((key) => (store.has(key) ? store.get(key) : null)),
      setItem: vi.fn((key, value) => {
        store.set(key, value)
      }),
      removeItem: vi.fn((key) => {
        store.delete(key)
      }),
      clear: vi.fn(() => {
        store.clear()
      })
    }
    const originalLocalStorage = window.localStorage
    Object.defineProperty(window, 'localStorage', {
      value: storage,
      configurable: true
    })
    window.__ENABLE_TEST_STORAGE__ = true

    let wrapper
    try {
      storage.setItem(
        'kb.sortState.v1',
        JSON.stringify({
          dossier: { key: 'isin', direction: 'asc' }
        })
      )

      apiRequest.mockImplementation((path) => {
        if (path.startsWith('/kb/config')) {
          return Promise.resolve({ enabled: true })
        }
        if (path.startsWith('/kb/dossiers')) {
          return Promise.resolve({ items: [], total: 0 })
        }
        if (path.startsWith('/kb/runs')) {
          return Promise.resolve({ items: [], total: 0 })
        }
        if (path.startsWith('/kb/llm-actions')) {
          return Promise.resolve([])
        }
        return Promise.resolve({})
      })

      wrapper = mount(KnowledgeBaseView)
      await flushPromises()

      const isinSortButton = wrapper.find('table.kb-dossier-table th[aria-sort="ascending"] button.sort-button')
      expect(isinSortButton.exists()).toBe(true)
      expect(isinSortButton.text()).toContain('ISIN')
    } finally {
      if (wrapper) {
        wrapper.unmount()
      }
      delete window.__ENABLE_TEST_STORAGE__
      Object.defineProperty(window, 'localStorage', {
        value: originalLocalStorage,
        configurable: true
      })
    }
  })

  it('renders empty state when no instruments exist', async () => {
    apiRequest.mockImplementation((path) => {
      if (path.startsWith('/kb/config')) {
        return Promise.resolve({ enabled: true })
      }
      if (path.startsWith('/kb/dossiers')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/runs')) {
        return Promise.resolve({ items: [], total: 0 })
      }
      if (path.startsWith('/kb/llm-actions')) {
        return Promise.resolve([])
      }
      return Promise.resolve({})
    })

    const wrapper = mount(KnowledgeBaseView)
    await flushPromises()

    expect(wrapper.text()).toContain('Knowledge Base')
    expect(wrapper.text()).toContain('No dossiers found.')
    wrapper.unmount()
  })
})

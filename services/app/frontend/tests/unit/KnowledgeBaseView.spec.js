import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import KnowledgeBaseView from '../../src/views/KnowledgeBaseView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('KnowledgeBaseView', () => {
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

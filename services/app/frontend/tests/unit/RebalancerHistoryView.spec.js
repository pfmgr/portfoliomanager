import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import RebalancerHistoryView from '../../src/views/RebalancerHistoryView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('RebalancerHistoryView', () => {
  it('renders saved runs and narrative', async () => {
    apiRequest.mockResolvedValueOnce([
      {
        runId: 7,
        createdAt: '2024-01-01T10:00:00Z',
        asOfDate: '2024-01-01',
        depotScope: ['tr']
      }
    ])
    apiRequest.mockResolvedValueOnce({
      runId: 7,
      createdAt: '2024-01-01T10:00:00Z',
      asOfDate: '2024-01-01',
      depotScope: ['tr'],
      narrativeMd: 'LLM narrative.',
      summary: {}
    })

    const wrapper = mount(RebalancerHistoryView)
    await flushPromises()

    expect(wrapper.text()).toContain('Rebalancer History')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('LLM narrative.')
  })
})

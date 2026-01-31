import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import AssessorView from '../../src/views/AssessorView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('AssessorView', () => {
  it('shows gap detection policy options for saving plan', async () => {
    apiRequest.mockResolvedValue({
      activeProfileKey: 'BALANCED',
      profiles: {
        BALANCED: {
          displayName: 'Balanced',
          layerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0 },
          acceptableVariancePct: 3.0,
          minimumSavingPlanSize: 15,
          minimumRebalancingAmount: 10
        }
      },
      layerNames: { 1: 'Global Core', 2: 'Core-Plus', 3: 'Themes', 4: 'Individual Stocks', 5: 'Unclassified' },
      effectiveLayerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0 },
      acceptableVariancePct: 3.0,
      minimumSavingPlanSize: 15,
      minimumRebalancingAmount: 10,
      customOverridesEnabled: false
    })

    const wrapper = mount(AssessorView)
    await flushPromises()

    expect(wrapper.text()).toContain('Gap Detection Policy')
    expect(wrapper.text()).toContain('Saving Plan Gaps (default)')
    expect(wrapper.text()).toContain('Portfolio Gaps')
  })

  it('shows instrument assessment option', async () => {
    apiRequest.mockResolvedValue({
      activeProfileKey: 'BALANCED',
      profiles: {},
      layerNames: {},
      effectiveLayerTargets: {},
      acceptableVariancePct: 3.0,
      minimumSavingPlanSize: 15,
      minimumRebalancingAmount: 10,
      customOverridesEnabled: false
    })

    const wrapper = mount(AssessorView)
    await flushPromises()

    expect(wrapper.text()).toContain('Instrument One-Time Invest')
  })
})

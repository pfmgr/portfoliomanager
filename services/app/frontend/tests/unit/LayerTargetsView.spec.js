import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import LayerTargetsView from '../../src/views/LayerTargetsView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('LayerTargetsView', () => {
  it('loads and displays layer targets', async () => {
    apiRequest.mockResolvedValueOnce({
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

    const wrapper = mount(LayerTargetsView)
    await flushPromises()

    expect(wrapper.text()).toContain('Layer Targets')
    expect(wrapper.text()).toContain('Acceptable Variance')
    expect(wrapper.text()).toContain('Minimum Saving Plan Size')
    expect(wrapper.text()).toContain('Minimum Rebalancing Amount')
    expect(wrapper.text()).toContain('Profile defaults')
  })

  it('shows custom overrides when enabled', async () => {
    apiRequest.mockResolvedValueOnce({
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
      customOverridesEnabled: true,
      customLayerTargets: { 1: 0.4, 2: 0.3, 3: 0.2, 4: 0.1, 5: 0 }
    })

    const wrapper = mount(LayerTargetsView)
    await flushPromises()

    expect(wrapper.text()).toContain('Custom overrides are active.')
    const customInputs = wrapper.findAll('tbody input.input.compact')
    expect(customInputs[0].element.disabled).toBe(false)
  })
})

import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ProfileConfigurationView from '../../src/views/ProfileConfigurationView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

const layerTargetsResponse = {
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
}

const qualityGatesResponse = {
  quality_gate_profiles: {
    active_profile: 'BALANCED',
    profiles: {
      BALANCED: {
        display_name: 'Balanced',
        layer_profiles: { 1: 'FUND', 2: 'FUND', 3: 'FUND', 4: 'EQUITY', 5: 'UNKNOWN' },
        evidence_profiles: { FUND: ['price'], EQUITY: ['price'], REIT: ['price'], UNKNOWN: ['price'] }
      }
    }
  }
}

function mockApi() {
  apiRequest.mockImplementation((path, options = {}) => {
    if (path === '/layer-targets') return Promise.resolve(layerTargetsResponse)
    if (path === '/kb/config') return Promise.resolve(qualityGatesResponse)
    return Promise.resolve({})
  })
}

describe('ProfileConfigurationView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
  })

  it('loads allocation tab', async () => {
    mockApi()
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    expect(wrapper.text()).toContain('Profile Configuration')
    expect(wrapper.text()).toContain('Allocation & limits')
  })
})

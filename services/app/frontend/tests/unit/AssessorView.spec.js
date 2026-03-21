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

  it('renders blacklist discard suggestions in saving plan results', async () => {
    apiRequest.mockImplementation((url) => {
      if (url === '/layer-targets') {
        return Promise.resolve({
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
      }
      if (url === '/assessor/run') {
        return Promise.resolve({ job_id: 'job-1', status: 'PENDING' })
      }
      if (url === '/assessor/run/job-1') {
        return Promise.resolve({
          status: 'DONE',
          result: {
            current_monthly_total: 100,
            current_layer_distribution: { 1: 100 },
            target_layer_distribution: { 1: 100 },
            saving_plan_suggestions: [
              {
                type: 'discard',
                isin: 'AAA111',
                instrument_name: 'Core ETF',
                layer: 1,
                depot_id: 1,
                depot_name: 'Trade Republic',
                old_amount: 80,
                new_amount: 0,
                delta: -80,
                rationale: 'Blacklisted from Saving Plan Proposals'
              }
            ],
            saving_plan_new_instruments: [],
            diagnostics: { kb_enabled: true, kb_complete: true, missing_kb_isins: [] }
          }
        })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(AssessorView)
    await flushPromises()

    await wrapper.find('button.primary').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Discard')
    expect(wrapper.text()).toContain('AAA111')
    expect(wrapper.text()).toContain('Blacklisted from Saving Plan Proposals')
  })
})

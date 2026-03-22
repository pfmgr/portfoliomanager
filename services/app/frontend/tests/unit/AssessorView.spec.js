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

  it('saves apply decisions for new saving plan proposals with depot and layer', async () => {
    apiRequest.mockImplementation((url, options = {}) => {
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
        return Promise.resolve({ job_id: 'job-2', status: 'PENDING' })
      }
      if (url === '/assessor/run/job-2') {
        return Promise.resolve({
          status: 'DONE',
          result: {
            current_monthly_total: 100,
            current_layer_distribution: { 3: 100 },
            target_layer_distribution: { 3: 100 },
            saving_plan_suggestions: [],
            saving_plan_new_instruments: [
              {
                isin: 'NEW123456789',
                instrument_name: 'Theme ETF',
                layer: 3,
                amount: 35,
                action: 'new',
                rationale: 'Gap detection'
              }
            ],
            diagnostics: { kb_enabled: true, kb_complete: true, missing_kb_isins: [] }
          }
        })
      }
      if (url === '/depots') {
        return Promise.resolve([
          { depotId: 1, depotCode: 'tr', name: 'Trade Republic' }
        ])
      }
      if (url === '/sparplans/apply-approvals') {
        expect(options.method).toBe('POST')
        const body = JSON.parse(options.body)
        expect(body.source).toBe('assessor')
        expect(body.items).toHaveLength(1)
        expect(body.items[0]).toMatchObject({
          decision: 'APPLY',
          depotId: 1,
          isin: 'NEW123456789',
          layer: 3,
          targetAmountEur: 35
        })
        return Promise.resolve({ applied: 1, ignored: 0, blacklistedSavingPlanOnly: 0, blacklistedAllProposals: 0, created: 1, updated: 0, deactivated: 0 })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(AssessorView)
    await flushPromises()

    await wrapper.find('button.primary').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Apply Approvals')
    expect(wrapper.text()).toContain('does not execute real depot transactions')

    const applyButton = wrapper.findAll('button').find((button) => button.text() === 'Apply Approvals')
    await applyButton.trigger('click')
    await flushPromises()

    await wrapper.find('select[aria-label="Decision for NEW123456789"]').setValue('APPLY')
    await wrapper.find('select[id^="approval-depot-"]').setValue('1')

    const submitButton = wrapper.findAll('button').find((button) => button.text() === 'Save decisions')
    await submitButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Saved decisions: 1 applied')
  })

  it('saves blacklist decisions without requiring a depot', async () => {
    apiRequest.mockImplementation((url, options = {}) => {
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
        return Promise.resolve({ job_id: 'job-3', status: 'PENDING' })
      }
      if (url === '/assessor/run/job-3') {
        return Promise.resolve({
          status: 'DONE',
          result: {
            current_monthly_total: 100,
            current_layer_distribution: { 3: 100 },
            target_layer_distribution: { 3: 100 },
            saving_plan_suggestions: [],
            saving_plan_new_instruments: [
              {
                isin: 'NEWBLACKLIST1',
                instrument_name: 'Theme ETF',
                layer: 3,
                amount: 35,
                action: 'new',
                rationale: 'Gap detection'
              }
            ],
            diagnostics: { kb_enabled: true, kb_complete: true, missing_kb_isins: [] }
          }
        })
      }
      if (url === '/depots') {
        return Promise.resolve([{ depotId: 1, depotCode: 'tr', name: 'Trade Republic' }])
      }
      if (url === '/sparplans/apply-approvals') {
        const body = JSON.parse(options.body)
        expect(body.items[0]).toMatchObject({
          decision: 'BLACKLIST_ALL_PROPOSALS',
          depotId: null,
          isin: 'NEWBLACKLIST1'
        })
        return Promise.resolve({ applied: 0, ignored: 0, blacklistedSavingPlanOnly: 0, blacklistedAllProposals: 1, created: 0, updated: 0, deactivated: 0 })
      }
      return Promise.resolve({})
    })

    const wrapper = mount(AssessorView)
    await flushPromises()
    await wrapper.find('button.primary').trigger('click')
    await flushPromises()
    await flushPromises()

    const applyButton = wrapper.findAll('button').find((button) => button.text() === 'Apply Approvals')
    await applyButton.trigger('click')
    await flushPromises()

    await wrapper.find('select[aria-label="Decision for NEWBLACKLIST1"]').setValue('BLACKLIST_ALL_PROPOSALS')

    const submitButton = wrapper.findAll('button').find((button) => button.text() === 'Save decisions')
    await submitButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('all-buy blacklist')
  })
})

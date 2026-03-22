import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import RebalancerView from '../../src/views/RebalancerView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('RebalancerView', () => {
  it('renders saving plan rebalancing when available', async () => {
    const summary = {
      layerAllocations: [],
      assetClassAllocations: [],
      topPositions: [],
      savingPlanSummary: {
        totalActiveAmountEur: 100,
        monthlyTotalAmountEur: 80,
        activeCount: 2,
        monthlyCount: 1,
        monthlyByLayer: [
          { layer: 1, amountEur: 50, weightPct: 62.5, count: 1 },
          { layer: 2, amountEur: 30, weightPct: 37.5, count: 0 }
        ]
      },
      savingPlanTargets: [
        { layer: 1, targetWeightPct: 60 },
        { layer: 2, targetWeightPct: 40 }
      ],
      savingPlanProposal: {
        totalMonthlyAmountEur: 80,
        targetWeightTotalPct: 100,
        source: 'llm',
        narrative: [
          '**Summary**',
          'Layer 1 sits slightly above target; rebalancing focuses on instrument adjustments.',
          '',
          '**Instrument highlights**',
          '- Stock ETF +5 EUR (KB-weighted: lower TER and lower overlap).',
          '- Bond ETF -5 EUR to reduce redundancy within the layer.',
          '',
          '**KB weighting**',
          'Weights consider TER and overlap in benchmark/regions/holdings where available.'
        ].join('\n'),
        notes: ['Existing savings plan distribution is within tolerance.'],
        actualDistributionByLayer: { 1: 62.5, 2: 37.5, 3: 0, 4: 0, 5: 0 },
        targetDistributionByLayer: { 1: 60, 2: 40, 3: 0, 4: 0, 5: 0 },
        proposedDistributionByLayer: { 1: 62.5, 2: 37.5, 3: 0, 4: 0, 5: 0 },
        deviationsByLayer: { 1: 2.5, 2: 2.5, 3: 0, 4: 0, 5: 0 },
        withinTolerance: true,
        constraints: [],
        recommendation: 'No change needed',
        selectedProfileKey: 'BALANCED',
        selectedProfileDisplayName: 'Balanced',
        gating: {
          knowledgeBaseEnabled: true,
          kbComplete: true,
          missingIsins: []
        },
        instrumentWarnings: ['Layer 2 has a budget of 10 EUR but no active saving plan instruments.'],
        instrumentWarningCodes: ['LAYER_NO_INSTRUMENTS'],
        instrumentProposals: [
          {
            isin: 'DE000S',
            instrumentName: 'Stock ETF',
            layer: 1,
            currentAmountEur: 50,
            proposedAmountEur: 55,
            deltaEur: 5,
            reasonCodes: ['KB_WEIGHTED']
          },
          {
            isin: 'DE000B',
            instrumentName: 'Bond ETF',
            layer: 1,
            currentAmountEur: 30,
            proposedAmountEur: 25,
            deltaEur: -5,
            reasonCodes: ['KB_WEIGHTED']
          }
        ],
        layers: [
          {
            layerName: 'Global Core',
            layer: 1,
            currentAmountEur: 50,
            currentWeightPct: 62.5,
            targetWeightPct: 60,
            targetAmountEur: 48,
            deltaEur: -2,
            currentTargetTotalWeightPct: 62.3,
            currentTargetTotalAmountEur: 62300,
            targetTotalWeightPct: 60,
            targetTotalAmountEur: 60000
          }
        ]
      }
    }

    apiRequest.mockImplementation((url) => {
      if (url === '/layer-targets') {
        return Promise.resolve({
          layerNames: {
            1: 'Global Core',
            2: 'Core-Plus',
            3: 'Themes',
            4: 'Individual Stocks',
            5: 'Unclassified'
          }
        })
      }
      if (url === '/rebalancer/run') {
        return Promise.resolve({ job_id: 'job-1', status: 'PENDING' })
      }
      if (url === '/rebalancer/run/job-1') {
        return Promise.resolve({
          job_id: 'job-1',
          status: 'DONE',
          result: { summary }
        })
      }
      return Promise.reject(new Error(`Unexpected request: ${url}`))
    })

    const wrapper = mount(RebalancerView)
    await flushPromises()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Layer Allocations')
    expect(text).toContain('Savings plan Rebalancing')
    expect(text).toContain('Monthly by Layer')
    expect(text).toContain('Rebalancing Proposal (Savings plan amounts, EUR)')
    expect(text).toContain('Current Target %')
    expect(text).toContain('Target Total (Rebalanced) %')
    expect(text).toContain('Current Target Total Amount €')
    expect(text).toContain('Target Total Amount (Rebalanced) €')
    expect(text).toContain('62.30')
    expect(text).toContain('62300.00')
    expect(text).toContain('60000.00')
    expect(text).toContain('Instrument Proposal')
    expect(text).toContain('Instrument proposal warnings')
    expect(text).toContain('KB')
    expect(text).toContain('KB status')
    expect(text).toContain('Stock ETF')
    expect(text).toContain('Group')
    expect(text).toContain('Sort')
    expect(text).toContain('Proposal source')
    expect(text).toContain('Instrument highlights')
    const narrativeHtml = wrapper.find('.narrative').html()
    expect(narrativeHtml).toContain('<ul>')
    expect(narrativeHtml).toContain('<strong>Instrument highlights</strong>')
    expect(narrativeHtml).toContain('KB weighting')
    expect(text.toLowerCase()).toContain('valuation')
  })

  it('shows discard action for blacklisted saving plans', async () => {
    const summary = {
      layerAllocations: [],
      assetClassAllocations: [],
      topPositions: [],
      savingPlanSummary: { totalActiveAmountEur: 25, monthlyTotalAmountEur: 25, activeCount: 1, monthlyCount: 1, monthlyByLayer: [] },
      savingPlanTargets: [],
      savingPlanProposal: {
        totalMonthlyAmountEur: 25,
        targetWeightTotalPct: 100,
        source: 'targets',
        narrative: 'Discard blacklisted saving plan.',
        notes: [],
        actualDistributionByLayer: { 5: 100 },
        targetDistributionByLayer: { 5: 100 },
        proposedDistributionByLayer: { 5: 0 },
        deviationsByLayer: { 5: 0 },
        withinTolerance: false,
        constraints: [],
        recommendation: 'Discard blacklisted plan',
        selectedProfileKey: 'BALANCED',
        selectedProfileDisplayName: 'Balanced',
        gating: { knowledgeBaseEnabled: true, kbComplete: true, missingIsins: [] },
        instrumentWarnings: [],
        instrumentWarningCodes: [],
        instrumentProposals: [
          {
            isin: 'DE000C',
            instrumentName: 'Test Stock',
            layer: 5,
            currentAmountEur: 25,
            proposedAmountEur: 0,
            deltaEur: -25,
            reasonCodes: ['BLACKLISTED_FROM_SAVING_PLAN_PROPOSALS']
          }
        ],
        layers: []
      }
    }

    apiRequest.mockImplementation((url) => {
      if (url === '/layer-targets') {
        return Promise.resolve({ layerNames: { 5: 'Unclassified' } })
      }
      if (url === '/rebalancer/run') {
        return Promise.resolve({ job_id: 'job-2', status: 'PENDING' })
      }
      if (url === '/rebalancer/run/job-2') {
        return Promise.resolve({ job_id: 'job-2', status: 'DONE', result: { summary } })
      }
      return Promise.reject(new Error(`Unexpected request: ${url}`))
    })

    const wrapper = mount(RebalancerView)
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Discard')
    expect(wrapper.text()).toContain('Blacklisted from Saving Plan Proposals')
  })

  it('applies selected new saving plan proposals with proposal layer', async () => {
    const summary = {
      layerAllocations: [],
      assetClassAllocations: [],
      topPositions: [],
      savingPlanSummary: { totalActiveAmountEur: 0, monthlyTotalAmountEur: 0, activeCount: 0, monthlyCount: 0, monthlyByLayer: [] },
      savingPlanTargets: [],
      savingPlanProposal: {
        totalMonthlyAmountEur: 35,
        targetWeightTotalPct: 100,
        source: 'targets',
        narrative: 'Add a new theme ETF.',
        notes: [],
        actualDistributionByLayer: { 3: 0 },
        targetDistributionByLayer: { 3: 100 },
        proposedDistributionByLayer: { 3: 100 },
        deviationsByLayer: { 3: 0 },
        withinTolerance: false,
        constraints: [],
        recommendation: 'Create a new saving plan',
        selectedProfileKey: 'BALANCED',
        selectedProfileDisplayName: 'Balanced',
        gating: { knowledgeBaseEnabled: true, kbComplete: true, missingIsins: [] },
        instrumentWarnings: [],
        instrumentWarningCodes: [],
        instrumentProposals: [
          {
            isin: 'NEWREBAL1234',
            instrumentName: 'Theme Builder ETF',
            layer: 4,
            currentAmountEur: 0,
            proposedAmountEur: 35,
            deltaEur: 35,
            reasonCodes: ['KB_GAP_SUGGESTION']
          }
        ],
        layers: []
      }
    }

    apiRequest.mockImplementation((url, options = {}) => {
      if (url === '/layer-targets') {
        return Promise.resolve({
          layerNames: { 4: 'Individual Stocks' }
        })
      }
      if (url === '/rebalancer/run') {
        return Promise.resolve({ job_id: 'job-3', status: 'PENDING' })
      }
      if (url === '/rebalancer/run/job-3') {
        return Promise.resolve({ job_id: 'job-3', status: 'DONE', result: { summary } })
      }
      if (url === '/depots') {
        return Promise.resolve([{ depotId: 2, depotCode: 'sc', name: 'Scalable Capital' }])
      }
      if (url === '/sparplans/apply-approvals') {
        const body = JSON.parse(options.body)
        expect(body.source).toBe('rebalancer')
        expect(body.items[0]).toMatchObject({
          depotId: 2,
          isin: 'NEWREBAL1234',
          layer: 4,
          targetAmountEur: 35
        })
        return Promise.resolve({ applied: 1, created: 1, updated: 0, deactivated: 0 })
      }
      return Promise.reject(new Error(`Unexpected request: ${url}`))
    })

    const wrapper = mount(RebalancerView)
    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('Apply Approvals')
    expect(wrapper.text()).toContain('does not execute real depot transactions')

    const applyButton = wrapper.findAll('button').find((button) => button.text() === 'Apply Approvals')
    await applyButton.trigger('click')
    await flushPromises()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await wrapper.find('.approval-panel__select').setValue('2')

    const submitButton = wrapper.findAll('button').find((button) => button.text() === 'Apply selected proposals')
    await submitButton.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Applied 1 proposal(s)')
  })
})

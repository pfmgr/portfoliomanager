import { test, expect } from '@playwright/test'

const seedToken = async (page) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('jwt', 'test-token')
  })
}

test('rebalancer applies a new saving plan proposal with depot selection', async ({ page }) => {
  let applyPayload = null
  await seedToken(page)

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/layer-targets') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ layerNames: { 4: 'Individual Stocks' } })
      })
    }
    if (url.pathname === '/api/rebalancer/run' && route.request().method() === 'POST') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'job-3', status: 'PENDING' })
      })
    }
    if (url.pathname === '/api/rebalancer/run/job-3') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'DONE',
          result: {
            summary: {
              layerAllocations: [],
              assetClassAllocations: [],
              topPositions: [],
              savingPlanSummary: { totalActiveAmountEur: 0, monthlyTotalAmountEur: 0, activeCount: 0, monthlyCount: 0, monthlyByLayer: [] },
              savingPlanTargets: [],
              savingPlanProposal: {
                totalMonthlyAmountEur: 35,
                targetWeightTotalPct: 100,
                source: 'targets',
                narrative: 'Create a new plan',
                notes: [],
                actualDistributionByLayer: { 4: 0 },
                targetDistributionByLayer: { 4: 100 },
                proposedDistributionByLayer: { 4: 100 },
                deviationsByLayer: { 4: 0 },
                withinTolerance: false,
                constraints: [],
                recommendation: 'Create a new plan',
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
          }
        })
      })
    }
    if (url.pathname === '/api/depots') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ depotId: 2, depotCode: 'sc', name: 'Scalable Capital', provider: 'SC' }])
      })
    }
    if (url.pathname === '/api/sparplans/apply-approvals' && route.request().method() === 'POST') {
      applyPayload = JSON.parse(route.request().postData() || '{}')
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ applied: 1, created: 1, updated: 0, deactivated: 0 })
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/rebalancer')
  await expect(page.getByText('Theme Builder ETF')).toBeVisible()
  await page.getByRole('button', { name: 'Apply Approvals' }).click()
  await page.getByLabel('Select proposal for NEWREBAL1234').check()
  await page.getByLabel('Choose depot').selectOption('2')
  await page.getByRole('button', { name: 'Apply selected proposals' }).click()

  await expect(page.getByText('Applied 1 proposal(s)')).toBeVisible()
  expect(applyPayload.items[0].layer).toBe(4)
  expect(applyPayload.items[0].depotId).toBe(2)
})

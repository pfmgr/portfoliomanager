import { test, expect } from '@playwright/test'

const seedToken = async (page) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('jwt', 'test-token')
  })
}

const stubApi = async (page) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/layer-targets') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
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
      })
    }
    if (url.pathname === '/api/assessor/run' && route.request().method() === 'POST') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'job-1', status: 'PENDING' })
      })
    }
    if (url.pathname === '/api/assessor/run/job-1') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
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
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })
}

test('assessor shows instrument assessment inputs', async ({ page }) => {
  await seedToken(page)
  await stubApi(page)

  await page.goto('/assessor')
  await page.getByLabel('Assessment type').selectOption('instrument_one_time')
  await expect(page.getByLabel('Instruments (ISINs)')).toBeVisible()
})

test('assessor shows blacklist discard suggestion', async ({ page }) => {
  await seedToken(page)
  await stubApi(page)

  await page.goto('/assessor')
  await page.getByRole('button', { name: 'Run Assessment' }).click()

  await expect(page.getByText('AAA111')).toBeVisible()
  await expect(page.getByText('Discard')).toBeVisible()
  await expect(page.getByText('Blacklisted from Saving Plan Proposals')).toBeVisible()
})

test('assessor applies a new saving plan proposal with depot selection', async ({ page }) => {
  let applyPayload = null
  await seedToken(page)
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/layer-targets') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          activeProfileKey: 'BALANCED',
          profiles: {
            BALANCED: {
              displayName: 'Balanced',
              layerTargets: { 3: 1 },
              acceptableVariancePct: 3.0,
              minimumSavingPlanSize: 15,
              minimumRebalancingAmount: 10
            }
          },
          layerNames: { 3: 'Themes' },
          effectiveLayerTargets: { 3: 1 },
          acceptableVariancePct: 3.0,
          minimumSavingPlanSize: 15,
          minimumRebalancingAmount: 10,
          customOverridesEnabled: false
        })
      })
    }
    if (url.pathname === '/api/assessor/run' && route.request().method() === 'POST') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'job-2', status: 'PENDING' })
      })
    }
    if (url.pathname === '/api/assessor/run/job-2') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'DONE',
          result: {
            current_monthly_total: 0,
            current_layer_distribution: { 3: 0 },
            target_layer_distribution: { 3: 35 },
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
      })
    }
    if (url.pathname === '/api/depots') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ depotId: 1, depotCode: 'tr', name: 'Trade Republic', provider: 'TR' }])
      })
    }
    if (url.pathname === '/api/sparplans/apply-approvals' && route.request().method() === 'POST') {
      applyPayload = JSON.parse(route.request().postData() || '{}')
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ applied: 1, ignored: 0, blacklistedSavingPlanOnly: 0, blacklistedAllProposals: 0, created: 1, updated: 0, deactivated: 0 })
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/assessor')
  await page.getByRole('button', { name: 'Run Assessment' }).click()
  await page.getByRole('button', { name: 'Apply Approvals' }).click()
  await page.getByLabel('Decision for NEW123456789').selectOption('APPLY')
  await page.getByLabel('Choose depot').selectOption('1')
  await page.getByRole('button', { name: 'Save decisions' }).click()

  await expect(page.getByText('Saved decisions: 1 applied')).toBeVisible()
  expect(applyPayload.items[0].decision).toBe('APPLY')
  expect(applyPayload.items[0].layer).toBe(3)
  expect(applyPayload.items[0].depotId).toBe(1)
})

test('assessor blacklists a proposal without depot selection', async ({ page }) => {
  let applyPayload = null
  await seedToken(page)
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/layer-targets') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          activeProfileKey: 'BALANCED',
          profiles: { BALANCED: { displayName: 'Balanced', layerTargets: { 3: 1 }, acceptableVariancePct: 3.0, minimumSavingPlanSize: 15, minimumRebalancingAmount: 10 } },
          layerNames: { 3: 'Themes' },
          effectiveLayerTargets: { 3: 1 },
          acceptableVariancePct: 3.0,
          minimumSavingPlanSize: 15,
          minimumRebalancingAmount: 10,
          customOverridesEnabled: false
        })
      })
    }
    if (url.pathname === '/api/assessor/run' && route.request().method() === 'POST') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ job_id: 'job-4', status: 'PENDING' }) })
    }
    if (url.pathname === '/api/assessor/run/job-4') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'DONE',
          result: {
            current_monthly_total: 0,
            current_layer_distribution: { 3: 0 },
            target_layer_distribution: { 3: 35 },
            saving_plan_suggestions: [],
            saving_plan_new_instruments: [
              { isin: 'NEWBLACKLIST2', instrument_name: 'Theme ETF', layer: 3, amount: 35, action: 'new', rationale: 'Gap detection' }
            ],
            diagnostics: { kb_enabled: true, kb_complete: true, missing_kb_isins: [] }
          }
        })
      })
    }
    if (url.pathname === '/api/depots') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ depotId: 1, depotCode: 'tr', name: 'Trade Republic', provider: 'TR' }]) })
    }
    if (url.pathname === '/api/sparplans/apply-approvals' && route.request().method() === 'POST') {
      applyPayload = JSON.parse(route.request().postData() || '{}')
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ applied: 0, ignored: 0, blacklistedSavingPlanOnly: 1, blacklistedAllProposals: 0, created: 0, updated: 0, deactivated: 0 })
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/assessor')
  await page.getByRole('button', { name: 'Run Assessment' }).click()
  await page.getByRole('button', { name: 'Apply Approvals' }).click()
  await page.getByLabel('Decision for NEWBLACKLIST2').selectOption('BLACKLIST_SAVING_PLAN_ONLY')
  await page.getByRole('button', { name: 'Save decisions' }).click()

  await expect(page.getByText('saving-plan blacklist')).toBeVisible()
  expect(applyPayload.items[0].decision).toBe('BLACKLIST_SAVING_PLAN_ONLY')
  expect(applyPayload.items[0].depotId).toBe(null)
})

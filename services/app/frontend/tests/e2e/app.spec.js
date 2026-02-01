import { test, expect } from '@playwright/test'

const seedToken = async (page) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('jwt', 'test-token')
  })
}

const stubApi = async (page) => {
  let pendingSaveRun = false
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const summaryBody = {
      layerAllocations: [{ label: '1', valueEur: 100, weightPct: 50 }],
      assetClassAllocations: [{ label: 'Equity', valueEur: 100, weightPct: 50 }],
      topPositions: [{ isin: 'DE0000000001', name: 'Alpha', valueEur: 100, weightPct: 50 }],
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
        narrative: 'Current savings plan distribution matches the Balanced profile within tolerance.',
        notes: ['Existing savings plan distribution is within tolerance (<= 3.0%) and does not need adjustment.'],
        layers: [
          {
            layer: 1,
            layerName: 'Global Core',
            currentAmountEur: 50,
            currentWeightPct: 62.5,
            targetWeightPct: 60,
            targetAmountEur: 48,
            deltaEur: -2
          }
        ],
        actualDistributionByLayer: { 1: 62.5, 2: 37.5, 3: 0, 4: 0, 5: 0 },
        targetDistributionByLayer: { 1: 60, 2: 40, 3: 0, 4: 0, 5: 0 },
        proposedDistributionByLayer: { 1: 62.5, 2: 37.5, 3: 0, 4: 0, 5: 0 },
        deviationsByLayer: { 1: 2.5, 2: 2.5, 3: 0, 4: 0, 5: 0 },
        withinTolerance: true,
        constraints: [],
        recommendation: 'No change needed; distribution is within tolerance.',
        selectedProfileKey: 'BALANCED',
        selectedProfileDisplayName: 'Balanced'
      }
    }

    if (path === '/api/rulesets') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ name: 'default', version: 1, active: true, updatedAt: '2024-01-01T00:00:00Z' }])
      })
    }
    if (path === '/api/rulesets/default') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          name: 'default',
          contentJson: '{"schema_version":1,"name":"default","rules":[]}',
          active: true
        })
      })
    }
    if (path.endsWith('/validate')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ valid: true, errors: [] })
      })
    }
    if (path.endsWith('/apply')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      })
    }
    if (path.startsWith('/api/rebalancer/reclassifications')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{
          isin: 'DE0000000001',
          name: 'Alpha',
          current: { instrumentType: 'ETF', assetClass: 'Equity', subClass: 'Global', layer: 2 },
          proposed: { instrumentType: 'ETF', assetClass: 'Equity', subClass: 'Global', layer: 1 },
          policyAdjusted: { instrumentType: 'ETF', assetClass: 'Equity', subClass: 'Global', layer: 2 },
          confidence: 0.8,
          impact: { valueEur: 100 }
        }])
      })
    }
    if (path === '/api/rebalancer/run' && route.request().method() === 'POST') {
      let payload = {}
      try {
        payload = route.request().postDataJSON() ?? {}
      } catch (err) {
        payload = {}
      }
      pendingSaveRun = Boolean(payload.saveRun)
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'job-1', status: 'PENDING' })
      })
    }
    if (path.startsWith('/api/rebalancer/run/')) {
      const result = { summary: summaryBody }
      if (pendingSaveRun) {
        result.saved_run = {
          runId: 1,
          createdAt: '2024-01-01T10:00:00Z',
          asOfDate: '2024-01-01',
          depotScope: ['tr'],
          narrativeMd: 'LLM narrative.',
          summary: summaryBody
        }
        pendingSaveRun = false
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ job_id: 'job-1', status: 'DONE', result })
      })
    }
    if (path === '/api/rebalancer/runs') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{
          runId: 1,
          createdAt: '2024-01-01T10:00:00Z',
          asOfDate: '2024-01-01',
          depotScope: ['tr']
        }])
      })
    }
    if (path === '/api/rebalancer/runs/1') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          runId: 1,
          createdAt: '2024-01-01T10:00:00Z',
          asOfDate: '2024-01-01',
          depotScope: ['tr'],
          narrativeMd: 'LLM narrative.',
          summary: {}
        })
      })
    }
    if (path === '/api/layer-targets') {
      const defaultProfiles = {
        CLASSIC: {
          displayName: 'Classic',
          description: 'Highly defensive allocation focused on the global core.',
          layerTargets: { 1: 0.8, 2: 0.15, 3: 0.04, 4: 0.01, 5: 0.0 },
          acceptableVariancePct: 3.0,
          constraints: { core_min: 0.7, layer4_max: 0.05, layer5_max: 0.03 }
        },
        BALANCED: {
          displayName: 'Balanced',
          description: 'Balanced mix of global core and thematic exposures.',
          layerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0.0 },
          acceptableVariancePct: 3.0,
          constraints: { core_min: 0.7, layer4_max: 0.05, layer5_max: 0.03 }
        }
      }
      const layerNames = {
        1: 'Global Core',
        2: 'Core-Plus',
        3: 'Themes',
        4: 'Individual Stocks',
        5: 'Unclassified'
      }

      if (route.request().method() === 'PUT') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            activeProfileKey: 'BALANCED',
            activeProfileDisplayName: 'Balanced',
            activeProfileDescription: 'Balanced mix of global core and themes.',
            profiles: defaultProfiles,
            effectiveLayerTargets: { 1: 0.4, 2: 0.3, 3: 0.2, 4: 0.1, 5: 0.0 },
            acceptableVariancePct: 1.5,
            layerNames,
            customOverridesEnabled: true,
            customLayerTargets: { 1: 0.4, 2: 0.3, 3: 0.2, 4: 0.1, 5: 0.0 },
            customAcceptableVariancePct: 1.5,
            updatedAt: '2024-01-01T00:00:00Z'
          })
        })
      }

      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          activeProfileKey: 'BALANCED',
          activeProfileDisplayName: 'Balanced',
          activeProfileDescription: 'Balanced mix of global core and thematic exposures.',
          profiles: defaultProfiles,
          effectiveLayerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0.0 },
          acceptableVariancePct: 3.0,
          layerNames,
          customOverridesEnabled: false,
          customLayerTargets: { 1: 0.0, 2: 0.0, 3: 0.0, 4: 0.0, 5: 0.0 },
          customAcceptableVariancePct: null,
          updatedAt: '2024-01-01T00:00:00Z'
        })
      })
    }
    if (path === '/api/layer-targets/reset') {
      const layerNames = {
        1: 'Global Core',
        2: 'Core-Plus',
        3: 'Themes',
        4: 'Individual Stocks',
        5: 'Unclassified'
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          activeProfileKey: 'BALANCED',
          activeProfileDisplayName: 'Balanced',
          activeProfileDescription: 'Balanced mix of global core and thematic exposures.',
          profiles: {
            CLASSIC: {
              displayName: 'Classic',
              description: 'Highly defensive allocation focused on the global core.',
              layerTargets: { 1: 0.8, 2: 0.15, 3: 0.04, 4: 0.01, 5: 0.0 },
              acceptableVariancePct: 3.0,
              constraints: { core_min: 0.7, layer4_max: 0.05, layer5_max: 0.03 }
            },
            BALANCED: {
              displayName: 'Balanced',
              description: 'Balanced mix of global core and thematic exposures.',
              layerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0.0 },
              acceptableVariancePct: 3.0,
              constraints: { core_min: 0.7, layer4_max: 0.05, layer5_max: 0.03 }
            }
          },
          effectiveLayerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0.0 },
          acceptableVariancePct: 3.0,
          layerNames,
          customOverridesEnabled: false,
          customLayerTargets: { 1: 0.0, 2: 0.0, 3: 0.0, 4: 0.0, 5: 0.0 },
          customAcceptableVariancePct: null,
          updatedAt: '2024-01-02T00:00:00Z'
        })
      })
    }
    if (path === '/api/depots') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ depotId: 1, depotCode: 'tr', name: 'Trade Republic' }])
      })
    }
    if (path.startsWith('/api/instruments/effective')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [], total: 0, limit: 50, offset: 0 })
      })
    }
    if (path === '/api/sparplans') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{
          savingPlanId: 1,
          depotId: 1,
          depotCode: 'tr',
          name: 'Sample',
          isin: 'DE0000000001',
          layer: 1,
          amountEur: 25,
          frequency: 'monthly',
          dayOfMonth: 1,
          active: true,
          lastChanged: '2024-01-01'
        }])
      })
    }
    if (path.startsWith('/api/sparplans')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({})
      })
    }
    if (path.startsWith('/api/imports/depot-statement')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ instrumentsImported: 1, positions: 1, snapshotStatus: 'imported' })
      })
    }

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({})
    })
  })
}

test.beforeEach(async ({ page }) => {
  await seedToken(page)
  await stubApi(page)
})

test('navigates across main pages', async ({ page }) => {
  await page.goto('/rulesets')
  await expect(page.getByRole('heading', { name: 'Reclassification Rulesets', level: 2 })).toBeVisible()

  await page.goto('/reclassifications')
  await expect(page.getByRole('heading', { name: 'Reclassifications' })).toBeVisible()

  await page.goto('/rebalancer')
  await expect(page.getByRole('heading', { name: 'Rebalancer' })).toBeVisible()

  await page.goto('/rebalancer/history')
  await expect(page.getByRole('heading', { name: 'Rebalancer History' })).toBeVisible()

  await page.goto('/layer-targets')
  await expect(page.getByRole('heading', { name: 'Profile Configuration' })).toBeVisible()

  await page.goto('/instruments')
  await expect(page.getByRole('heading', { name: 'Effective Instruments' })).toBeVisible()

  await page.goto('/savings-plans')
  await expect(page.getByRole('heading', { name: 'Savings plans' })).toBeVisible()
  await page.goto('/imports-exports')
  await expect(page.getByRole('heading', { name: 'Imports & Exports', level: 2 })).toBeVisible()
})

test('ruleset validate shows toast', async ({ page }) => {
  await page.goto('/rulesets')
  await page.locator('textarea').fill('{"schema_version":1,"name":"default","rules":[]}')
  await page.getByRole('button', { name: 'Validate' }).click({ force: true })
  await expect(page.getByText('Ruleset valid.')).toBeVisible()
})

test('reclassifications require selection', async ({ page }) => {
  await page.goto('/reclassifications')
  await page.getByRole('button', { name: 'Dry Run Apply' }).click({ force: true })
  await expect(page.getByText('Select at least one instrument.')).toBeVisible()
})

test('savings plans edit then reset form', async ({ page }) => {
  await page.goto('/savings-plans')
  await page.getByRole('button', { name: 'Edit' }).click({ force: true })
  await expect(page.getByRole('heading', { name: 'Update Savings plan' })).toBeVisible()
  await page.getByRole('button', { name: 'Reset' }).click({ force: true })
  await expect(page.getByRole('heading', { name: 'Create Savings plan' })).toBeVisible()
})

test('rebalancer displays savings plan rebalancing', async ({ page }) => {
  await page.goto('/rebalancer')
  await expect(page.getByRole('heading', { name: 'Savings plan Rebalancing' })).toBeVisible()
  await expect(page.getByText('Monthly by Layer')).toBeVisible()
  await expect(page.getByText('Rebalancing Proposal (Savings plan weights)')).toBeVisible()
  await expect(page.getByText('Proposal source')).toBeVisible()
  await expect(page.getByText('Valuation glossary', { exact: true })).toBeVisible()
})

test('rebalancer history loads narrative', async ({ page }) => {
  await page.goto('/rebalancer/history')
  await page.getByRole('button', { name: 'View' }).click({ force: true })
  await expect(page.getByText('LLM narrative.')).toBeVisible()
})

test('layer targets can reset to default', async ({ page }) => {
  await page.goto('/layer-targets')
  await page.getByRole('button', { name: 'Reset to Profile Default' }).click({ force: true })
  await expect(page.getByText('Layer targets reset to profile defaults.')).toBeVisible()
})

test('layer targets save custom overrides', async ({ page }) => {
  await page.goto('/layer-targets')

  await page.getByRole('checkbox', { name: 'Enable custom overrides' }).check({ force: true })
  const firstCustomInput = page.locator('tbody tr').first().locator('input.input.compact').first()
  await firstCustomInput.fill('0.4')
  await page.getByRole('button', { name: 'Save' }).click({ force: true })

  await expect(page.getByText('Layer targets saved.')).toBeVisible()
  await expect(page.getByText('Custom overrides are active.')).toBeVisible()
})

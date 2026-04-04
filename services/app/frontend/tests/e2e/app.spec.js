import { test, expect } from '@playwright/test'

const seedToken = async (page) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('jwt', 'test-token')
  })
}

const stubApi = async (page) => {
  let pendingSaveRun = false
  await page.route('**/auth/health', async (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'ok' })
    })
  })
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
            deltaEur: -2,
            currentTargetTotalWeightPct: 62.3,
            currentTargetTotalAmountEur: 62300,
            targetTotalWeightPct: 60,
            targetTotalAmountEur: 60000
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

    const kbDossiers = [
      {
        isin: 'DE0000000001',
        name: 'Alpha Knowledge Fund',
        hasDossier: true,
        latestDossierStatus: 'APPROVED',
        latestUpdatedAt: '2025-01-01T00:00:00Z',
        latestDossierVersion: 1,
        approvalStatus: 'APPROVED',
        latestExtractionStatus: 'APPROVED',
        blacklistScope: 'NONE',
        blacklistPendingChange: false,
        hasApprovedDossier: true,
        hasApprovedExtraction: true,
        stale: false,
        extractionFreshness: 'CURRENT'
      },
      {
        isin: 'DE0000000002',
        name: 'Beta Filtered Fund',
        hasDossier: true,
        latestDossierStatus: 'DRAFT',
        latestUpdatedAt: '2025-01-02T00:00:00Z',
        latestDossierVersion: 2,
        approvalStatus: 'NOT_APPROVED',
        latestExtractionStatus: 'PENDING_REVIEW',
        blacklistScope: 'ALL_PROPOSALS',
        blacklistPendingChange: false,
        hasApprovedDossier: false,
        hasApprovedExtraction: false,
        stale: false,
        extractionFreshness: 'OUTDATED'
      }
    ]

    if (path === '/api/kb/config') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ enabled: true })
      })
    }
    if (path === '/api/llm/config') {
      if (route.request().method() === 'PUT') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            editable: true,
            password_set: true,
            standard: {
              provider: 'openai',
              base_url: 'https://api.openai.com/v1',
              model: 'gpt-5-mini',
              api_key_set: true
            },
            websearch: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-5-mini', api_key_set: true, enabled: true },
            extraction: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-5-mini', api_key_set: true, enabled: true },
            narrative: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-5-mini', api_key_set: true, enabled: true }
          })
        })
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          editable: true,
          password_set: true,
          standard: {
            provider: 'openai',
            base_url: 'https://api.openai.com/v1',
            model: 'gpt-5-mini',
            api_key_set: false
          },
          websearch: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-5-mini', api_key_set: false, enabled: false },
          extraction: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-5-mini', api_key_set: false, enabled: false },
          narrative: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-5-mini', api_key_set: false, enabled: false }
        })
      })
    }
    if (path === '/api/kb/dossiers') {
      const query = (url.searchParams.get('q') || '').toLowerCase()
      const stale = url.searchParams.get('stale')
      const status = url.searchParams.get('status')
      if (stale === 'true' && status === 'APPROVED') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ items: [], total: 0 })
        })
      }
      const items = query
        ? kbDossiers.filter((item) => item.isin.toLowerCase().includes(query) || item.name.toLowerCase().includes(query))
        : kbDossiers
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items, total: items.length })
      })
    }
    if (path === '/api/kb/runs') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ items: [], total: 0 })
      })
    }
    if (path === '/api/kb/llm-actions') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      })
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
  await page.goto('/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()

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
  await expect(page.getByText('Rebalancing Proposal (Savings plan amounts, EUR)')).toBeVisible()
  await expect(page.getByRole('columnheader', { name: 'Current Target %' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: 'Target Total (Rebalanced) %' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: 'Current Target Total Amount €' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: 'Target Total Amount (Rebalanced) €' })).toBeVisible()
  await expect(page.getByText('62.30')).toBeVisible()
  await expect(page.getByText('62300.00')).toBeVisible()
  await expect(page.getByText('60000.00')).toBeVisible()
  await expect(page.getByText('Proposal source')).toBeVisible()
  await expect(page.getByText('Valuation glossary', { exact: true })).toBeVisible()
})

test('knowledge base dossier filters are hidden by default and can be applied', async ({ page }) => {
  await page.goto('/knowledge-base')

  await expect(page.getByRole('heading', { name: 'Knowledge Base' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Show Filter' })).toBeVisible()
  await expect(page.getByText('No filters applied')).toBeVisible()
  await expect(page.locator('form.kb-filter-form')).toBeHidden()

  await page.getByRole('button', { name: 'Show Filter' }).click()
  await expect(page.getByRole('button', { name: 'Hide Filter' })).toBeVisible()
  await expect(page.locator('form.kb-filter-form')).toBeVisible()

  await page.getByPlaceholder('ISIN or name').fill('beta')
  const filteredResponse = page.waitForResponse((response) => {
    return response.url().includes('/api/kb/dossiers?') && response.url().includes('q=beta')
  })
  await page.getByRole('button', { name: 'Refresh' }).click()
  await filteredResponse

  await expect(page.getByText('Beta Filtered Fund')).toBeVisible()
  await expect(page.getByText('Alpha Knowledge Fund')).not.toBeVisible()
  await expect(page.getByText('1 filter active: Search')).toBeVisible()

  await page.getByRole('button', { name: 'Hide Filter' }).click()
  await expect(page.getByRole('button', { name: 'Show Filter' })).toBeVisible()
  await expect(page.locator('form.kb-filter-form')).toBeHidden()
  await expect(page.getByText('1 filter active: Search')).toBeVisible()
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

test('llm configuration view shows standard-based default status', async ({ page }) => {
  await page.goto('/llm-configuration')
  await expect(page.getByRole('heading', { name: 'LLM Configuration' })).toBeVisible()
  await expect(page.getByText('API key configured: No')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Set API key' })).toBeVisible()
  await expect(page.locator('#standard-api-key-editor')).toHaveCount(0)
  await expect(page.getByText('Standard API key is not configured.').first()).toBeVisible()
})

test('llm configuration opens and closes the key editor explicitly', async ({ page }) => {
  await page.goto('/llm-configuration')

  await page.getByRole('button', { name: 'Set API key' }).click()
  await expect(page.locator('#standard-api-key-editor')).toBeVisible()

  await page.locator('#standard-api-key-editor').getByRole('button', { name: 'Cancel' }).click()
  await expect(page.locator('#standard-api-key-editor')).toHaveCount(0)
})

test('llm configuration shows a pending status after switching websearch to custom', async ({ page }) => {
  await page.goto('/llm-configuration')
  const websearchCard = page.locator('.llm-function-card').filter({ has: page.getByRole('heading', { name: 'Websearch' }) })

  await websearchCard.getByRole('combobox').selectOption('CUSTOM')
  await expect(websearchCard.getByText('Pending: custom configuration')).toBeVisible()
})

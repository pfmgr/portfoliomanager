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

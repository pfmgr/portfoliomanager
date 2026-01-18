import { test, expect } from '@playwright/test'

const seedToken = async (page) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('jwt', 'test-token')
  })
}

test('edit and clear override from effective instruments table', async ({ page }) => {
  await seedToken(page)

  await page.route('**/api/instruments/effective**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [{
          isin: 'DE0000000001',
          effectiveName: 'Alpha',
          effectiveAssetClass: 'Equity',
          effectiveLayer: 2,
          hasOverride: true,
          overrideName: 'Alpha Override',
          overrideAssetClass: 'Equity',
          overrideLayer: 2
        }],
        total: 1,
        limit: 50,
        offset: 0
      })
    })
  })

  await page.route('**/api/overrides/DE0000000001', async (route) => {
    if (route.request().method() === 'PUT') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    }
    if (route.request().method() === 'DELETE') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/instruments')
  await expect(page.getByRole('heading', { name: 'Effective Instruments' })).toBeVisible()

  const row = page.locator('table.table').getByRole('row', { name: /DE0000000001/ })
  await expect(row).toBeVisible()
  await row.getByRole('button', { name: 'Edit' }).click({ force: true })
  await row.getByLabel('Override Name').fill('Alpha Manual')

  const savePromise = page.waitForRequest('**/api/overrides/DE0000000001')
  await row.getByRole('button', { name: 'Save' }).click({ force: true })
  const saveRequest = await savePromise
  expect(saveRequest.method()).toBe('PUT')

  page.once('dialog', (dialog) => dialog.accept())
  const clearPromise = page.waitForRequest('**/api/overrides/DE0000000001')
  await row.getByRole('button', { name: 'Clear' }).click({ force: true })
  const clearRequest = await clearPromise
  expect(clearRequest.method()).toBe('DELETE')
})

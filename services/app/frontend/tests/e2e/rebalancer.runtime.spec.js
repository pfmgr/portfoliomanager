import { test, expect } from '@playwright/test'

import { getAdminCredentials, loginThroughForm } from './login.helpers.js'

test('rebalancer running stack shows the seeded blacklist proposal fixture', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await loginThroughForm(page, username, password)
  await page.waitForURL('**/start')

  await page.goto('/rebalancer')
  await expect(page.getByRole('heading', { name: 'Rebalancer' })).toBeVisible()

  await page.getByRole('button', { name: 'Refresh' }).click()

  await expect(page.getByText('Rebalancing Proposal (Savings plan amounts, EUR)')).toBeVisible({ timeout: 30000 })
  const fixtureRow = page.getByRole('table', { name: 'Instrument-level savings plan proposal.' })
    .locator('tr')
    .filter({ hasText: 'ZZTESTRBL003' })
    .first()
  await expect(fixtureRow).toBeVisible({ timeout: 30000 })
  await expect(fixtureRow).toContainText('Blacklisted from Saving Plan Proposals')
})

import { test, expect } from '@playwright/test'

import { getAdminCredentials, loginThroughForm } from './login.helpers.js'

test('assessor running stack shows the seeded discard fixture', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await loginThroughForm(page, username, password)
  await page.waitForURL('**/start')

  await page.goto('/assessor')
  await expect(page.getByRole('heading', { name: 'Assessor' })).toBeVisible()

  await page.getByRole('button', { name: 'Run Assessment' }).click()

  await expect(page.getByRole('heading', { name: 'Saving Plan Suggestions' })).toBeVisible({ timeout: 30000 })
  const fixtureRow = page.locator('tr').filter({ hasText: 'ZZTESTAAA001' }).first()
  await expect(fixtureRow).toBeVisible({ timeout: 30000 })
  await expect(fixtureRow).toContainText('Discard')
  await expect(fixtureRow).toContainText('Blacklisted from Saving Plan Proposals')
})

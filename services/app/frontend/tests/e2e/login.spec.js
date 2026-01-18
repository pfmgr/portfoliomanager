import { test, expect } from '@playwright/test'

test('login via form obtains JWT and opens rulesets', async ({ page }) => {
  const username = process.env.ADMIN_USER || 'admin'
  const password = process.env.ADMIN_PASS || 'admin'

  await page.goto('/login')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('main').getByRole('button', { name: 'Login' }).click()

  await page.waitForURL('**/rulesets')
  await expect(page.getByRole('heading', { name: 'Reclassification Rulesets', level: 2 })).toBeVisible()

  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  expect(token).toBeTruthy()
})

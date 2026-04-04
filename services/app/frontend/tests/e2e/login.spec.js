import { test, expect } from '@playwright/test'

import { authHealthUrl, getAdminCredentials, loginThroughForm } from './login.helpers.js'

test('backend auth health endpoint is reachable', async ({ request }) => {
  const response = await request.get(authHealthUrl)
  expect(response.status()).toBe(200)
})

test('login via form obtains JWT and opens start', async ({ page }) => {
  const { username, password } = getAdminCredentials()
  await loginThroughForm(page, username, password)

  await page.waitForURL('**/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()

  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  expect(token).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/)
})

test('login returns to originally requested protected route', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.goto('/instruments')
  await expect(page).toHaveURL(/\/login\?returnTo=(%2F|\/)instruments/)

  await loginThroughForm(page, username, password, { navigate: false })

  await page.waitForURL('**/instruments')
  await expect(page.getByRole('heading', { name: 'Effective Instruments' })).toBeVisible()
})

import { test, expect } from '@playwright/test'

test('login via form obtains JWT and opens start', async ({ page }) => {
  const username = process.env.ADMIN_USER || 'admin'
  const password = process.env.ADMIN_PASS || 'admin'

  await page.route('**/auth/health', async (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'ok' })
    })
  })

  await page.goto('/login')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('main').getByRole('button', { name: 'Login' }).click()

  await page.waitForURL('**/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()

  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  expect(token).toBeTruthy()
})

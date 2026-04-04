import { expect } from '@playwright/test'

export const authHealthUrl = process.env.AUTH_HEALTH_URL || 'http://127.0.0.1:18089/auth/health'

export const getAdminCredentials = () => {
  const username = process.env.ADMIN_USER || 'admin'
  const password = process.env.ADMIN_PASS
  if (!password) {
    throw new Error('ADMIN_PASS must be set for login E2E tests.')
  }
  return { username, password }
}

export const loginThroughForm = async (page, username, password, options = {}) => {
  if (options.navigate !== false) {
    await page.goto('/login')
  }
  await expect(page.getByLabel('Username')).toBeVisible()
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('main').getByRole('button', { name: 'Login' }).click()
}

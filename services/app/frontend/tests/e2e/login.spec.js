import { test, expect } from '@playwright/test'

const authHealthUrl = process.env.AUTH_HEALTH_URL || 'http://127.0.0.1:18089/auth/health'

const getAdminCredentials = () => {
  const username = process.env.ADMIN_USER || 'admin'
  const password = process.env.ADMIN_PASS
  if (!password) {
    throw new Error('ADMIN_PASS must be set for login E2E tests.')
  }
  return { username, password }
}

const loginThroughForm = async (page, username, password, options = {}) => {
  if (options.navigate !== false) {
    await page.goto('/login')
  }
  await expect(page.getByLabel('Username')).toBeVisible()
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('main').getByRole('button', { name: 'Login' }).click()
}

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

test('invalid credentials keep user on login and show error', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.route('**/auth/token', async (route) => {
    return route.fulfill({
      status: 400,
      contentType: 'application/json',
      body: JSON.stringify({ detail: 'Invalid request.' })
    })
  })

  await loginThroughForm(page, username, `${password}-wrong`)

  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  await expect(page.getByRole('alert')).toContainText('Invalid request.')
  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  expect(token).toBeNull()
})

test('server-side auth error is shown without redirect', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.route('**/auth/token', async (route) => {
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ detail: 'Authentication service unavailable.' })
    })
  })

  await loginThroughForm(page, username, password)

  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  await expect(page.getByRole('alert')).toContainText('Authentication service unavailable.')
  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  expect(token).toBeNull()
})

test('login returns to originally requested protected route', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.goto('/instruments')
  await expect(page).toHaveURL(/\/login\?returnTo=(%2F|\/)instruments/)

  await loginThroughForm(page, username, password, { navigate: false })

  await page.waitForURL('**/instruments')
  await expect(page.getByRole('heading', { name: 'Effective Instruments' })).toBeVisible()
})

test('network error on login shows alert and keeps user on login', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.route('**/auth/token', async (route) => {
    await route.abort('failed')
  })

  await loginThroughForm(page, username, password)

  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  await expect(page.getByRole('alert')).toContainText(/failed|network|fetch/i)
  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  expect(token).toBeNull()
})

test('unsafe returnTo query falls back to start', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.goto('/login?returnTo=https://evil.example')
  await loginThroughForm(page, username, password, { navigate: false })

  await page.waitForURL('**/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()
})

test('unknown returnTo route falls back to start', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await page.goto('/login?returnTo=/does-not-exist')
  await loginThroughForm(page, username, password, { navigate: false })

  await page.waitForURL('**/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()
})

import { test, expect } from '@playwright/test'

import { loginThroughForm } from './login.helpers.js'

const stubCredentials = {
  username: 'stub-admin',
  password: 'stub-password'
}

const stubSuccessfulLogin = async (page) => {
  await page.route('**/auth/token', async (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ token: 'stub.header.signature', tokenType: 'Bearer' })
    })
  })
}

test('invalid credentials keep user on login and show error', async ({ page }) => {
  const { username, password } = stubCredentials

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
  const { username, password } = stubCredentials

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

test('network error on login shows alert and keeps user on login', async ({ page }) => {
  const { username, password } = stubCredentials

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
  const { username, password } = stubCredentials

  await stubSuccessfulLogin(page)

  await page.goto('/login?returnTo=https://evil.example')
  await loginThroughForm(page, username, password, { navigate: false })

  await page.waitForURL('**/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()
})

test('unknown returnTo route falls back to start', async ({ page }) => {
  const { username, password } = stubCredentials

  await stubSuccessfulLogin(page)

  await page.goto('/login?returnTo=/does-not-exist')
  await loginThroughForm(page, username, password, { navigate: false })

  await page.waitForURL('**/start')
  await expect(page.getByRole('heading', { name: 'Start', level: 1 })).toBeVisible()
})

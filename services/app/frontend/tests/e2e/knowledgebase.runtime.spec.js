import { test, expect } from '@playwright/test'

import { getAdminCredentials, loginThroughForm } from './login.helpers.js'

function formatActionType(type) {
  if (!type) return '-'
  switch (type) {
    case 'RESEARCH':
      return 'Research'
    case 'ALTERNATIVES':
      return 'Alternatives'
    case 'EXTRACTION':
      return 'Extraction'
    case 'REFRESH':
      return 'Refresh'
    case 'MISSING_METRICS':
      return 'Missing metrics'
    default:
      return type
  }
}

function formatActionTrigger(trigger) {
  if (!trigger) return '-'
  if (trigger === 'AUTO') return 'Auto'
  if (trigger === 'USER') return 'User'
  return trigger
}

function formatActionStatus(status) {
  switch (status) {
    case 'QUEUED':
      return 'Queued'
    case 'RUNNING':
      return 'Running'
    case 'WAITING_RETRY':
      return 'Waiting retry'
    case 'REVIEW_REQUIRED':
      return 'Review required'
    case 'COMPLETED':
      return 'Completed'
    case 'FAILED':
      return 'Failed'
    case 'CANCELED':
      return 'Canceled'
    default:
      return status ? status.toLowerCase().replace(/_/g, ' ') : 'unknown'
  }
}

function summarizeActionCollection(actions) {
  const total = Array.isArray(actions) ? actions.length : 0
  if (!total) return 'No LLM actions.'
  const active = actions.filter((action) => ['QUEUED', 'RUNNING', 'WAITING_RETRY'].includes(action?.status)).length
  const review = actions.filter((action) => action?.status === 'REVIEW_REQUIRED').length
  if (active > 0) {
    return review > 0
      ? `${active} active action${active === 1 ? '' : 's'} and ${review} pending review.`
      : `${active} active action${active === 1 ? '' : 's'} polling from the server.`
  }
  return review > 0 ? `${review} action${review === 1 ? '' : 's'} require review.` : `${total} action${total === 1 ? '' : 's'} loaded.`
}

async function fetchAuthedJson(page, path) {
  const token = await page.evaluate(() => sessionStorage.getItem('jwt'))
  if (!token) {
    throw new Error('Missing JWT after login.')
  }

  return page.evaluate(async ({ path, token }) => {
    const response = await fetch(path, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const text = await response.text()
    let body = null
    if (text) {
      try {
        body = JSON.parse(text)
      } catch {
        body = text
      }
    }
    return { status: response.status, ok: response.ok, body }
  }, { path, token })
}

test('knowledge base route redirects unauthenticated users to login', async ({ page }) => {
  await page.goto('/knowledge-base')

  await expect(page).toHaveURL(/\/login\?returnTo=(%2F|\/)knowledge-base/)
  await expect(page.getByLabel('Username')).toBeVisible()
})

test('authenticated knowledge base keeps actions and detail state server-authoritative', async ({ page }) => {
  const { username, password } = getAdminCredentials()

  await loginThroughForm(page, username, password)
  await page.waitForURL('**/start')

  const config = await fetchAuthedJson(page, '/api/kb/config')
  expect(config.status).toBe(200)
  expect(config.body.enabled).toBe(true)

  const actionsResponse = await fetchAuthedJson(page, '/api/kb/llm-actions')
  expect(actionsResponse.status).toBe(200)
  expect(Array.isArray(actionsResponse.body)).toBe(true)

  await page.goto('/knowledge-base')
  await expect(page.getByRole('heading', { name: 'Knowledge Base' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'LLM actions' })).toBeVisible()

  await page.getByRole('tab', { name: 'LLM actions' }).click()
  const actionsPanel = page.locator('#kb-panel-actions')
  await expect(actionsPanel).toBeVisible()
  await expect(page.getByRole('region', { name: 'LLM actions table' })).toBeVisible()
  await expect(page.getByText('Track persisted workflow records. Status, retry timing, and evidence stay server-authoritative.')).toBeVisible()

  const emptyState = actionsPanel.getByText('No actions yet.')
  const expectedSummary = summarizeActionCollection(actionsResponse.body)
  const liveMessage = actionsPanel.locator('[role="status"][aria-live="polite"]')

  if (await emptyState.isVisible()) {
    await expect(actionsPanel.getByText('No actions yet.')).toBeVisible()
    await expect(liveMessage).toContainText('No LLM actions.')
    await expect(actionsPanel.getByRole('button', { name: 'Cancel' })).toHaveCount(0)
    await expect(actionsPanel.getByRole('button', { name: 'Dismiss' })).toHaveCount(0)

    await page.reload()
    await page.getByRole('tab', { name: 'LLM actions' }).click()
    await expect(page.locator('#kb-panel-actions')).toBeVisible()
    await expect(page.getByRole('region', { name: 'LLM actions table' })).toBeVisible()
    await expect(page.getByText('Track persisted workflow records. Status, retry timing, and evidence stay server-authoritative.')).toBeVisible()
    await expect(page.locator('#kb-panel-actions')).toContainText('No actions yet.')
    await expect(page.locator('#kb-panel-actions')).toContainText('No LLM actions.')
    await expect(page.locator('#kb-panel-actions')).not.toContainText('Cancel')
    await expect(page.locator('#kb-panel-actions')).not.toContainText('Dismiss')
  } else {
    const terminalStatuses = new Set(['REVIEW_REQUIRED', 'COMPLETED', 'FAILED', 'CANCELED'])
    const terminalAction = actionsResponse.body.find((item) => terminalStatuses.has(item.status))
    await expect(liveMessage).toContainText(expectedSummary)

    if (terminalAction) {
      const detailResponse = await fetchAuthedJson(page, `/api/kb/llm-actions/${encodeURIComponent(terminalAction.actionId)}`)
      expect(detailResponse.status).toBe(200)
      expect(detailResponse.body.actionId).toBe(terminalAction.actionId)
      expect(detailResponse.body.status).toBe(terminalAction.status)
    }

    await page.reload()
    await page.getByRole('tab', { name: 'LLM actions' }).click()
    await expect(page.locator('#kb-panel-actions')).toBeVisible()
    await expect(page.getByRole('region', { name: 'LLM actions table' })).toBeVisible()
    await expect(page.getByText('Track persisted workflow records. Status, retry timing, and evidence stay server-authoritative.')).toBeVisible()
    await expect(page.locator('#kb-panel-actions').locator('[role="status"][aria-live="polite"]')).toContainText(expectedSummary)
  }
})

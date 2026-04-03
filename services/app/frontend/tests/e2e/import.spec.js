import { test, expect } from '@playwright/test'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const fixturesDir = path.join(__dirname, 'fixtures')

const seedToken = async (page) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('jwt', 'test-token')
  })
}

const stubDepots = async (page) => {
  await page.route('**/api/depots', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        { depotId: 1, depotCode: 'deka', name: 'Deka Depot' },
        { depotId: 2, depotCode: 'tr', name: 'Trade Republic' }
      ])
    })
  })
}

const stubImport = async (page, result) => {
  await page.route('**/api/imports/depot-statement', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(result)
    })
  })
}

const stubBackupImport = async (page, result) => {
  await page.route('**/api/backups/import', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(result)
    })
  })
}

test.beforeEach(async ({ page }) => {
  await seedToken(page)
  await stubDepots(page)
})

test('shows backup copy for full and KB backups', async ({ page }) => {
  await page.goto('/imports-exports')

  const databaseBackupSection = page.locator('.section').filter({ has: page.getByRole('heading', { name: 'Full Database Backup' }) })
  await expect(databaseBackupSection).toContainText('include unencrypted LLM API keys')
  await expect(databaseBackupSection).toContainText('Store them securely and do not share them')
  await expect(databaseBackupSection).toContainText('Older backups without saved LLM settings leave the current LLM configuration unchanged')
  await expect(databaseBackupSection.getByLabel('I understand that importing a full backup may replace LLM configuration and API keys when they are present in the backup.')).toBeVisible()

  const knowledgeBaseSection = page.locator('.section').filter({ has: page.getByRole('heading', { name: 'Knowledge Base (KB) Backup' }) })
  await expect(knowledgeBaseSection).toContainText('They do not include LLM configuration or API keys')
  await expect(knowledgeBaseSection).toContainText('Importing replaces existing KB data')
})

test('imports Deka CSV sample via UI', async ({ page }) => {
  await stubImport(page, { instrumentsImported: 20, positions: 20, snapshotStatus: 'imported' })
  await page.goto('/imports-exports')

  await page.getByRole('combobox').selectOption('deka')
  await page.setInputFiles('input[type="file"]', path.join(fixturesDir, 'Depot_12345678_Depotbestand_20260101.CSV'))

  const requestPromise = page.waitForRequest('**/api/imports/depot-statement')
  const runButton = page.getByRole('button', { name: 'Run Import' })
  await runButton.click({ force: true })

  const request = await requestPromise
  const postData = request.postData() || ''
  expect(postData).toContain('Depot_12345678_Depotbestand_20260101.CSV')
  expect(postData).toContain('depotCode')
  expect(postData).toContain('deka')

  await expect(page.getByText('Import complete:')).toBeVisible()
})

test('imports TR PDF sample via UI', async ({ page }) => {
  await stubImport(page, { instrumentsImported: 1, positions: 1, snapshotStatus: 'imported' })
  await page.goto('/imports-exports')

  await page.getByRole('combobox').selectOption('tr')
  await page.setInputFiles('input[type="file"]', path.join(fixturesDir, 'Wertpapiere.pdf'))

  const requestPromise = page.waitForRequest('**/api/imports/depot-statement')
  const runButton = page.getByRole('button', { name: 'Run Import' })
  await runButton.click({ force: true })

  const request = await requestPromise
  const postData = request.postData() || ''
  expect(postData).toContain('Wertpapiere.pdf')
  expect(postData).toContain('depotCode')
  expect(postData).toContain('tr')

  await expect(page.getByText('Import complete:')).toBeVisible()
})

test('imports database backup via UI', async ({ page }) => {
  await stubBackupImport(page, { tablesImported: 5, rowsImported: 123, formatVersion: 2 })
  await page.goto('/imports-exports')
  const databaseBackupSection = page.locator('.section').filter({ has: page.getByRole('heading', { name: 'Full Database Backup' }) })
  await expect(databaseBackupSection).toContainText('include unencrypted LLM API keys')
  await expect(databaseBackupSection).toContainText('Store them securely and do not share them')
  await databaseBackupSection.getByLabel('I understand that importing a full backup may replace LLM configuration and API keys when they are present in the backup.').check()
  await databaseBackupSection.locator('input[accept=".zip"]').setInputFiles(path.join(fixturesDir, 'database-backup.zip'))
  await expect(page.getByText('Backup imported: tables=5, rows=123, format=v2.')).toBeVisible()
})

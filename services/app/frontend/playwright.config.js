import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30000,
  expect: {
    timeout: 10000
  },
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8090',
    actionTimeout: 10000,
    chromiumSandbox: false,
    launchOptions: {
      args: ['--no-sandbox', '--disable-setuid-sandbox']
    }
  }
})

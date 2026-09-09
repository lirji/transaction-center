import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.TRADE_E2E_UI_PORT ?? 4185)

export default defineConfig({
  outputDir: 'test-results/contract',
  testMatch: 'console.spec.ts',
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['html', { outputFolder: 'playwright-report/contract', open: 'never' }], ['github']] : 'list',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'pnpm exec vite --config vite.e2e.config.ts',
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      ...process.env,
      VITE_AUTH_MODE: 'TEST',
      VITE_API_BASE_URL: '',
    },
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})

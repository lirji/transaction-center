import { defineConfig, devices } from '@playwright/test'

// 由Java隔离测试提供真实API、OIDC及端口，缺少环境不得误连共享实例。
for (const key of ['TRADE_E2E_BACKEND_URL', 'TRADE_E2E_ISSUER', 'TRADE_E2E_UI_PORT', 'TRADE_E2E_MANIFEST']) {
  if (!process.env[key]) throw new Error(`Missing isolated integration setting: ${key}`)
}
const url = `http://127.0.0.1:${process.env.TRADE_E2E_UI_PORT}`
export default defineConfig({
  outputDir: 'test-results/integration',
  testDir: './e2e', testMatch: 'integration.spec.ts', workers: 1,
  timeout: 45_000, expect: { timeout: 15_000 }, retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/integration', open: 'never' }]],
  // 身份码/JWT不写trace、HAR或storageState；截图只保留页面结果。
  use: { baseURL: url, trace: 'off', screenshot: 'only-on-failure' },
  webServer: { command: 'pnpm exec vite --config vite.e2e.config.ts', url,
    reuseExistingServer: false, timeout: 60_000 },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})

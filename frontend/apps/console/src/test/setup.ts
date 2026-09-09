import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

window.__TRADE_CONFIG__ = {
  API_BASE_URL: '',
  AUTH_MODE: 'TEST',
  ALLOW_TEST_AUTH: true,
  OIDC_AUTHORITY: 'http://localhost:8000',
  OIDC_CLIENT_ID: 'transaction-center-console',
  OIDC_AUDIENCE: 'transaction-center',
  OIDC_SCOPE: 'openid profile',
}

afterEach(cleanup)

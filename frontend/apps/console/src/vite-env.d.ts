/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_AUTH_MODE?: 'OIDC' | 'TEST'
  readonly VITE_OIDC_AUTHORITY?: string
  readonly VITE_OIDC_CLIENT_ID?: string
  readonly VITE_OIDC_AUDIENCE?: string
  readonly VITE_OIDC_ORGANIZATION?: string
  readonly VITE_OIDC_SCOPE?: string
  readonly VITE_ALLOW_TEST_AUTH?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  __TRADE_CONFIG__?: {
    API_BASE_URL?: string
    AUTH_MODE?: 'OIDC' | 'TEST'
    ALLOW_TEST_AUTH?: boolean | string
    OIDC_AUTHORITY?: string
    OIDC_CLIENT_ID?: string
    OIDC_AUDIENCE?: string
    OIDC_ORGANIZATION?: string
    OIDC_SCOPE?: string
  }
}

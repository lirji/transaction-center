export type AuthMode = 'OIDC' | 'TEST'

export type RuntimeConfig = {
  apiBaseUrl: string
  authMode: AuthMode
  allowTestAuth: boolean
  oidcAuthority: string
  oidcClientId: string
  oidcAudience: string
  oidcOrganization: string
  oidcScope: string
  requestTimeoutMs: number
  workspaces: { commerce?: string; marketing?: string; benefit?: string }
}

export class RuntimeConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'RuntimeConfigError'
  }
}

function booleanValue(value: boolean | string | undefined): boolean | undefined {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    if (value.toLowerCase() === 'true') return true
    if (value.toLowerCase() === 'false') return false
  }
  return undefined
}

export function originFromAuthority(authority: string): string {
  try {
    return new URL(authority).origin
  } catch {
    return ''
  }
}

function sourceWorkspace(configured: string | undefined, localPort: number) {
  const loopback = (host: string) => host === 'localhost' || host === '127.0.0.1' || host === '[::1]'
  const value = configured ?? (loopback(window.location.hostname) ? `http://localhost:${localPort}/` : undefined)
  if (!value) return undefined
  try {
    const url = new URL(value)
    if (url.username || url.password || (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback(url.hostname)))) {
      return undefined
    }
    return url.toString()
  } catch {
    return undefined
  }
}

function parseConfig(): RuntimeConfig {
  const injected = window.__TRADE_CONFIG__
  const authMode = (import.meta.env.VITE_AUTH_MODE ?? injected?.AUTH_MODE ?? 'OIDC') as AuthMode
  const allowTestAuth = booleanValue(injected?.ALLOW_TEST_AUTH) ?? (import.meta.env.DEV && import.meta.env.VITE_AUTH_MODE === 'TEST')

  if (authMode !== 'OIDC' && authMode !== 'TEST') {
    throw new RuntimeConfigError('交易管理台缺少合法 AUTH_MODE，已拒绝启动。')
  }
  if (authMode === 'TEST' && import.meta.env.PROD && !allowTestAuth) {
    throw new RuntimeConfigError('生产构建禁止 TEST 身份。请设置 AUTH_MODE=OIDC。')
  }
  if (authMode === 'OIDC' && import.meta.env.PROD) {
    const authority = injected?.OIDC_AUTHORITY ?? import.meta.env.VITE_OIDC_AUTHORITY
    const clientId = injected?.OIDC_CLIENT_ID ?? import.meta.env.VITE_OIDC_CLIENT_ID
    if (!authority || !clientId) {
      throw new RuntimeConfigError('OIDC 模式缺少 OIDC_AUTHORITY 或 OIDC_CLIENT_ID。')
    }
  }

  return Object.freeze({
    apiBaseUrl: injected?.API_BASE_URL ?? import.meta.env.VITE_API_BASE_URL ?? '',
    authMode,
    allowTestAuth: Boolean(allowTestAuth),
    oidcAuthority: injected?.OIDC_AUTHORITY ?? import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8000',
    oidcClientId: injected?.OIDC_CLIENT_ID ?? import.meta.env.VITE_OIDC_CLIENT_ID ?? 'transaction-center-console',
    oidcAudience: injected?.OIDC_AUDIENCE ?? import.meta.env.VITE_OIDC_AUDIENCE ?? 'transaction-center',
    oidcOrganization: injected?.OIDC_ORGANIZATION ?? import.meta.env.VITE_OIDC_ORGANIZATION ?? '',
    oidcScope: injected?.OIDC_SCOPE ?? import.meta.env.VITE_OIDC_SCOPE ?? 'openid profile',
    requestTimeoutMs: 30_000,
    workspaces: {
      commerce: sourceWorkspace(injected?.COMMERCE_WORKSPACE_URL ?? import.meta.env.VITE_COMMERCE_WORKSPACE_URL, 8602),
      marketing: sourceWorkspace(injected?.MARKETING_WORKSPACE_URL ?? import.meta.env.VITE_MARKETING_WORKSPACE_URL, 8084),
      benefit: sourceWorkspace(injected?.BENEFIT_WORKSPACE_URL ?? import.meta.env.VITE_BENEFIT_WORKSPACE_URL, 8083),
    },
  })
}

const placeholder: RuntimeConfig = Object.freeze({
  apiBaseUrl: '',
  authMode: 'OIDC',
  allowTestAuth: false,
  oidcAuthority: '',
  oidcClientId: '',
  oidcAudience: 'transaction-center',
  oidcOrganization: '',
  oidcScope: 'openid',
  requestTimeoutMs: 30_000,
  workspaces: {},
})

let parsed: RuntimeConfig = placeholder
let parseError: RuntimeConfigError | undefined
try {
  parsed = parseConfig()
} catch (cause) {
  parseError = cause instanceof RuntimeConfigError ? cause : new RuntimeConfigError('交易管理台配置无效，已拒绝启动。')
}

export const runtimeConfig = parsed
export const runtimeConfigError = parseError
export const oidcOrigin = originFromAuthority(runtimeConfig.oidcAuthority)

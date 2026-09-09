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

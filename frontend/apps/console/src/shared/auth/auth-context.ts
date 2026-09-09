import { createContext } from 'react'

export const TRADE_ADMIN = 'trade.admin'
export const TRADE_EVENTS_REPLAY = 'trade.events.replay'
export const TRADE_RECONCILIATION_QUERY = 'trade.reconciliation.query'
export const TRADE_ADMISSION_WRITE = 'trade.admission.write'
export const TRADE_FULFILLMENT_WRITE = 'trade.fulfillment.write'
export const TRADE_RETURN_WRITE = 'trade.return.write'
export const TRADE_ADMIN_EVIDENCE_READ = 'trade.admin.evidence.read'
export const TRADE_ADMIN_JOBS_READ = 'trade.admin.jobs.read'

export type IdentityClaims = {
  tenantId: string
  scopes: string[]
  audience: string[]
}

export type AuthState = {
  ready: boolean
  authenticated: boolean
  displayName: string
  subject: string
  tenantId: string
  scopes: string[]
  audience: string[]
  error?: string
  redirecting: boolean
  hasScope: (scope: string) => boolean
  hasAnyScope: (scopes: string[]) => boolean
  login: (returnTo?: string) => Promise<void>
  completeLogin: () => Promise<string>
  logout: () => Promise<void>
}

export function permits(scopes: string[], scope: string) {
  return scopes.includes('*') || scopes.includes(scope)
}

export const unauthenticatedState: AuthState = {
  ready: false,
  authenticated: false,
  displayName: '',
  subject: '',
  tenantId: '',
  scopes: [],
  audience: [],
  redirecting: false,
  hasScope: () => false,
  hasAnyScope: () => false,
  login: async () => undefined,
  completeLogin: async () => '/',
  logout: async () => undefined,
}

const TEST_SCOPES = [
  TRADE_ADMIN,
  TRADE_EVENTS_REPLAY,
  TRADE_RECONCILIATION_QUERY,
  TRADE_ADMISSION_WRITE,
  TRADE_FULFILLMENT_WRITE,
  TRADE_RETURN_WRITE,
]

export const testAuthState: AuthState = {
  ready: true,
  authenticated: true,
  displayName: '测试运营',
  subject: 'trade-console-test',
  tenantId: '900001',
  scopes: TEST_SCOPES,
  audience: ['transaction-center'],
  redirecting: false,
  hasScope: (scope) => permits(TEST_SCOPES, scope),
  hasAnyScope: (scopes) => scopes.some((scope) => permits(TEST_SCOPES, scope)),
  login: async () => undefined,
  completeLogin: async () => '/',
  logout: async () => undefined,
}

export const AuthStateContext = createContext<AuthState>(unauthenticatedState)

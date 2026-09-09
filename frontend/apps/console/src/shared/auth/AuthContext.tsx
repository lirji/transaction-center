import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { runtimeConfig } from '../config/runtime'
import { setAccessTokenProvider, setUnauthorizedHandler } from '../api/client'
import { AuthStateContext, permits, testAuthState, type AuthState, type IdentityClaims } from './auth-context'
import { audienceFromPayload, decodeJwtPayload, scopesFromPayload, tenantIdFromPayload } from './claims'
import { completeOidcRedirect, returnToFromState } from './oidcCallback'
import { sanitizeReturnTo } from './tenantSelection'

function claimsFromUser(user: User | null): IdentityClaims {
  const payload = user?.access_token ? decodeJwtPayload(user.access_token) : {}
  return {
    tenantId: tenantIdFromPayload(payload),
    scopes: scopesFromPayload(payload),
    audience: audienceFromPayload(payload),
  }
}

function TestAuthProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    setAccessTokenProvider(() => 'test-token')
    setUnauthorizedHandler(() => undefined)
  }, [])
  return <AuthStateContext.Provider value={testAuthState}>{children}</AuthStateContext.Provider>
}

export function AuthProvider({ children }: { children: ReactNode }) {
  if (runtimeConfig.authMode === 'TEST') {
    return <TestAuthProvider>{children}</TestAuthProvider>
  }
  return <OidcAuthProvider>{children}</OidcAuthProvider>
}

function OidcAuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const navigateRef = useRef(navigate)
  navigateRef.current = navigate
  const location = useLocation()
  const queryClient = useQueryClient()
  const extraQueryParams: Record<string, string> = { audience: runtimeConfig.oidcAudience }
  if (runtimeConfig.oidcOrganization) extraQueryParams.organization = runtimeConfig.oidcOrganization
  const manager = useMemo(() => new UserManager({
    authority: runtimeConfig.oidcAuthority,
    client_id: runtimeConfig.oidcClientId,
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/login`,
    response_type: 'code',
    scope: runtimeConfig.oidcScope,
    extraQueryParams,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
    monitorSession: false,
  }), [extraQueryParams.audience, extraQueryParams.organization])
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(false)
  const [redirecting, setRedirecting] = useState(false)
  const [error, setError] = useState<string>()
  const signingOut = useRef(false)

  const applyUser = useCallback((next: User | null) => {
    setUser(next)
    setAccessTokenProvider(() => next?.access_token)
    setReady(true)
  }, [])

  useEffect(() => {
    let active = true
    const initialize = async () => {
      try {
        if (window.location.pathname === '/auth/callback') {
          setReady(true)
          return
        }
        const current = await manager.getUser()
        if (!active) return
        applyUser(current && !current.expired ? current : null)
      } catch (cause) {
        if (!active) return
        setError(cause instanceof Error ? cause.message : 'OIDC 登录失败')
        setReady(true)
      }
    }
    const onLoaded = (next: User) => applyUser(next)
    const onExpired = () => applyUser(null)
    manager.events.addUserLoaded(onLoaded)
    manager.events.addAccessTokenExpired(onExpired)
    setUnauthorizedHandler(() => {
      if (signingOut.current) return
      signingOut.current = true
      applyUser(null)
      queryClient.clear()
      void manager.removeUser()
      const path = window.location.pathname
      if (path === '/login' || path === '/auth/callback') return
      const returnTo = `${path}${window.location.search}`
      navigateRef.current(`/login?returnTo=${encodeURIComponent(sanitizeReturnTo(returnTo))}`, { replace: true })
    })
    void initialize()
    return () => {
      active = false
      manager.events.removeUserLoaded(onLoaded)
      manager.events.removeAccessTokenExpired(onExpired)
      setUnauthorizedHandler(() => undefined)
    }
  }, [manager, applyUser, queryClient])

  const login = useCallback(async (returnTo = '/') => {
    signingOut.current = false
    setError(undefined)
    setRedirecting(true)
    try {
      await manager.signinRedirect({ state: { returnTo: sanitizeReturnTo(returnTo) } })
    } catch (cause) {
      setRedirecting(false)
      setError(cause instanceof Error ? cause.message : '无法连接统一身份服务。请确认 Casdoor 已启动，且已签发 aud=transaction-center、含 trade.admin 的应用。')
    }
  }, [manager])

  const completeLogin = useCallback(async () => {
    signingOut.current = false
    const next = await completeOidcRedirect(manager)
    applyUser(next)
    return returnToFromState(next.state)
  }, [manager, applyUser])

  const logout = useCallback(async () => {
    signingOut.current = true
    queryClient.clear()
    await manager.signoutRedirect()
  }, [manager, queryClient])

  const claims = useMemo(() => claimsFromUser(user), [user])
  const scopes = claims.scopes
  const hasScope = useCallback((scope: string) => permits(scopes, scope), [scopes])
  const hasAnyScope = useCallback((required: string[]) => required.some((scope) => permits(scopes, scope)), [scopes])

  const value = useMemo<AuthState>(() => ({
    ready,
    authenticated: Boolean(user && !user.expired),
    displayName: String(user?.profile.name ?? user?.profile.preferred_username ?? '交易运营'),
    subject: user?.profile.sub ?? '',
    tenantId: claims.tenantId,
    scopes,
    audience: claims.audience,
    error,
    redirecting,
    hasScope,
    hasAnyScope,
    login: (returnTo = `${location.pathname}${location.search}`) => login(returnTo),
    completeLogin,
    logout,
  }), [
    ready, user, claims, scopes, error, redirecting, hasScope, hasAnyScope,
    login, completeLogin, logout, location.pathname, location.search,
  ])

  return <AuthStateContext.Provider value={value}>{children}</AuthStateContext.Provider>
}

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, type RenderOptions } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useEffect, type ReactElement, type ReactNode } from 'react'
import { AuthStateContext, permits, testAuthState } from '../shared/auth/auth-context'
import { setAccessTokenProvider, setUnauthorizedHandler } from '../shared/api/client'

export function renderWithApp(ui: ReactElement, options?: { route?: string; path?: string; scopes?: string[] } & Omit<RenderOptions, 'wrapper'>) {
  const { route = '/', path, scopes, ...renderOptions } = options ?? {}
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const auth = scopes
    ? {
        ...testAuthState,
        scopes,
        hasScope: (scope: string) => permits(scopes, scope),
        hasAnyScope: (required: string[]) => required.some((scope) => permits(scopes, scope)),
      }
    : testAuthState

  function Wrapper({ children }: { children: ReactNode }) {
    useEffect(() => {
      setAccessTokenProvider(() => 'test-token')
      setUnauthorizedHandler(() => undefined)
    }, [])
    const inner = path ? <Routes><Route path={path} element={children} /></Routes> : children
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[route]}>
          <AuthStateContext.Provider value={auth}>{inner}</AuthStateContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>
    )
  }
  return render(ui, { wrapper: Wrapper, ...renderOptions })
}

import { type ReactElement } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthStateContext, unauthenticatedState, type AuthState } from '../../shared/auth/auth-context'
import { LoginPage } from './LoginPage'

function renderLogin(ui: ReactElement, auth: AuthState, route = '/login') {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthStateContext.Provider value={auth}>
        <Routes>
          <Route path="/login" element={ui} />
          <Route path="/" element={<p>已进入工作台</p>} />
        </Routes>
      </AuthStateContext.Provider>
    </MemoryRouter>,
  )
}

function guest(overrides: Partial<AuthState> = {}): AuthState {
  return {
    ...unauthenticatedState,
    ready: true,
    ...overrides,
  }
}

describe('LoginPage', () => {
  it('shows Casdoor login and identity requirements', () => {
    renderLogin(<LoginPage />, guest())
    expect(screen.getByRole('heading', { name: '登录交易中枢' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '使用 Casdoor 登录' })).toBeEnabled()
    expect(screen.getAllByText(/trade\.admin/).length).toBeGreaterThan(0)
    expect(screen.getByText('登录与权限帮助')).toBeInTheDocument()
  })

  it('keeps the operational aside visible', () => {
    renderLogin(<LoginPage />, guest())
    expect(screen.getByRole('heading', { name: '交易运营控制台' })).toBeInTheDocument()
  })

  it('redirects an authenticated operator to the workspace', () => {
    renderLogin(<LoginPage />, guest({ authenticated: true }))
    expect(screen.getByText('已进入工作台')).toBeInTheDocument()
  })

  it('surfaces auth errors and calls login with the return path', async () => {
    const login = vi.fn(async () => undefined)
    renderLogin(<LoginPage />, guest({ error: '无法连接统一身份服务', login }), '/login?returnTo=/orders')
    expect(screen.getByRole('alert')).toHaveTextContent('无法连接统一身份服务')
    await userEvent.click(screen.getByRole('button', { name: '使用 Casdoor 登录' }))
    expect(login).toHaveBeenCalledWith('/orders')
  })

  it('disables the button while redirecting', () => {
    renderLogin(<LoginPage />, guest({ redirecting: true }))
    expect(screen.getByRole('button', { name: '正在跳转 Casdoor…' })).toBeDisabled()
  })
})

import { screen } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import { AppShell } from './AppShell'
import { renderWithApp } from '../test/render'
import { TRADE_ADMIN, TRADE_ADMIN_JOBS_READ } from '../shared/auth/auth-context'

function shell() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<p>home</p>} />
      </Route>
    </Routes>
  )
}

describe('AppShell jobs nav', () => {
  afterEach(() => {
    /* no fetch */
  })

  it('hides jobs without trade.admin.jobs.read', () => {
    renderWithApp(shell(), { scopes: [TRADE_ADMIN] })
    expect(screen.queryByRole('link', { name: '任务' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '订单' })).toBeInTheDocument()
  })

  it('shows jobs when both scopes are present', () => {
    renderWithApp(shell(), { scopes: [TRADE_ADMIN, TRADE_ADMIN_JOBS_READ] })
    expect(screen.getByRole('link', { name: '任务' })).toBeInTheDocument()
  })
})

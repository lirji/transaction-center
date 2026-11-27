import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReturnsPage } from './ReturnsPage'
import { renderWithApp } from '../../test/render'
import { TRADE_ADMIN } from '../../shared/auth/auth-context'
import { adminPage, json } from '../../test/admin-fixtures'

describe('ReturnsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input) => /\/admin\/numbered-(pages|operations)\//.test(String(input))
      ? json({}, 404) : json(adminPage([]))))
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('disables return writes without trade.return.write', async () => {
    renderWithApp(<ReturnsPage />, { scopes: [TRADE_ADMIN] })
    expect(await screen.findByText('当前令牌不能申请或验收退货')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '申请' })).toBeDisabled()
  })
})

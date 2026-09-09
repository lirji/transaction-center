import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FulfillmentPage } from './FulfillmentPage'
import { renderWithApp } from '../../test/render'
import { TRADE_ADMIN } from '../../shared/auth/auth-context'

describe('FulfillmentPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('disables delivery when the token lacks trade.fulfillment.write', async () => {
    renderWithApp(<FulfillmentPage />, { scopes: [TRADE_ADMIN] })
    expect(await screen.findByText('当前令牌不能声明履约')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '声明已交付' })).toBeDisabled()
  })

  it('prefills orderNo from the query string', () => {
    renderWithApp(<FulfillmentPage />, { route: '/fulfillment?orderNo=ORD-88' })
    expect(screen.getByLabelText('orderNo')).toHaveValue('ORD-88')
  })

  it('asks for confirmation before calling the fulfillment API', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ resourceNo: 'FUL-1', status: 'DELIVERED', version: 1 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.mocked(fetch).mockImplementation(fetchMock)
    renderWithApp(<FulfillmentPage />)
    await userEvent.type(screen.getByLabelText('orderNo'), 'ORD-1')
    await userEvent.type(screen.getByLabelText('sourceRequestId'), 'src-1')
    await userEvent.type(screen.getByLabelText('deliveryRef'), 'del-1')
    await userEvent.click(screen.getByRole('button', { name: '声明已交付' }))
    expect(screen.getByText('确认整单已交付')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '确认交付' }))
    expect(fetchMock).toHaveBeenCalled()
  })
})

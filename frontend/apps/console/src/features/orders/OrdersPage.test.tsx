import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { OrdersPage } from './OrdersPage'
import { renderWithApp } from '../../test/render'
import { adminOrder, adminPage, json } from '../../test/admin-fixtures'

describe('OrdersPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('pages with nextAfter and resets after when filters change', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockResolvedValue(json(adminPage([adminOrder()], '15', true)))
    renderWithApp(<OrdersPage />, { route: '/orders' })
    expect(await screen.findByText('ORD-1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => {
      expect(fetchMock.mock.calls.some((call) => String(call[0]).includes('after=15'))).toBe(true)
    })
    await userEvent.type(screen.getByLabelText('订单号'), 'ORD-9')
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
    await waitFor(() => {
      const urls = fetchMock.mock.calls.map((call) => String(call[0]))
      expect(urls.some((url) => url.includes('orderNo=ORD-9') && !url.includes('after=15'))).toBe(true)
    })
  })
})

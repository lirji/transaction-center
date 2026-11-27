import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RefundsPage } from './RefundsPage'
import { renderWithApp } from '../../test/render'

function json(data: unknown) {
  return new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('RefundsPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the queue as read-only without approve actions', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (/\/admin\/numbered-(pages|operations)\//.test(String(input))) return new Response(null, { status: 404 })
      const url = String(input)
      if (url.includes('refund-policy')) return json({ currency: 'CNY', autoApproveLimit: 0, version: 0 })
      return json([{
        cursor: '3',
        refundNo: 'RF-9',
        orderNo: 'ORD-1',
        amount: 199,
        currency: 'CNY',
        status: 'PENDING_APPROVAL',
        approvalRef: null,
        approvalDeadline: '2026-09-09T14:00:00Z',
        version: 1,
      }])
    })
    renderWithApp(<RefundsPage />, { route: '/refunds' })
    expect(await screen.findByText('RF-9')).toBeInTheDocument()
    expect(screen.getByText('2026/09/09 22:00:00')).toBeInTheDocument()
    expect(screen.getByText('不能在此批准退款')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /批准|驳回|同意退款/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'RF-9' })).toHaveAttribute('href', '/refunds/RF-9')
  })

  it('puts status=ALL on the request when selected', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input) => {
      if (/\/admin\/numbered-(pages|operations)\//.test(String(input))) return new Response(null, { status: 404 })
      if (String(input).includes('refund-policy')) return json({ currency: 'CNY', autoApproveLimit: 0, version: 0 })
      return json([])
    })
    renderWithApp(<RefundsPage />, { route: '/refunds' })
    await screen.findByText('筛选')
    await userEvent.click(screen.getByRole('combobox', { name: '状态' }))
    await userEvent.click(screen.getByRole('option', { name: '全部' }))
    await waitFor(() => {
      expect(fetchMock.mock.calls.some((call) => String(call[0]).includes('status=ALL'))).toBe(true)
    })
  })

  it('loads the policy on demand and preserves its currency when editing the amount', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockImplementation(async (input) => {
      if (/\/admin\/numbered-(pages|operations)\//.test(String(input))) return new Response(null, { status: 404 })
      if (String(input).includes('refund-policy')) return json({ currency: 'USD', autoApproveLimit: 200, version: 1 })
      return json([])
    })
    renderWithApp(<RefundsPage />, { route: '/refunds' })
    await screen.findByText('队列为空')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('refund-policy'))).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: '退款政策' }))
    expect(await screen.findByLabelText('币种')).toHaveValue('USD')
    const amount = screen.getByLabelText('免审额度（最小单位）')
    await userEvent.clear(amount)
    await userEvent.type(amount, '300')
    await userEvent.click(screen.getByRole('button', { name: '保存政策' }))
    await waitFor(() => {
      const write = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH')
      expect(write?.[1]?.body).toBe(JSON.stringify({ currency: 'USD', autoApproveLimit: 300, expectedVersion: 1 }))
    })
  })

  it('shows an error when the refund policy cannot be loaded', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (/\/admin\/numbered-(pages|operations)\//.test(String(input))) return new Response(null, { status: 404 })
      if (String(input).includes('refund-policy')) {
        return new Response(JSON.stringify({ code: 'DOWN', message: '政策不可用', traceId: 't', retryable: false }), {
          status: 503,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return json([])
    })
    renderWithApp(<RefundsPage />)
    await userEvent.click(screen.getByRole('button', { name: '退款政策' }))
    expect(await screen.findByText('入口暂不可用')).toBeInTheDocument()
    expect(screen.getByText(/政策不可用/)).toBeInTheDocument()
  })
})

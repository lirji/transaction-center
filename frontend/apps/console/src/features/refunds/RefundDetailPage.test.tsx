import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RefundDetailPage } from './RefundDetailPage'
import { renderWithApp } from '../../test/render'
import { json } from '../../test/admin-fixtures'

describe('RefundDetailPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('is read-only without approve or reject', async () => {
    vi.mocked(fetch).mockResolvedValue(json({
      refundNo: 'RF-9',
      orderNo: 'ORD-1',
      memberId: '11',
      amount: 199,
      currency: 'CNY',
      status: 'PENDING_APPROVAL',
      approvalRef: null,
      approvalDeadline: null,
      policyVersion: 0,
      version: 1,
      createdAt: '2026-09-08T00:00:00Z',
      observedAt: '2026-09-08T12:00:00Z',
      items: [{ componentType: 'ORDER_ITEM', orderItemId: '21', quantity: 1, amount: 199 }],
      processingReasonCode: null,
    }))
    renderWithApp(<RefundDetailPage />, { route: '/refunds/RF-9', path: '/refunds/:refundNo' })
    expect(await screen.findByText('RF-9')).toBeInTheDocument()
    expect(screen.getByText('不能在此批准退款')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /批准|驳回/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '批准' })).not.toBeInTheDocument()
  })
})

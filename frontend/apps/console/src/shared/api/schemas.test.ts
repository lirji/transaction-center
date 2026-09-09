import { describe, expect, it } from 'vitest'
import { adminOrderViewSchema, memberSchema, parseArray, parsePage, refundQueueItemSchema } from './schemas'

describe('memberSchema', () => {
  it('keeps identifiers as strings', () => {
    const member = parseArray(memberSchema, [{
      memberId: '12',
      displayName: '演示会员',
      status: 'ACTIVE',
      registeredAt: '2026-01-01T00:00:00Z',
      everPaid: false,
      version: 3,
    }])[0]
    expect(member.memberId).toBe('12')
    expect(member.version).toBe(3)
  })

  it('rejects mocked extra fields that break the contract', () => {
    expect(() => parseArray(memberSchema, [{ memberId: 'x' }])).toThrow()
  })
})

describe('refundQueueItemSchema', () => {
  it('parses a queue row without inventing approval actions', () => {
    const row = refundQueueItemSchema.parse({
      cursor: '8',
      refundNo: 'RF-1',
      orderNo: 'ORD-1',
      amount: 199,
      currency: 'CNY',
      status: 'PENDING_APPROVAL',
      approvalRef: null,
      approvalDeadline: null,
      version: 1,
    })
    expect(row.status).toBe('PENDING_APPROVAL')
  })
})

describe('parsePage', () => {
  it('reads nextAfter as a string cursor', () => {
    const page = parsePage(adminOrderViewSchema, {
      items: [{
        orderId: '1',
        orderNo: 'ORD-1',
        memberId: '11',
        quoteId: 'Q-1',
        merchantNo: 'M-1',
        warehouseId: null,
        status: 'PAID',
        financialStatus: 'NO_PAYMENT_REQUIRED',
        payableAmount: 0,
        currency: 'CNY',
        expiresAt: '2026-09-09T00:00:00Z',
        paidAt: null,
        completedAt: null,
        createdAt: '2026-09-08T00:00:00Z',
        version: 1,
        paymentStartedAt: null,
        cancelledAt: null,
        updatedAt: '2026-09-08T00:00:00Z',
      }],
      nextAfter: '1',
      hasMore: true,
      observedAt: '2026-09-08T12:00:00Z',
    })
    expect(page.nextAfter).toBe('1')
    expect(page.items[0]?.paidAt).toBeNull()
  })
})

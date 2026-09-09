export function adminPage<T>(items: T[], nextAfter: string | null = null, hasMore = Boolean(nextAfter)) {
  return { items, nextAfter, hasMore, observedAt: '2026-09-08T12:00:00.000Z' }
}

export function adminOrder(overrides: Record<string, unknown> = {}) {
  return {
    orderId: '1',
    orderNo: 'ORD-1',
    memberId: '11',
    quoteId: 'Q-1',
    merchantNo: 'M-1',
    warehouseId: '2',
    status: 'PAID',
    financialStatus: 'PAID',
    payableAmount: 199,
    currency: 'CNY',
    expiresAt: '2026-09-09T00:00:00Z',
    paidAt: '2026-09-08T01:00:00Z',
    completedAt: null,
    createdAt: '2026-09-08T00:00:00Z',
    version: 1,
    paymentStartedAt: '2026-09-08T00:30:00Z',
    cancelledAt: null,
    updatedAt: '2026-09-08T01:00:00Z',
    ...overrides,
  }
}

export function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
}

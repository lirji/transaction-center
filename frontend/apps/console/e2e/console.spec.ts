import { test, expect } from '@playwright/test'

test.describe('transaction console', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/trade/**', async (route) => {
      const url = route.request().url()
      if (url.includes('/admin/merchant') && route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ merchantId: '1', merchantNo: 'M-1', name: '演示商户', status: 'ACTIVE', version: 1 }),
        })
        return
      }
      if (url.includes('/admin/members')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([{
            memberId: '11',
            displayName: '林若',
            status: 'ACTIVE',
            registeredAt: '2026-01-01T00:00:00Z',
            everPaid: false,
            version: 2,
          }]),
        })
        return
      }
      if (url.includes('/admin/refunds')) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
        return
      }
      if (url.includes('/admin/refund-policy')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ currency: 'CNY', autoApproveLimit: 0, version: 0 }),
        })
        return
      }
      if (url.match(/\/admin\/orders(\?|$)/) || url.includes('/admin/payments') || url.includes('/admin/payment-receipts') || url.includes('/admin/quotes') || url.match(/\/admin\/returns(\?|$)/) || url.includes('/admin/jobs')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            items: url.match(/\/admin\/orders(\?|$)/) ? [{
              orderId: '1',
              orderNo: 'ORD-1',
              memberId: '11',
              quoteId: 'Q-1',
              merchantNo: 'M-1',
              warehouseId: null,
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
            }] : [],
            nextAfter: null,
            hasMore: false,
            observedAt: '2026-09-08T12:00:00Z',
          }),
        })
        return
      }
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 'NOT_FOUND', message: 'not mocked', traceId: 't', retryable: false }) })
    })
  })

  test('shows the admin shell and members from the API', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '交易运营工作台' })).toBeVisible()
    await page.getByRole('link', { name: '会员' }).click()
    await expect(page.getByRole('heading', { name: '会员' })).toBeVisible()
    await expect(page.getByText('林若')).toBeVisible()
  })

  test('refund queue has no approve button', async ({ page }) => {
    await page.goto('/refunds')
    await expect(page.getByText('不能在此批准退款')).toBeVisible()
    await expect(page.getByRole('button', { name: '批准' })).toHaveCount(0)
  })

  test('order list is reachable from the shell', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: '订单', exact: true }).click()
    await expect(page.getByRole('heading', { name: '订单' })).toBeVisible()
    await expect(page.getByText('ORD-1')).toBeVisible()
  })
})

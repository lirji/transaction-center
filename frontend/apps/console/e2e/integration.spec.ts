import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

const data = JSON.parse(readFileSync(process.env.TRADE_E2E_MANIFEST!, 'utf8')) as Record<string, string>

async function login(page: Page, role = 'admin', path = '/orders') {
  await page.goto(path)
  await page.getByRole('button', { name: '使用 Casdoor 登录' }).click()
  await expect(page.getByRole('heading', { name: '隔离测试身份' })).toBeVisible()
  await page.getByLabel('测试角色').selectOption(role)
  await page.getByRole('button', { name: '签发测试授权码' }).click()
  await page.waitForURL((url) => !url.pathname.includes('/auth/') && !url.pathname.includes('/login') && url.port === process.env.TRADE_E2E_UI_PORT)
}

// 请求经真实同源Vite代理到Spring，令牌来自本次OIDC回调，不拦截或伪造业务响应。
async function api(page: Page, path: string, init: { method?: string; body?: string; headers?: Record<string, string> } = {}) {
  return page.evaluate(async ({ path, init }) => {
    const key = Object.keys(sessionStorage).find((key) => key.startsWith('oidc.user:'))
    const user = key ? JSON.parse(sessionStorage.getItem(key)!) : undefined
    const response = await fetch('/api/v1/trade' + path, { ...init, headers: {
      Authorization: `Bearer ${user?.access_token}`, 'Content-Type': 'application/json', ...init.headers,
    } })
    const text = await response.text()
    return { status: response.status, body: text ? JSON.parse(text) : null, etag: response.headers.get('ETag') }
  }, { path, init })
}

test('OIDC login and persisted order/payment/refund/quote drilldown', async ({ page }) => {
  await login(page)
  await expect(page.getByRole('link', { name: data.paidOrder, exact: true })).toBeVisible()
  await page.getByRole('link', { name: data.paidOrder, exact: true }).click()
  await expect(page.getByRole('heading', { name: data.paidOrder, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '支付意图', exact: true }).click()
  await page.getByRole('link', { name: data.paymentNo, exact: true }).first().click()
  await expect(page.getByRole('heading', { name: data.paymentNo, exact: true })).toBeVisible()
  await page.goto(`/refunds/${data.refundNo}`)
  await expect(page.getByRole('heading', { name: data.refundNo, exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /批准|驳回/ })).toHaveCount(0)
  await page.goto(`/quotes/${data.quoteId}`)
  await expect(page.getByRole('heading', { name: data.quoteId, exact: true })).toBeVisible()
  await page.goto(`/receipts/${data.abnormalReceiptNo}`)
  await expect(page.getByRole('heading', { name: data.abnormalReceiptNo, exact: true })).toBeVisible()
  const first = await api(page, '/admin/orders?limit=1')
  expect(first.status).toBe(200)
  expect(first.body.hasMore).toBe(true)
  const next = await api(page, `/admin/orders?limit=1&after=${first.body.nextAfter}`)
  expect(next.body.items[0].orderNo).not.toBe(first.body.items[0].orderNo)
  expect((await api(page, `/admin/refunds?orderNo=${data.paidOrder}&status=ALL`)).body.length).toBe(4)
})

test('approval deadline and receipt confirmation use half-open server time windows', async ({ page }) => {
  await login(page)
  const refund = await api(page, `/admin/refunds/${data.refundNo}`)
  expect(refund.status).toBe(200)
  const receipt = await api(page, `/admin/payment-receipts/${data.abnormalReceiptNo}`)
  expect(receipt.status).toBe(200)
  const approval = new Date(refund.body.approvalDeadline)
  const confirmed = new Date(receipt.body.confirmedAt)
  const window = (field: string, date: Date, fromOffset: number, toOffset: number) => new URLSearchParams({
    [`${field}From`]: new Date(date.getTime() + fromOffset).toISOString(),
    [`${field}To`]: new Date(date.getTime() + toOffset).toISOString(),
  })
  const refundInside = await api(page, `/admin/refunds?${window('approvalDeadline', approval, 0, 1)}`)
  expect(refundInside.status).toBe(200)
  expect(refundInside.body.map((row: { refundNo: string }) => row.refundNo)).toContain(data.refundNo)
  const refundOutside = await api(page, `/admin/refunds?${window('approvalDeadline', approval, -1, 0)}`)
  expect(refundOutside.status).toBe(200)
  expect(refundOutside.body.map((row: { refundNo: string }) => row.refundNo)).not.toContain(data.refundNo)
  const receiptInside = await api(page, `/admin/payment-receipts?abnormalOnly=true&${window('confirmed', confirmed, 0, 1)}`)
  expect(receiptInside.status).toBe(200)
  expect(receiptInside.body.items.map((row: { receiptNo: string }) => row.receiptNo)).toContain(data.abnormalReceiptNo)
  const receiptOutside = await api(page, `/admin/payment-receipts?abnormalOnly=true&${window('confirmed', confirmed, -1, 0)}`)
  expect(receiptOutside.status).toBe(200)
  expect(receiptOutside.body.items.map((row: { receiptNo: string }) => row.receiptNo)).not.toContain(data.abnormalReceiptNo)
})

test('latest orders keep the same order across cursor pages', async ({ page }) => {
  await login(page)
  const full = await api(page, '/admin/orders?sort=CREATED_DESC&limit=100')
  expect(full.status).toBe(200)
  const rows: { orderId: string; orderNo: string; createdAt: string }[] = full.body.items
  expect(rows.length).toBeGreaterThan(1)
  const expected = [...rows].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || (BigInt(a.orderId) > BigInt(b.orderId) ? -1 : 1))
  expect(rows.map(row => row.orderNo)).toEqual(expected.map(row => row.orderNo))
  let after = '0'
  for (const row of expected) {
    const current = await api(page, `/admin/orders?sort=CREATED_DESC&limit=1&after=${after}`)
    expect(current.status).toBe(200)
    expect(current.body.items[0].orderNo).toBe(row.orderNo)
    after = current.body.nextAfter
  }
  expect(after).toBeNull()
  expect((await api(page, '/admin/orders?sort=member_id')).status).toBe(400)
})

test('real member freeze/unfreeze writes and tenant-safe database reads', async ({ page }) => {
  await login(page, 'admin', '/members')
  const row = page.getByRole('row').filter({ hasText: '联调会员' })
  await row.getByRole('button', { name: '冻结', exact: true }).click()
  await page.getByLabel('原因').fill('真实接口联调冻结')
  await page.getByRole('button', { name: '提交', exact: true }).click()
  await expect(row.getByRole('cell', { name: '冻结', exact: true })).toBeVisible()
  await row.getByRole('button', { name: '解冻', exact: true }).click()
  await page.getByLabel('原因').fill('真实接口联调恢复')
  await page.getByRole('button', { name: '提交', exact: true }).click()
  await expect(row.getByRole('cell', { name: '正常', exact: true })).toBeVisible()
})

test('return inspection uses real ETag, idempotency and inventory flow', async ({ page }) => {
  await login(page, 'admin', '/returns')
  await page.locator('form').filter({ has: page.getByRole('button', { name: '读取', exact: true }) }).getByLabel('returnNo', { exact: true }).fill(data.openReturn)
  await page.getByRole('button', { name: '读取', exact: true }).click()
  await expect(page.getByText('尚未验收', { exact: true })).toBeVisible()
  await page.getByLabel('result', { exact: true }).selectOption('ACCEPTED')
  await page.getByLabel('restockQuantity', { exact: true }).fill('1')
  await page.getByLabel('evidenceRef', { exact: true }).fill('test:browser-inspection')
  await page.getByRole('button', { name: '提交验收', exact: true }).click()
  const responsePromise = page.waitForResponse((r) => r.url().includes(data.openReturn) && r.request().method() === 'POST')
  await page.getByRole('button', { name: '确认验收', exact: true }).click()
  const response = await responsePromise
  expect(response.status()).toBe(200)
  const request = response.request()
  const headers = request.headers()
  expect(headers['if-match']).toBe('"0"')
  expect(headers['idempotency-key']).toBeTruthy()
  await expect(page.getByText('验收数量', { exact: true })).toBeVisible()
  const replay = await api(page, new URL(request.url()).pathname.replace('/api/v1/trade', ''), {
    method: 'POST', body: request.postData()!, headers: {
      'If-Match': headers['if-match'], 'Idempotency-Key': headers['idempotency-key'],
    },
  })
  expect(replay.status).toBe(200)
  const detail = await api(page, `/admin/returns/${data.openReturn}`)
  expect(detail.etag).toBe('"1"')
  expect(detail.body.inspection.restockQuantity).toBe(1)
})

test('diagnostic scopes show evidence and jobs; ordinary admin cannot read them', async ({ page }) => {
  await login(page, 'diagnostic', `/orders/${data.paidOrder}`)
  await expect(page.getByRole('heading', { name: data.paidOrder, exact: true })).toBeVisible()
  expect((await api(page, `/admin/orders/${data.paidOrder}/referral-evidence`)).status).toBe(200)
  await page.goto('/jobs')
  await expect(page.getByRole('heading', { name: '任务', exact: true })).toBeVisible()
  expect((await api(page, '/admin/jobs')).body.items.length).toBeGreaterThan(0)
})

test('ordinary admin scope denial and genuine rate-limit response', async ({ page }) => {
  await login(page)
  expect((await api(page, '/admin/jobs')).status).toBe(403)
  expect((await api(page, `/admin/orders/${data.paidOrder}/referral-evidence`)).status).toBe(403)
  expect((await api(page, '/admin/orders', { headers: { Authorization: 'Bearer invalid' } })).status).toBe(401)
  const statuses = await page.evaluate(async () => {
    const key = Object.keys(sessionStorage).find((key) => key.startsWith('oidc.user:'))!
    const token = JSON.parse(sessionStorage.getItem(key)!).access_token
    return Promise.all(Array.from({ length: 500 }, async () => {
      const response = await fetch('/api/v1/trade/admin/orders?limit=1', { headers: { Authorization: `Bearer ${token}` } })
      await response.text()
      return response.status
    }))
  })
  expect(statuses).toContain(429)
})

test('another tenant sees no seeded orders and cannot drill into them', async ({ page }) => {
  await login(page, 'other')
  expect((await api(page, '/admin/orders')).body.items).toEqual([])
  expect((await api(page, `/admin/orders/${data.paidOrder}`)).status).toBe(404)
})

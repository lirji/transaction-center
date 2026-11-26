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

test('product SKU, member registration, and available inventory filters read past the first page', async ({ page }) => {
  await login(page, 'admin', '/products')
  await expect(page.getByRole('cell', { name: 'INTEGRATION-P', exact: true })).toBeVisible()
  const baseline = await api(page, '/admin/products?limit=1')
  expect(baseline.status).toBe(200)
  expect(baseline.body[0]).not.toHaveProperty('productNo')
  const search = await api(page, '/admin/products/search?limit=1&skuNo=INTEGRATION-ALT-SECOND&status=DRAFT')
  expect(search.status).toBe(200)
  expect(search.body).toEqual([expect.objectContaining({ productId: data.alternateProduct, productNo: 'INTEGRATION-ALT', unitPrice: 900 })])
  expect(search.body[0].productId).not.toBe(baseline.body[0].productId)
  await page.getByLabel('按 SKU 编码').fill('INTEGRATION-ALT-SECOND')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'INTEGRATION-ALT', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'INTEGRATION-P', exact: true })).toHaveCount(0)

  const members = await api(page, `/admin/members?memberId=${data.memberId}`)
  expect(members.status).toBe(200)
  const registeredAt = Date.parse(members.body[0].registeredAt)
  const at = (offset: number) => new Date(registeredAt + offset).toISOString()
  expect((await api(page, `/admin/members?memberId=${data.memberId}&registeredFrom=${at(0)}&registeredTo=${at(1)}`)).body).toHaveLength(1)
  expect((await api(page, `/admin/members?memberId=${data.memberId}&registeredFrom=${at(-1)}&registeredTo=${at(0)}`)).body).toEqual([])
  expect((await api(page, `/admin/members?registeredFrom=${at(0)}`)).status).toBe(400)

  const stockFilters = `warehouseId=${data.warehouseId}&skuId=${data.skuId}`
  const stock = await api(page, `/admin/inventories?${stockFilters}`)
  expect(stock.status).toBe(200)
  expect(stock.body).toHaveLength(1)
  const quantity = stock.body[0].available
  expect(quantity).toBeGreaterThan(0)
  expect((await api(page, `/admin/inventories?${stockFilters}&maxAvailable=${quantity}`)).body).toEqual(stock.body)
  expect((await api(page, `/admin/inventories?${stockFilters}&maxAvailable=0`)).body).toEqual([])
  expect((await api(page, '/admin/inventories?maxAvailable=-1')).status).toBe(400)
})

test('operations totals reconcile with paged facts for the same window, currency and tenant', async ({ page }) => {
  await login(page, 'admin', '/')
  const paid = await api(page, `/admin/orders/${data.paidOrder}`)
  const seedTime = Date.parse(paid.body.createdAt)
  const beijing = (epoch: number) => new Date(epoch + 8 * 3_600_000).toISOString().slice(0, 16)
  const from = seedTime - 3_600_000
  const to = seedTime + 3_600_000
  const makeRange = (start: number, end: number, currency: string) => new URLSearchParams({
    createdFrom: new Date(start).toISOString(), createdTo: new Date(end).toISOString(), currency,
  })
  const cny = makeRange(from, to, 'CNY')
  const totals = await api(page, `/admin/operations/summary?${cny}`)
  expect(totals.status).toBe(200)
  expect(totals.body).toMatchObject({
    currency: 'CNY', orderCount: 4, orderPayable: '60000', paidOrderCount: 1,
    normalReceipt: '20000', abnormalReceipt: '100', normalRefund: '1000', abnormalRefund: '0', netReceipt: '19000',
  })
  const orders = await api(page, `/admin/orders?${cny}`)
  expect(orders.body.items.map((row: { orderNo: string }) => row.orderNo)).toContain(data.freeOrder)
  expect(orders.body.items.map((row: { orderNo: string }) => row.orderNo)).toContain(data.cancelledOrder)
  expect(orders.body.items.map((row: { orderNo: string }) => row.orderNo)).not.toContain(data.usdOrder)
  expect(orders.body.items.reduce((sum: number, row: { payableAmount: number }) => sum + row.payableAmount, 0)).toBe(60000)

  const normal = await api(page, `/admin/operations/financial-facts?${cny}&scope=NET&limit=1`)
  expect(normal.status).toBe(200)
  expect(normal.body.hasMore).toBe(true)
  expect(normal.body.items[0]).toMatchObject({ kind: 'NORMAL_RECEIPT', receiptNo: data.receiptNo, amount: 20000 })
  const netRefund = await api(page, `/admin/operations/financial-facts?${cny}&scope=NET&limit=1&after=${normal.body.nextAfter}`)
  expect(netRefund.body.hasMore).toBe(false)
  expect(netRefund.body.items[0]).toMatchObject({ kind: 'NORMAL_REFUND', refundNo: data.successfulRefund, amount: 1000 })

  const refundAt = Date.parse(netRefund.body.items[0].confirmedAt)
  const refundOnly = makeRange(refundAt, refundAt + 1, 'CNY')
  expect((await api(page, `/admin/operations/summary?${refundOnly}`)).body).toMatchObject({ orderCount: 0, normalReceipt: '0', normalRefund: '1000', netReceipt: '-1000' })
  const empty = makeRange(to, to + 1, 'CNY')
  expect((await api(page, `/admin/operations/summary?${empty}`)).body).toMatchObject({ orderCount: 0, normalReceipt: '0', normalRefund: '0', netReceipt: '0' })
  const usd = makeRange(from, to, 'USD')
  expect((await api(page, `/admin/operations/summary?${usd}`)).body).toMatchObject({
    orderCount: 1, orderPayable: '250', paidOrderCount: 0, normalReceipt: '0', abnormalReceipt: '210', netReceipt: '0',
  })
  expect((await api(page, '/admin/operations/summary?currency=CNY')).status).toBe(400)

  await page.getByLabel('统计起始时间（北京时间）').fill(beijing(from))
  await page.getByLabel('统计结束时间（北京时间）').fill(beijing(to))
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row', { name: /期间净收款/ })).toContainText('19,000')
  await page.getByRole('button', { name: '查看期间净收款明细' }).click()
  await expect(page.getByRole('heading', { name: '期间净收款明细' })).toBeVisible()
  await expect(page.getByRole('link', { name: data.successfulRefund, exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: data.submittedRefund, exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: '查看创建订单' }).click()
  await expect(page.getByLabel('币种', { exact: true })).toHaveValue('CNY')
  await expect(page.getByRole('link', { name: data.freeOrder, exact: true })).toBeVisible()
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
    // 隔离环境每租户每秒允许 30 次读取，用短突发覆盖真实 HTTP 限流。
    return Promise.all(Array.from({ length: 80 }, async () => {
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
  const range = new URLSearchParams({ createdFrom: new Date(Date.now() - 3_600_000).toISOString(), createdTo: new Date(Date.now() + 3_600_000).toISOString(), currency: 'CNY' })
  expect((await api(page, `/admin/operations/summary?${range}`)).body).toMatchObject({ orderCount: 0, normalReceipt: '0', netReceipt: '0' })
  expect((await api(page, `/admin/operations/financial-facts?${range}&scope=NET`)).body.items).toEqual([])
  expect((await api(page, `/admin/orders/${data.paidOrder}`)).status).toBe(404)
  expect((await api(page, '/admin/products/search?skuNo=INTEGRATION-ALT-SECOND')).body).toEqual([])
  expect((await api(page, `/admin/members?memberId=${data.memberId}`)).body).toEqual([])
  expect((await api(page, `/admin/inventories?warehouseId=${data.warehouseId}&skuId=${data.skuId}`)).body).toEqual([])
})

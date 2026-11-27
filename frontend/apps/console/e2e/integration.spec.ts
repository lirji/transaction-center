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
    const contentType = response.headers.get('Content-Type') ?? ''
    return { status: response.status, body: text && contentType.includes('json') ? JSON.parse(text) : text || null,
      contentType, etag: response.headers.get('ETag'), retryAfter: response.headers.get('Retry-After') }
  }, { path, init })
}

async function guardedRead(page: Page, path: string) {
  const result = await api(page, path)
  if (result.status !== 429 || !result.retryAfter) return result
  // 两个用例共享真实租户配额，按服务器声明等一次再校验业务响应。
  await new Promise<void>((resolve) => setTimeout(resolve, Number(result.retryAfter) * 1000))
  return api(page, path)
}

test('OIDC login and persisted order/payment/refund/quote drilldown', async ({ page }, testInfo) => {
  await login(page)
  await expect(page.getByRole('link', { name: data.paidOrder, exact: true })).toBeVisible()
  await page.getByRole('link', { name: data.paidOrder, exact: true }).click()
  await expect(page.getByRole('heading', { name: data.paidOrder, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '资金', exact: true }).click()
  await page.getByRole('link', { name: data.paymentNo, exact: true }).first().click()
  await expect(page.getByRole('heading', { name: data.paymentNo, exact: true })).toBeVisible()
  await page.goto(`/refunds/${data.refundNo}`)
  const refundNoDetail = await api(page, `/admin/refunds/${data.refundNo}`)
  expect(refundNoDetail.status).toBe(200)
  expect(refundNoDetail.body.businessNo).toMatch(/TRD-RFD-[0-9]{6}-[0-9]{10}/)
  await expect(page.getByRole('heading', { name: refundNoDetail.body.businessNo, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查看操作日志', exact: true }).click()
  const auditRequests = page.getByRole('row').filter({ hasText: '申请退款' })
  await expect(auditRequests.getByText('已提交', { exact: true })).toBeVisible()
  await expect(auditRequests.getByText('重复请求', { exact: true })).toBeVisible()
  const auditPath = `/admin/refunds/${data.refundNo}/operation-audits`
  const history = await api(page, auditPath)
  const auditFirst = await api(page, auditPath + '?limit=1')
  expect(auditFirst.status).toBe(200)
  expect(auditFirst.body.items).toEqual(history.body.items.slice(0, 1))
  const auditNext = await api(page, auditPath + '?' + new URLSearchParams({ limit: '1', cursor: auditFirst.body.nextCursor }))
  expect(auditNext.body.items).toEqual(history.body.items.slice(1, 2))
  expect((await api(page, auditPath + '?limit=101')).status).toBe(400)
  expect((await api(page, auditPath + '?limit=1&limit=2')).status).toBe(400)
  await page.getByRole('combobox', { name: '每页条数' }).click()
  await page.getByRole('option', { name: '10 条 / 页' }).click()
  await expect(auditRequests.getByText('重复请求', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('refund-operation-audit.png'), fullPage: true })

  await expect(page.getByRole('button', { name: /批准|驳回/ })).toHaveCount(0)
  await page.goto(`/quotes/${data.quoteId}`)
  const quoteIdDetail = await api(page, `/admin/quotes/${data.quoteId}`)
  expect(quoteIdDetail.status).toBe(200)
  expect(quoteIdDetail.body.businessNo).toMatch(/TRD-QTE-[0-9]{6}-[0-9]{10}/)
  await expect(page.getByRole('heading', { name: quoteIdDetail.body.businessNo, exact: true })).toBeVisible()
  await page.goto(`/receipts/${data.abnormalReceiptNo}`)
  const abnormalReceiptNoDetail = await api(page, `/admin/payment-receipts/${data.abnormalReceiptNo}`)
  expect(abnormalReceiptNoDetail.status).toBe(200)
  expect(abnormalReceiptNoDetail.body.businessNo).toMatch(/TRD-RCT-[0-9]{6}-[0-9]{10}/)
  await expect(page.getByRole('heading', { name: abnormalReceiptNoDetail.body.businessNo, exact: true })).toBeVisible()
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

test('numbered list gateways preserve real visibility and filtered totals', async ({ page }) => {
  await login(page)
  const available = `warehouseId=${data.warehouseId}&skuId=${data.skuId}`
  const lists = [
    ['numbered-pages/payments', `orderNo=${data.paidOrder}`],
    ['numbered-pages/payment-receipts', `orderNo=${data.paidOrder}`],
    ['numbered-pages/returns', `orderNo=${data.paidOrder}`],
    ['numbered-pages/quotes', ''],
    ['numbered-pages/refunds', 'status=ALL'],
    ['numbered-pages/products', 'status=DRAFT'],
    ['numbered-pages/members', 'status=ACTIVE'],
    ['numbered-pages/inventories', available],
    ['numbered-pages/warehouses', ''],
    ['numbered-pages/consent-documents', ''],
    ['numbered-pages/payment-channels', ''],
    ['numbered-pages/inventory-ledger', available],
    ['numbered-operations/operation-audits', 'resourceType=ORDER'],
    ['numbered-operations/outbox-publications', 'state=SENT'],
    ['numbered-operations/outbox-tasks', 'state=READY'],
    ['numbered-operations/reconciliation-cases', 'state=OPEN'],
    ['numbered-operations/reconciliation-operations', 'state=REVIEW'],
    [`numbered-pages/payment-attempts/${data.paymentNo}`, ''],
    [`numbered-operations/refund-audits/${data.refundNo}`, ''],
  ]
  for (const [resource, query] of lists) {
    const result = await guardedRead(page, `/admin/${resource}?page=2&limit=1&${query}`)
    expect(result.status, resource).toBe(200)
    expect(result.body.pageSize).toBe(1)
    expect(result.body.totalPages).toBe(result.body.totalCount)
    expect(result.body.page).toBe(result.body.totalCount > 1 ? 2 : 1)
    expect(result.body.items.length).toBe(result.body.totalCount > 0 ? 1 : 0)
  }

  const refunds = await guardedRead(page, `/admin/numbered-operations/refund-audits/${data.refundNo}?limit=1`)
  expect(refunds.body.totalCount).toBeGreaterThan(1)
  const paymentRows = await guardedRead(page, `/admin/numbered-pages/payments?orderNo=${data.paidOrder}`)
  const previous = await guardedRead(page, `/admin/payments?orderNo=${data.paidOrder}`)
  expect(paymentRows.body.items).toEqual(previous.body.items)
  const forbiddenJobs = await guardedRead(page, '/admin/numbered-pages/jobs')
  expect(forbiddenJobs.status).toBe(403)
})

test('latest orders keep the same order across cursor pages', async ({ page }) => {
  await login(page)
  const full = await guardedRead(page, '/admin/orders?sort=CREATED_DESC&limit=100')
  expect(full.status).toBe(200)
  const rows: { orderId: string; orderNo: string; createdAt: string }[] = full.body.items
  expect(rows.length).toBeGreaterThan(1)
  const expected = [...rows].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || (BigInt(a.orderId) > BigInt(b.orderId) ? -1 : 1))
  expect(rows.map(row => row.orderNo)).toEqual(expected.map(row => row.orderNo))
  let after = '0'
  for (const row of expected) {
    const current = await guardedRead(page, `/admin/orders?sort=CREATED_DESC&limit=1&after=${after}`)
    expect(current.status).toBe(200)
    expect(current.body.items[0].orderNo).toBe(row.orderNo)
    after = current.body.nextAfter
  }
  expect(after).toBeNull()
  expect((await guardedRead(page, '/admin/orders?sort=member_id')).status).toBe(400)
})

test('numbered order pages count the filtered tenant and jump directly with stable sorting', async ({ page }) => {
  await login(page)
  const filter = 'currency=CNY&sort=CREATED_DESC'
  const legacy = await api(page, `/admin/orders?limit=100&${filter}`)
  expect(legacy.status).toBe(200)
  const rows = legacy.body.items
  expect(rows.length).toBeGreaterThan(2)

  const third = await api(page, `/admin/order-pages?page=3&limit=1&${filter}`)
  expect(third.status).toBe(200)
  expect(third.body).toMatchObject({ page: 3, pageSize: 1, totalCount: rows.length, totalPages: rows.length })
  expect(third.body.items).toEqual([rows[2]])
  expect(Date.parse(third.body.observedAt)).toBeGreaterThan(0)

  const last = await api(page, `/admin/order-pages?page=9007199254740991&limit=2&${filter}`)
  const lastPage = Math.ceil(rows.length / 2)
  expect(last.body).toMatchObject({ page: lastPage, totalPages: lastPage, items: rows.slice((lastPage - 1) * 2) })
  const exact = await api(page, `/admin/order-pages?orderNo=${encodeURIComponent(rows[2].orderNo)}`)
  expect(exact.body).toMatchObject({ page: 1, totalCount: 1, totalPages: 1, items: [rows[2]] })
  const empty = await api(page, '/admin/order-pages?page=7&currency=XXX')
  expect(empty.body).toMatchObject({ page: 1, totalCount: 0, totalPages: 0, items: [] })
  for (const invalid of ['page=0', 'page=9007199254740992', 'page=1&page=2', 'page=2&after=0', 'limit=101']) {
    expect((await api(page, `/admin/order-pages?${invalid}`)).status).toBe(400)
  }
})

test('product SKU, member registration, and available inventory filters read past the first page', async ({ page }) => {
  await login(page, 'admin', '/products')
  await expect(page.getByRole('cell').filter({ hasText: 'INTEGRATION-P' })).toBeVisible()
  const baseline = await api(page, '/admin/products?limit=1')
  expect(baseline.status).toBe(200)
  expect(baseline.body[0]).not.toHaveProperty('productNo')
  const search = await api(page, '/admin/products/search?limit=1&skuNo=INTEGRATION-ALT-SECOND&status=DRAFT')
  expect(search.status).toBe(200)
  expect(search.body).toEqual([expect.objectContaining({ productId: data.alternateProduct, productNo: 'INTEGRATION-ALT', unitPrice: 900 })])
  expect(search.body[0].productId).not.toBe(baseline.body[0].productId)
  await page.getByLabel('按 SKU 编码').fill('INTEGRATION-ALT-SECOND')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('cell').filter({ hasText: 'INTEGRATION-ALT' })).toBeVisible()
  await expect(page.getByRole('cell').filter({ hasText: 'INTEGRATION-P' })).toHaveCount(0)

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

test('operations totals reconcile with paged facts for the same window, currency and tenant', async ({ page }, testInfo) => {
  await login(page, 'admin', '/analytics')
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
  const analysis = await api(page, `/admin/operations/analysis?${cny}`)
  expect(analysis.status).toBe(200)
  expect(analysis.body).toMatchObject({ payableCount: 3, convertedCount: 1 })
  expect(analysis.body.orderStates.reduce((sum: number, entry: { count: number }) => sum + entry.count, 0)).toBe(4)
  expect(analysis.body.daily.reduce((sum: bigint, day: { netReceipt: string }) => sum + BigInt(day.netReceipt), 0n)).toBe(19000n)
  const factPage = await api(page, `/admin/operations/financial-fact-pages?${cny}&scope=NET&page=2&limit=1`)
  expect(factPage.status).toBe(200)
  expect(factPage.body).toMatchObject({ page: 2, totalCount: 2, totalPages: 2 })
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
  await expect(page.getByRole('button', { name: '查看期间净收款明细' })).toContainText('190.00 元')
  await page.screenshot({ path: testInfo.outputPath('analytics-summary.png'), fullPage: true })
  await page.getByRole('button', { name: '查看期间净收款明细' }).click()
  await expect(page.getByRole('heading', { name: '期间净收款明细' })).toBeVisible()
  const refundNo = netRefund.body.items[0].refundBusinessNo
  expect(refundNo).toMatch(/^TRD-RFD-[0-9]{6}-[0-9]{10}$/)
  const refundLink = page.getByRole('link', { name: refundNo, exact: true })
  await expect(refundLink).toBeVisible()
  await expect(refundLink).toHaveAttribute('href', `/refunds/${data.successfulRefund}`)
  await expect(page.getByRole('link', { name: data.successfulRefund, exact: true })).toHaveCount(0)
  await expect(page.locator(`a[href="/refunds/${data.submittedRefund}"]`)).toHaveCount(0)
  await page.getByRole('link', { name: '查看创建订单' }).click()
  await page.getByRole('button', { name: /高级筛选/ }).click()
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
  await page.locator('form').filter({ has: page.getByRole('button', { name: '读取', exact: true }) }).getByLabel('退货单号', { exact: true }).fill(data.openReturn)
  await page.getByRole('button', { name: '读取', exact: true }).click()
  await expect(page.getByText('尚未验收', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: '验收结果', exact: true }).click()
  await page.getByRole('option', { name: '验收通过', exact: true }).click()
  await page.getByLabel('回库数量', { exact: true }).fill('1')
  await page.getByLabel('验收凭证', { exact: true }).fill('test:browser-inspection')
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

test('product file commands, display image and replenishment rules use real versions and idempotency', async ({ page }) => {
  await login(page)
  const draft = [
    { productNo: 'BATCH-OPS-1', skuNo: 'BATCH-OPS-SKU-1', name: '运营批次一', unitPrice: 100, currency: 'CNY' },
    { productNo: 'BATCH-OPS-2', skuNo: 'BATCH-OPS-SKU-2', name: '运营批次二', unitPrice: 200, currency: 'CNY' },
  ]
  const importDrafts = (rows: typeof draft) => api(page, '/admin/products/bulk-import', {
    method: 'POST', headers: { 'Idempotency-Key': 'integration-product-bulk-import' }, body: JSON.stringify({ items: rows }),
  })
  const imported = await importDrafts(draft)
  expect(imported.status).toBe(200)
  expect(imported.body.items).toEqual(draft.map(({ productNo }) => ({ reference: productNo, state: 'COMPLETED' })))
  expect((await importDrafts(draft)).body).toEqual(imported.body)
  expect((await importDrafts(draft.slice(1))).status).toBe(409)

  const read = async (productNo: string) => (await api(page, `/admin/numbered-pages/products?productNo=${productNo}`)).body.items[0]
  const first = await read(draft[0].productNo)
  const second = await read(draft[1].productNo)
  const img = await api(page, `/admin/products/${first.productId}/image`, {
    method: 'PATCH', headers: { 'Idempotency-Key': 'integration-product-image' },
    body: JSON.stringify({ version: first.version, imageUrl: 'https://assets.example.test/product.png' }),
  })
  expect(img.status).toBe(200)
  const withImage = await read(draft[0].productNo)
  expect(withImage.imageUrl).toBe('https://assets.example.test/product.png')
  expect(withImage.version).toBe(first.version + 1)
  const updateBatch = (version: number, key: string) => api(page, '/admin/products/bulk-state', {
    method: 'POST', headers: { 'Idempotency-Key': key },
    body: JSON.stringify({ action: 'PUBLISH', items: [
      { productId: first.productId, version: withImage.version },
      { productId: second.productId, version },
    ] }),
  })
  expect((await updateBatch(second.version + 1000, 'integration-product-stale')).status).toBe(409)
  expect((await read(draft[0].productNo)).status).toBe('DRAFT')
  expect((await updateBatch(second.version, 'integration-product-publish')).status).toBe(200)
  expect((await read(draft[1].productNo)).status).toBe('PUBLISHED')

  const path = `/admin/inventory-alerts?warehouseId=${data.warehouseId}&skuId=${data.skuId}`
  const before = (await api(page, path)).body.rows.items[0]
  const patch = await api(page, `/admin/inventory-alerts/${data.warehouseId}/${data.skuId}`, {
    method: 'PATCH', headers: { 'Idempotency-Key': 'integration-threshold' },
    body: JSON.stringify({ revision: before.revision, reorderThreshold: before.available }),
  })
  expect(patch.status).toBe(200)
  const after = (await api(page, path)).body
  expect(after.rows.items[0]).toMatchObject({ available: before.available, reorderThreshold: before.available,
    state: before.available === 0 ? 'OUT_OF_STOCK' : 'LOW', revision: before.revision + 1 })
  expect(after.totals).toEqual([{ state: after.rows.items[0].state, count: 1 }])

  const csv = await api(page, `/admin/exports/orders?orderNo=${data.paidOrder}`)
  expect(csv.status).toBe(200)
  expect(csv.contentType).toContain('text/csv')
  expect(csv.body.split('\r\n').filter(Boolean)).toHaveLength(2)
  const order = await api(page, `/admin/orders/${data.paidOrder}`)
  expect(csv.body).toContain(order.body.businessNo ?? data.paidOrder)

  await page.goto(`/inventory?warehouseId=${data.warehouseId}&skuId=${data.skuId}`)
  await page.getByRole('button', { name: '查看预警与补货阈值' }).click()
  await expect(page.getByRole('region', { name: '库存预警列表' })).toBeVisible()
  await page.getByRole('button', { name: '设置阈值', exact: true }).click()
  const reorder = page.getByRole('dialog', { name: '设置补货阈值' })
  await expect(reorder.getByLabel('补货阈值', { exact: true })).toHaveValue(String(before.available))
  await reorder.getByRole('button', { name: '关闭' }).click()

  await page.goto(`/products?productNo=${draft[0].productNo}`)
  await page.getByRole('button', { name: '编辑展示图' }).click()
  await expect(page.getByRole('dialog', { name: '编辑商品展示图' }).getByLabel('HTTPS 图片地址')).toHaveValue(withImage.imageUrl)
})

test('diagnostic scopes show evidence and jobs; ordinary admin cannot read them', async ({ page }) => {
  await login(page, 'diagnostic', `/orders/${data.paidOrder}`)
  await expect(page.getByRole('heading', { name: data.paidOrder, exact: true })).toBeVisible()
  expect((await api(page, `/admin/orders/${data.paidOrder}/referral-evidence`)).status).toBe(200)
  await page.goto('/jobs')
  await expect(page.getByRole('heading', { name: '任务', exact: true })).toBeVisible()
  const jobs = (await api(page, '/admin/jobs')).body.items
  expect(jobs.length).toBeGreaterThan(0)
  await page.getByRole('link', { name: jobs[0].jobId, exact: true }).click()
  await expect(page.getByRole('heading', { name: jobs[0].jobId, exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '运行摘要' })).toBeVisible()
})

test('ordinary admin scope denial and genuine rate-limit response', async ({ page }) => {
  await login(page)
  expect((await api(page, '/admin/jobs')).status).toBe(403)
  expect((await api(page, '/admin/operations/linked-checkouts')).status).toBe(403)
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
  expect((await api(page, '/admin/order-pages?page=2')).body).toMatchObject({ page: 1, totalCount: 0, totalPages: 0, items: [] })
  expect((await api(page, `/admin/members?memberId=${data.memberId}`)).body).toEqual([])
  expect((await api(page, `/admin/inventories?warehouseId=${data.warehouseId}&skuId=${data.skuId}`)).body).toEqual([])
})

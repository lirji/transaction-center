import { test, expect } from '@playwright/test'

const order = {
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
}

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
            items: url.match(/\/admin\/orders(\?|$)/) ? [order] : [],
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
    await expect(page.getByRole('heading', { name: '会员', level: 1 })).toBeVisible()
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
    await expect(page.getByRole('heading', { name: '订单', level: 1 })).toBeVisible()
    await expect(page.getByText('ORD-1')).toBeVisible()
  })

  for (const width of [1440, 390]) {
    test(`opens and filters the operation log at ${width}px`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 })
      const cursor = '2026-09-08T00:00:00Z,E12'
      const orderLog = {
        id: 'E12', occurredAt: '2026-09-08T00:00:00Z', resourceType: 'ORDER',
        resourceId: 'ORD-AUDIT', action: 'order.create', actorId: 'a'.repeat(64),
        result: 'RECORDED', traceId: 'b'.repeat(32), errorCode: null,
      }
      const refundLog = {
        ...orderLog, id: 'R11', resourceType: 'REFUND', resourceId: null,
        action: 'refund.request', result: 'REJECTED', errorCode: 'REFUND_INVALID',
      }
      await page.route('**/admin/operation-audits**', async (route) => {
        const params = new URL(route.request().url()).searchParams
        const second = params.get('cursor') === cursor
        const filtered = params.get('resourceType') === 'ORDER' && params.get('resourceRef') === 'ORD-AUDIT'
        await route.fulfill({ json: {
          items: second ? [refundLog] : [orderLog],
          nextCursor: second || filtered ? null : cursor,
        } })
      })
      await page.goto('/orders')
      if (width === 390) await page.getByRole('button', { name: '打开菜单' }).click()
      await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '操作日志' }).click()
      await expect(page.getByRole('heading', { name: '操作日志', level: 1 })).toBeVisible()
      await expect(page.getByText('2026/09/08 08:00:00')).toBeVisible()
      await expect(page.getByRole('link', { name: 'ORD-AUDIT' })).toHaveAttribute('href', '/orders/ORD-AUDIT')

      const list = page.getByRole('region', { name: '操作日志列表' })
      const pageFits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)
      expect(pageFits).toBe(true)
      if (width === 390) expect(await list.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
      await testInfo.attach('operation-logs', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })

      await page.getByRole('button', { name: '下一页' }).click()
      await expect(page.getByText('REFUND_INVALID')).toBeVisible()
      await page.getByLabel('对象类型').selectOption('ORDER')
      await page.getByLabel('对象编号').fill('ORD-AUDIT')
      const request = page.waitForRequest((pending) => pending.url().includes('/admin/operation-audits?resourceType=ORDER&resourceRef=ORD-AUDIT'))
      await page.getByRole('button', { name: '查询' }).click()
      expect(new URL((await request).url()).searchParams.has('cursor')).toBe(false)
      await expect(page.getByRole('link', { name: 'ORD-AUDIT' })).toBeVisible()
      await expect(page.getByRole('button', { name: '下一页' })).toBeDisabled()
      await page.getByRole('button', { name: '清空筛选' }).click()
      await expect(page).toHaveURL(/\/operation-logs$/)
      await expect(page.getByLabel('对象编号')).toHaveValue('')
    })
  }

  test('loads only the active order section and keeps the list query after a retry', async ({ page }, testInfo) => {
    const requests: string[] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname.includes('/admin/')) requests.push(url.pathname.split('/admin/')[1])
    })
    await page.route('**/api/v1/trade/admin/orders/ORD-1', (route) => route.fulfill({
      json: { ...order, observedAt: order.updatedAt, items: [], fulfillment: null },
    }))
    let refuseRefundRead = true
    await page.route('**/api/v1/trade/admin/refunds?*', async (route) => {
      await route.fulfill(refuseRefundRead ? {
        status: 429,
        headers: { 'Retry-After': '3' },
        json: { code: 'ADMIN_QUERY_BUSY', message: '管理查询繁忙', traceId: 't', retryable: true },
      } : { json: [] })
    })
    await page.goto('/orders?status=PAID&after=10')
    await page.getByRole('link', { name: 'ORD-1', exact: true }).click()
    await expect(page.getByRole('heading', { name: '冻结商品' })).toBeVisible()
    expect(requests).toEqual(['orders', 'orders/ORD-1'])
    await page.screenshot({ path: testInfo.outputPath('order-detail.png') })
    await page.getByRole('button', { name: '退款', exact: true }).click()
    await expect(page.getByText(/3 秒后重试/)).toBeVisible()
    await expect(page.getByRole('heading', { name: 'ORD-1', exact: true })).toBeVisible()
    refuseRefundRead = false
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await expect(page.getByRole('heading', { name: '本单无退款' })).toBeVisible()
    expect([...new Set(requests)]).toEqual(['orders', 'orders/ORD-1', 'refunds'])
    await page.getByRole('link', { name: '返回订单列表' }).click()
    await expect(page).toHaveURL(/\/orders\?status=PAID&after=10$/)
  })

})

test('product dialogs keep draft input on failure and return to the original list', async ({ page }, testInfo) => {
  const draft = {
    productId: '9', productNo: 'P-1', name: '企业采购测试商品', skuId: '1',
    unitPrice: 199, currency: 'CNY', status: 'DRAFT', version: 1, publishedRevision: null,
  }
  let createAttempts = 0
  let draftIdempotencyKey: string | undefined
  let created = false
  await page.route('**/api/v1/trade/admin/products**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST') {
      expect(request.postDataJSON()).toMatchObject({ productNo: 'P-1', skuNo: 'SKU-1', unitPrice: 199, currency: 'CNY' })
      createAttempts += 1
      if (createAttempts === 1) {
        draftIdempotencyKey = request.headers()['idempotency-key']
        expect(draftIdempotencyKey).toBeTruthy()
        await route.fulfill({ status: 503, json: { code: 'TEST_TEMPORARY', message: '暂时无法创建商品', retryable: true } })
        return
      }
      expect(request.headers()['idempotency-key']).toBe(draftIdempotencyKey)
      created = true
      await route.fulfill({ json: { resourceId: '9' } })
      return
    }
    if (path.endsWith('/search')) {
      await route.fulfill({ json: created ? [draft] : [] })
    } else if (path.endsWith('/skus')) {
      await route.fulfill({ json: [{
        skuId: '1', skuNo: 'SKU-1', productId: '9', title: draft.name, unitPrice: 199,
        currency: 'CNY', stockType: 'PHYSICAL', specification: { color: '蓝' }, revision: 1,
      }] })
    } else {
      const detail = { ...draft, productNo: undefined }
      await route.fulfill({ json: detail })
    }
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/products?status=DRAFT')
  const open = page.getByRole('button', { name: '创建草稿' })
  await open.click()
  const create = page.getByRole('dialog', { name: '创建商品草稿' })
  await expect(create.getByLabel('商品编号')).toBeFocused()
  await create.getByLabel('商品编号').fill('P-1')
  await create.getByLabel('商品名称').fill(draft.name)
  await create.getByLabel('主 SKU 编码').fill('SKU-1')
  await create.getByLabel('单价（最小单位整数）').fill('199')
  await page.screenshot({ path: testInfo.outputPath('product-create-mobile.png') })
  await create.getByRole('button', { name: '创建', exact: true }).click()
  await expect(create.getByRole('alert')).toHaveText(/入口暂不可用/)
  await expect(create.getByLabel('商品名称')).toHaveValue(draft.name)
  await create.getByRole('button', { name: '创建', exact: true }).click()
  await expect(create).toBeHidden()
  await expect(open).toBeFocused()
  await page.getByRole('link', { name: draft.name }).click()
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const edit = page.getByRole('dialog', { name: '编辑 SKU SKU-1' })
  await expect(edit.getByLabel('单价')).toHaveValue('199')
  await expect(edit.getByLabel('单价')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(edit).toBeHidden()
  await expect(page.getByRole('button', { name: '编辑', exact: true })).toBeFocused()
  await page.getByRole('link', { name: '返回列表' }).click()
  await expect(page).toHaveURL(/\/products\?status=DRAFT$/)
})

test('keeps advanced order filters visible in the applied count and clears the cursor', async ({ page }, testInfo) => {
  await page.route('**/api/v1/trade/admin/orders?**', (route) => route.fulfill({
    json: { items: [order], nextAfter: null, hasMore: false, observedAt: order.updatedAt },
  }))
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/orders?currency=CNY&after=10')
  const expand = page.getByRole('button', { name: /高级筛选/ })
  await expect(expand).toHaveAttribute('aria-expanded', 'false')
  await expect(expand.getByLabel('已应用 1 项高级条件')).toBeVisible()
  await expand.click()
  await expect(page.getByLabel('币种')).toHaveValue('CNY')
  await page.screenshot({ path: testInfo.outputPath('order-filters-desktop.png') })
  await page.getByRole('button', { name: '清空筛选' }).click()
  await expect(page).toHaveURL(/\/orders$/)
  await expect(page.getByLabel('币种')).toHaveValue('')
  await expect(page.getByText('本页 1 条')).toBeVisible()

  await page.keyboard.press('Control+k')
  const search = page.getByRole('dialog', { name: '搜索模块' })
  await expect(search.getByRole('textbox')).toBeFocused()
  await search.getByRole('textbox').fill('日志')
  await expect(search.getByRole('button', { name: /操作日志/ })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(search).toBeHidden()
})

const moduleRoutes = [
  ['/', '交易运营工作台'], ['/members', '会员'], ['/consents', '条款文档'], ['/merchant', '逻辑商户'],
  ['/products', '商品'], ['/inventory', '仓库与库存'], ['/orders', '订单'], ['/payments', '支付'],
  ['/receipts', '实收'], ['/quotes', '报价'], ['/refunds', '退款'], ['/payment-channels', '支付渠道'],
  ['/returns', '退货'], ['/fulfillment', '履约'], ['/outbox', '发布隔离'],
  ['/reconciliation', '对账差异'], ['/admission', '准入策略'], ['/operation-logs', '操作日志'],
] as const

for (const width of [1440, 1024, 390]) {
  test(`all accessible modules fit a ${width}px viewport`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await page.route('**/api/v1/trade/**', (route) => route.fulfill({
      status: 404, json: { code: 'NOT_FOUND', message: '无测试记录', retryable: false },
    }))
    for (const [path, title] of moduleRoutes) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1, name: title, exact: true })).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth), path).toBeLessThanOrEqual(width)
      if (path === '/') {
        await expect(page.getByRole('button', { name: '打开菜单' })).toBeVisible({ visible: width <= 800 })
        await page.screenshot({ path: testInfo.outputPath(`workbench-${width}.png`) })
      }
    }
    await page.goto('/jobs')
    await expect(page.getByRole('heading', { name: '没有访问该模块的权限' })).toBeVisible()
    await page.goto('/unknown-module')
    await expect(page.getByRole('heading', { name: '页面没有找到' })).toBeVisible()
    expect(errors).toEqual([])
  })
}

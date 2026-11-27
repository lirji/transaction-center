import { test, expect, type Locator } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  // 其余契约用例继续覆盖旧服务、旧链接和已知游标。
  await page.route('**/api/v1/trade/admin/order-pages?**', (route) => route.fulfill({ status: 404 }))
})

async function choose(control: Locator, label: string) {
  await control.click()
  await control.page().getByRole('option', { name: label, exact: true }).click()
}

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
      await page.getByRole('button', { name: '上一页' }).click()
      await expect(page.getByRole('link', { name: 'ORD-AUDIT' })).toBeVisible()
      await choose(page.getByRole('combobox', { name: '跳转页码' }), '第 2 页')
      await expect(page.getByText('REFUND_INVALID')).toBeVisible()
      await choose(page.getByRole('combobox', { name: '对象类型' }), '订单')
      await page.getByLabel('对象编号', { exact: true }).fill('ORD-AUDIT')
      const request = page.waitForRequest((pending) => pending.url().includes('/admin/operation-audits?resourceType=ORDER&resourceRef=ORD-AUDIT'))
      await page.getByRole('button', { name: '查询' }).click()
      expect(new URL((await request).url()).searchParams.has('cursor')).toBe(false)
      await expect(page.getByRole('link', { name: 'ORD-AUDIT' })).toBeVisible()
      await expect(page.getByRole('button', { name: '下一页' })).toBeDisabled()
      await page.getByRole('button', { name: '清空筛选' }).click()
      await expect(page).toHaveURL(/\/operation-logs$/)
      await expect(page.getByLabel('对象编号', { exact: true })).toHaveValue('')
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
  await expect(page.getByLabel('币种', { exact: true })).toHaveValue('CNY')
  await page.screenshot({ path: testInfo.outputPath('order-filters-desktop.png') })
  await page.getByRole('button', { name: '清空筛选' }).click()
  await expect(page).toHaveURL(/\/orders$/)
  await expect(page.getByLabel('币种', { exact: true })).toHaveValue('')
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

test('uses known cursors for previous page and jumps, restores them after refresh and detail navigation', async ({ page }, testInfo) => {
  const requested: string[] = []
  await page.route('**/api/v1/trade/admin/orders?**', async (route) => {
    const params = new URL(route.request().url()).searchParams
    const cursor = params.get('after') || '0'
    requested.push(cursor)
    const filtered = params.has('orderNo')
    const next = filtered ? null : { '0': '10', '10': '20', '20': '30' }[cursor] ?? null
    await route.fulfill({
      json: {
        items: cursor === '30' ? [] : [{ ...order, orderNo: 'ORD-' + cursor }],
        nextAfter: next,
        hasMore: next !== null,
        observedAt: order.updatedAt,
      },
    })
  })
  await page.route('**/api/v1/trade/admin/orders/ORD-20', (route) => route.fulfill({
    json: { ...order, orderNo: 'ORD-20', observedAt: order.updatedAt, items: [], fulfillment: null },
  }))

  await page.setViewportSize({ width: 390, height: 900 })
  await page.goto('/orders')
  const pager = page.getByRole('navigation', { name: '结果分页' })
  const jump = pager.getByRole('combobox', { name: '跳转页码' })
  const previous = pager.getByRole('button', { name: '上一页' })
  const next = pager.getByRole('button', { name: '下一页' })
  await expect(previous).toBeDisabled()
  await next.click()
  await expect(page.getByRole('link', { name: 'ORD-10', exact: true })).toBeVisible()
  await next.click()
  await expect(page.getByRole('link', { name: 'ORD-20', exact: true })).toBeVisible()
  await expect(jump).toHaveText('第 3 页')

  await next.click()
  await expect(page.getByRole('heading', { name: '当前筛选无订单' })).toBeVisible()
  await expect(next).toBeDisabled()
  await previous.click()
  await expect(page.getByRole('link', { name: 'ORD-20', exact: true })).toBeVisible()
  await choose(jump, '第 1 页')
  await expect(page.getByRole('link', { name: 'ORD-0', exact: true })).toBeVisible()
  const requestsBeforeJump = requested.length
  await choose(jump, '第 3 页')
  await expect(page.getByRole('link', { name: 'ORD-20', exact: true })).toBeVisible()
  expect(requested.slice(requestsBeforeJump).every((cursor) => cursor === '20')).toBe(true)
  await expect(page).toHaveURL(/after=20/)

  await page.reload()
  await expect(jump).toHaveText('第 3 页')
  await expect(previous).toBeEnabled()
  await page.getByRole('link', { name: 'ORD-20', exact: true }).click()
  await expect(page.getByRole('heading', { name: '订单摘要' })).toBeVisible()
  await page.getByRole('link', { name: '返回订单列表' }).click()
  await expect(jump).toHaveText('第 3 页')
  await expect(previous).toBeEnabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await pager.screenshot({ path: testInfo.outputPath('cursor-pager-mobile.png') })

  await page.getByLabel('订单号', { exact: true }).fill('ORD-0')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page).toHaveURL(/orderNo=ORD-0$/)
  await expect(previous).toBeDisabled()
  await expect(jump).toHaveCount(0)
})

test('shows the true order total and jumps to a page that has never been visited', async ({ page }) => {
  const requests: URLSearchParams[] = []
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/v1/trade/admin/order-pages?**', (route) => {
    const params = new URL(route.request().url()).searchParams
    requests.push(params)
    const pageNumber = Number(params.get('page'))
    const pageSize = Number(params.get('limit'))
    return route.fulfill({ json: {
      items: [{ ...order, orderId: String(pageNumber), orderNo: `ORD-PAGE-${pageNumber}` }],
      page: pageNumber, pageSize, totalCount: 500, totalPages: 500 / pageSize,
      observedAt: '2026-09-08T12:00:00Z',
    } })
  })

  await page.goto('/orders?status=PAID&size=10&page=9')
  const pager = page.getByRole('navigation', { name: '结果分页' })
  await expect(pager.getByText('共 500 条')).toBeVisible()
  await expect(pager.getByText('第 9 / 50 页')).toBeVisible()
  await pager.getByLabel('指定页码').fill('51')
  await pager.getByRole('button', { name: '跳转', exact: true }).click()
  await expect(pager.getByRole('alert')).toHaveText('请输入 1 至 50 的页码')
  await pager.getByLabel('指定页码').fill('42')
  await pager.getByLabel('指定页码').press('Enter')
  await expect(pager.getByText('第 42 / 50 页')).toBeVisible()
  await expect(page.getByRole('link', { name: 'ORD-PAGE-42' })).toBeVisible()
  await expect(page).toHaveURL(/page=42/)
  expect([...new Set(requests.map((request) => request.get('page')))]).toEqual(['9', '42'])
  const jumpRequest = requests.find((request) => request.get('page') === '42')
  expect(jumpRequest?.get('status')).toBe('PAID')
  expect(jumpRequest?.has('after')).toBe(false)
  await pager.getByRole('button', { name: '上一页' }).click()
  await expect(pager.getByText('第 41 / 50 页')).toBeVisible()
  await pager.getByRole('button', { name: '下一页' }).click()
  await expect(pager.getByText('第 42 / 50 页')).toBeVisible()
  await choose(pager.getByRole('combobox', { name: '选择页码' }), '第 1 页')
  await expect(pager.getByText('第 1 / 50 页')).toBeVisible()
  await choose(pager.getByRole('combobox', { name: '每页条数' }), '20 条 / 页')
  await expect(pager.getByText('第 1 / 25 页')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})

test('workbench compares equal periods and opens funding facts from the actual summary', async ({ page }, testInfo) => {
  const windows: Array<{ from: string; to: string }> = []
  await page.route('**/api/v1/trade/admin/operations/summary?**', async (route) => {
    const params = new URL(route.request().url()).searchParams
    const from = params.get('createdFrom')!
    const to = params.get('createdTo')!
    if (!windows.some((window) => window.from === from && window.to === to)) windows.push({ from, to })
    const previous = from !== windows[0].from
    await route.fulfill({ json: {
      from, to, currency: 'CNY', observedAt: order.updatedAt, orderCount: previous ? 4 : 8,
      orderPayable: previous ? '12000' : '24000', paidOrderCount: previous ? 3 : 6,
      normalReceipt: '20800', abnormalReceipt: '620', normalRefund: '1800', abnormalRefund: '55', netReceipt: '19000',
    } })
  })
  await page.route('**/admin/operations/financial-facts?**', async (route) => {
    const params = new URL(route.request().url()).searchParams
    expect(params.get('scope')).toBe('NET')
    expect(params.get('currency')).toBe('CNY')
    expect(params.get('createdFrom')).toBe(windows[0].from)
    await route.fulfill({ json: { items: [], nextAfter: null, hasMore: false, observedAt: order.updatedAt } })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/')
  const summary = page.getByRole('button', { name: '查看期间净收款明细' })
  await expect(summary).toContainText('19,000')
  await expect(page.getByRole('button', { name: '查看正常确认收款明细' })).toContainText('20,800')
  await page.getByRole('button', { name: '对比上一期' }).click()
  await expect(page.getByRole('link', { name: '查看创建订单' })).toContainText('较上期 +100.0%')
  expect(windows[1].to).toBe(windows[0].from)
  expect(Date.parse(windows[1].to) - Date.parse(windows[1].from))
    .toBe(Date.parse(windows[0].to) - Date.parse(windows[0].from))
  await page.screenshot({ path: testInfo.outputPath('workbench-financial-summary.png') })
  await summary.click()
  await expect(page.getByRole('heading', { name: '期间净收款明细' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '期间无资金记录' })).toBeVisible()
})

test('table tools, keyboard selection, presets and real page size requests', async ({ page, browserName }, testInfo) => {
  const issued: URLSearchParams[] = []
  await page.route('**/api/v1/trade/admin/orders?**', async (route) => {
    issued.push(new URL(route.request().url()).searchParams)
    await route.fulfill({ json: {
      items: [order], nextAfter: null, hasMore: false, observedAt: order.updatedAt,
    } })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/orders?after=10&status=PAID')
  const table = page.getByRole('region', { name: '订单列表' })
  await expect(table.getByRole('link', { name: 'ORD-1' })).toBeVisible()
  if (browserName === 'chromium') {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
    await table.getByRole('button', { name: '复制订单号' }).click()
    await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe('ORD-1')
  }
  await choose(page.getByRole('combobox', { name: '每页条数' }), '50 条 / 页')
  await expect(page).toHaveURL(/status=PAID&size=50$/)
  await expect.poll(() => issued.at(-1)?.get('limit')).toBe('50')
  expect(issued.at(-1)?.get('after')).toBe('0')
  await page.reload()
  await expect(page.getByRole('combobox', { name: '每页条数' })).toHaveText('50 条 / 页')

  await page.getByRole('button', { name: '显示设置' }).click()
  await page.getByRole('checkbox', { name: '紧凑行高' }).check()
  await page.getByRole('checkbox', { name: '仓库 ID', exact: true }).uncheck()
  await expect(table.getByRole('columnheader', { name: '仓库 ID' })).toHaveCount(0)
  await expect(table.getByRole('table')).toHaveClass(/data-table-compact/)
  await page.screenshot({ path: testInfo.outputPath('order-table-tools.png') })
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: '放大表格' }).click()
  const expanded = page.getByRole('dialog', { name: '订单列表' })
  await expect(expanded.getByRole('columnheader', { name: '订单号' })).toBeVisible()
  await expect(expanded.getByRole('columnheader', { name: '仓库 ID' })).toHaveCount(0)
  await expanded.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(table.getByRole('link', { name: 'ORD-1' })).toBeVisible()

  const beforeRefresh = issued.length
  await page.getByRole('group', { name: '订单列表工具' }).getByRole('button', { name: '刷新' }).click()
  await expect.poll(() => issued.length).toBe(beforeRefresh + 1)
  await page.getByRole('button', { name: '取消筛选 订单状态' }).click()
  await expect(page).toHaveURL(/\/orders\?size=50$/)
  await expect.poll(() => issued.at(-1)?.get('status')).toBeNull()

  const choice = page.getByRole('combobox', { name: '订单状态' })
  await choice.focus()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(choice).not.toHaveText('全部')
  await page.getByRole('button', { name: '高级筛选' }).click()
  await page.getByRole('button', { name: '近 7 天' }).click()
  const from = page.getByLabel('起始时间（北京时间）', { exact: true })
  await expect(from).toHaveValue(/^\d{4}-\d{2}-\d{2} 00:00$/)
  await page.getByRole('button', { name: '打开日历' }).first().click()
  await expect(page.getByRole('group', { name: '选择日期' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('group', { name: '选择日期' })).toHaveCount(0)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect.poll(() => issued.at(-1)?.has('createdFrom')).toBe(true)
  const filter = issued.at(-1)!
  expect(Date.parse(filter.get('createdTo')!) - Date.parse(filter.get('createdFrom')!)).toBe(7 * 86_400_000)
})

test('receipt drawer shows the available amounts without leaving the results', async ({ page }, testInfo) => {
  await page.route('**/admin/payment-receipts?**', async (route) => route.fulfill({ json: {
    items: [{
      receiptNo: 'RCT-1', paymentNo: 'PAY-1', orderNo: 'ORD-1', attemptNo: 'PATT-1',
      channelCode: 'DEMO', amount: 239900, currency: 'CNY', disposition: 'EXCESS',
      providerPaidAt: order.updatedAt, confirmedAt: order.updatedAt, createdAt: order.updatedAt,
      refundedAmount: 1200, refundReservedAmount: 100, unreservedAmount: 238600, version: 1,
    }], nextAfter: null, hasMore: false, observedAt: order.updatedAt,
  } }))
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/receipts')
  const trigger = page.getByRole('button', { name: '查看摘要' })
  await trigger.click()
  const preview = page.getByRole('dialog', { name: 'RCT-1' })
  await expect(preview.getByText('CNY 2,399.00')).toBeVisible()
  await expect(preview.getByText('超额收款')).toBeVisible()
  await expect(preview.getByRole('link', { name: '查看完整实收详情' })).toHaveAttribute('href', '/receipts/RCT-1')
  await page.screenshot({ path: testInfo.outputPath('receipt-drawer-mobile.png') })
  await page.keyboard.press('Escape')
  await expect(trigger).toBeFocused()
  await expect(page).toHaveURL(/\/receipts$/)
})

import { useState, type ReactNode } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, CursorPager, PageHeader, PagePager, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { PageQueryGate, QueryError, QueryGate } from '../../shared/ui/QueryGate'
import { dash, formatBeijingTime, statusTone } from '../../shared/ui/display'
import { financialStatusSchema, orderStatusSchema } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_ADMIN_EVIDENCE_READ, TRADE_FULFILLMENT_WRITE } from '../../shared/auth/auth-context'
import { PaymentRows } from '../payments/PaymentsPage'
import { ReceiptRows } from '../receipts/ReceiptsPage'
import { RefundRows } from '../refunds/RefundRows'
import { ReturnRows } from '../returns/ReturnRows'
import { financialLabels, orderLabels } from './orderStatus'

const sections = [
  { id: 'items', label: '商品明细' },
  { id: 'fulfillment', label: '履约' },
  { id: 'payments', label: '支付意图' },
  { id: 'receipts', label: '实收' },
  { id: 'refunds', label: '退款' },
  { id: 'returns', label: '退货' },
  { id: 'evidence', label: '交易证据' },
] as const

export function OrderDetailPage() {
  const { orderNo = '' } = useParams()
  return <OrderDetail key={orderNo} orderNo={orderNo} />
}

function OrderDetail({ orderNo }: { orderNo: string }) {
  const [copyResult, setCopyResult] = useState('')
  const auth = useAuth()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const canFulfill = auth.hasScope(TRADE_FULFILLMENT_WRITE)
  const canEvidence = auth.hasScope(TRADE_ADMIN_EVIDENCE_READ)
  const visibleSections = sections.filter(({ id }) => id !== 'evidence' || canEvidence)
  const active = visibleSections.find(({ id }) => id === params.get('section'))?.id ?? 'items'
  const listSearch = typeof location.state?.ordersSearch === 'string' ? location.state.ordersSearch : ''
  const detail = useQuery({
    queryKey: ['admin-order', orderNo],
    queryFn: () => api.order(orderNo),
    retry: false,
    enabled: Boolean(orderNo),
  })
  if (detail.isLoading) {
    return <div className="workspace"><StateBanner tone="loading" title="正在读取订单" detail={`GET /admin/orders/${orderNo}`} /></div>
  }
  if (detail.isError || !detail.data) {
    return <div className="workspace"><QueryError query={detail} /></div>
  }

  const order = detail.data
  const noPayment = order.financialStatus === financialStatusSchema.enum.NO_PAYMENT_REQUIRED
  const showFulfill = canFulfill && !order.fulfillment && order.status === orderStatusSchema.enum.PAID &&
    (noPayment || Boolean(order.paidAt))

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Order"
        title={order.businessNo ?? order.orderNo}
        description="查看订单状态、冻结商品与关联交易记录。"
        actions={<>
          <button type="button" className="button secondary" onClick={async () => {
            try {
              await navigator.clipboard.writeText(order.businessNo ?? order.orderNo)
              setCopyResult('已复制订单号')
            } catch {
              setCopyResult('复制失败，可选中订单号手动复制')
            }
          }}>复制订单号</button>
          <Link className="button secondary" to={{ pathname: '/orders', search: listSearch }} state={{ returnedOrder: order.orderNo }}>返回订单列表</Link>
          {showFulfill ? <Link className="button primary" to={`/fulfillment?orderNo=${encodeURIComponent(order.orderNo)}`}>声明履约</Link> : null}
        </>}
      />
      <div role="status" aria-live="polite">{copyResult}</div>
      <div className="section-gap">
        <Panel>
          <h2>订单摘要</h2>
          <dl className="summary-fields">
            <div><dt>订单状态</dt><dd><Badge tone={statusTone(order.status)}>{orderLabels[order.status]}</Badge></dd></div>
            <div><dt>资金状态</dt><dd><Badge tone={statusTone(order.financialStatus)}>{financialLabels[order.financialStatus]}</Badge></dd></div>
            <div><dt>应付金额</dt><dd>{formatMinor(order.payableAmount, order.currency)}</dd></div>
            <div><dt>创建时间（北京时间）</dt><dd>{formatBeijingTime(order.createdAt)}</dd></div>
            <div><dt>支付确认时间（北京时间）</dt><dd>{noPayment ? '无需支付' : formatBeijingTime(order.paidAt)}</dd></div>
            <div><dt>有效至（北京时间）</dt><dd>{formatBeijingTime(order.expiresAt)}</dd></div>
            <div><dt>会员 ID</dt><dd className="mono">{order.memberId}</dd></div>
            <div><dt>报价编号</dt><dd><Link className="mono" to={`/quotes/${encodeURIComponent(order.quoteId)}`}>{order.quoteId}</Link></dd></div>
            <div><dt>仓库 ID</dt><dd className="mono">{dash(order.warehouseId)}</dd></div>
          </dl>
          <details className="order-metadata">
            <summary>更多订单信息</summary>
            <dl className="kv">
              {order.businessNo && order.businessNo !== order.orderNo
                ? <><dt>原订单号</dt><dd className="mono">{order.orderNo}</dd></> : null}
              <dt>内部订单编号</dt><dd className="mono">{order.orderId}</dd>
              <dt>商户号</dt><dd className="mono">{order.merchantNo}</dd>
              <dt>完成时间（北京时间）</dt><dd>{formatBeijingTime(order.completedAt)}</dd>
              <dt>取消时间（北京时间）</dt><dd>{formatBeijingTime(order.cancelledAt)}</dd>
              <dt>版本</dt><dd className="mono">{order.version}</dd>
              <dt>读取时间（北京时间）</dt><dd>{formatBeijingTime(order.observedAt)}</dd>
            </dl>
          </details>
        </Panel>
        <nav aria-label="订单信息分区" className="queue-actions">
          {visibleSections.map(({ id, label }) => (
            <Button key={id} tone={active === id ? 'primary' : 'secondary'} aria-pressed={active === id} onClick={() => {
              const next = new URLSearchParams(params)
              if (id === 'items') next.delete('section')
              else next.set('section', id)
              setParams(next, { replace: true, preventScrollReset: true, state: location.state })
            }}>{label}</Button>
          ))}
        </nav>
        {active === 'fulfillment' ? (
          <Panel>
            <h2>履约摘要</h2>
            {order.fulfillment ? (
              <dl className="kv">
                <dt>履约单</dt><dd className="mono">{order.fulfillment.businessNo ?? order.fulfillment.fulfillmentNo}</dd>
                <dt>交付时间（北京时间）</dt><dd>{formatBeijingTime(order.fulfillment.deliveredAt)}</dd>
                <dt>完成时间（北京时间）</dt><dd>{formatBeijingTime(order.fulfillment.completedAt)}</dd>
                <dt>版本</dt><dd className="mono">{order.fulfillment.version}</dd>
              </dl>
            ) : <p>尚无履约记录。</p>}
          </Panel>
        ) : null}
        {active === 'items' ? <Panel className="table-panel">
          <h2 className="table-title">冻结商品</h2>
          <table className="data-table">
            <thead><tr><th>明细编号</th><th>SKU</th><th>SKU 修订</th><th>商品快照标题</th><th>数量</th><th>已验收退货量</th><th>冻结单价</th><th>行应付</th></tr></thead>
            <tbody>
              {order.items.map((item) => (
                <tr key={item.itemId}>
                  <td className="mono">{item.itemId}</td>
                  <td className="mono">{item.skuId}</td>
                  <td className="mono">{item.skuRevision}</td>
                  <td>{item.title}</td>
                  <td>{item.quantity}</td>
                  <td>{item.returnedQuantity}</td>
                  <td>{formatMinor(item.unitPrice, order.currency)}</td>
                  <td>{formatMinor(item.payableAmount, order.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel> : null}
        {active === 'payments' && !detail.isFetching ? (
          <OrderRecords key="payments" orderNo={order.orderNo} resource="admin-payments" title="支付意图" emptyTitle="本单无支付意图" read={api.payments}>
            {(rows) => <PaymentRows rows={rows} />}
          </OrderRecords>
        ) : null}
        {active === 'receipts' && !detail.isFetching ? (
          <OrderRecords key="receipts" orderNo={order.orderNo} resource="admin-receipts" title="实收" emptyTitle="本单无实收" read={api.paymentReceipts}>
            {(rows) => <ReceiptRows rows={rows} />}
          </OrderRecords>
        ) : null}
        {active === 'refunds' && !detail.isFetching ? <OrderRefunds orderNo={order.orderNo} /> : null}
        {active === 'returns' && !detail.isFetching ? (
          <OrderRecords key="returns" orderNo={order.orderNo} resource="admin-returns" title="退货" emptyTitle="本单无退货" read={api.returns}>
            {(rows) => <ReturnRows rows={rows} />}
          </OrderRecords>
        ) : null}
        {active === 'evidence' && !detail.isFetching ? <OrderEvidence orderNo={order.orderNo} /> : null}
      </div>
    </div>
  )
}

// 只挂载当前分区，关闭时由查询层取消不再需要的读取。
function OrderRecords<T>({ orderNo, resource, title, emptyTitle, read, children }: {
  orderNo: string
  resource: string
  title: string
  emptyTitle: string
  read: (query: { orderNo: string; after: string }, signal: AbortSignal) => Promise<{
    items: T[]; nextAfter: string | null; hasMore: boolean; observedAt: string
  }>
  children: (rows: T[]) => ReactNode
}) {
  const [after, setAfter] = useState('0')
  const query = useQuery({
    queryKey: [resource, orderNo, after],
    queryFn: ({ signal }) => read({ orderNo, after }, signal),
    retry: false,
  })
  return (
    <Panel className="table-panel">
      <h2 className="table-title">{title}</h2>
      <PageQueryGate query={query} emptyTitle={emptyTitle} emptyDetail="当前订单没有匹配记录。">
        {(page) => (<>
          {children(page.items)}
          <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
        </>)}
      </PageQueryGate>
    </Panel>
  )
}

function OrderRefunds({ orderNo }: { orderNo: string }) {
  const [after, setAfter] = useState(0)
  const query = useQuery({
    queryKey: ['admin-refunds', orderNo, 'ALL', after],
    queryFn: ({ signal }) => api.refunds({ orderNo, status: 'ALL', after }, signal),
    retry: false,
  })
  return (
    <Panel className="table-panel">
      <h2 className="table-title">退款</h2>
      <QueryGate query={query} emptyTitle="本单无退款" emptyDetail="当前订单没有退款记录。">
        {(rows) => (<>
          <RefundRows rows={rows} />
          <CursorPager after={after} pageSize={20} lastId={rows.at(-1)?.cursor} length={rows.length} onReset={() => setAfter(0)} onNext={setAfter} />
        </>)}
      </QueryGate>
    </Panel>
  )
}

function OrderEvidence({ orderNo }: { orderNo: string }) {
  const query = useQuery({
    queryKey: ['admin-evidence', orderNo],
    queryFn: ({ signal }) => api.orderReferralEvidence(orderNo, signal),
    retry: false,
  })
  return (
    <Panel>
      <h2>交易证据</h2>
      <p>资格核验以对应业务流程的结果为准。</p>
      <QueryGate query={query} emptyTitle="无交易证据" emptyDetail="当前订单没有可展示的交易证据。">
        {(data) => (
          <dl className="kv">
            <dt>政策</dt><dd className="mono">{data.firstOrderPolicy}</dd>
            <dt>首笔已确认支付</dt><dd>{data.isFirstConfirmedPaidOrder ? '是' : '否'}</dd>
            <dt>实付</dt><dd>{formatMinor(data.paidAmount, data.currency)}</dd>
            <dt>已退</dt><dd>{formatMinor(data.refundedAmount, data.currency)}</dd>
            <dt>在途退款</dt><dd>{formatMinor(data.pendingRefundAmount, data.currency)}</dd>
            <dt>净付</dt><dd>{formatMinor(data.netPaidAmount, data.currency)}</dd>
            <dt>保守可计</dt><dd>{formatMinor(data.conservativeEligibleAmount, data.currency)}</dd>
            <dt>读取时间（北京时间）</dt><dd>{formatBeijingTime(data.asOf)}</dd>
          </dl>
        )}
      </QueryGate>
    </Panel>
  )
}

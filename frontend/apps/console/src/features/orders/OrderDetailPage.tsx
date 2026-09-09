import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, CursorPager, PageHeader, PagePager, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { PageQueryGate, QueryGate } from '../../shared/ui/QueryGate'
import { dash, statusTone } from '../../shared/ui/display'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_ADMIN_EVIDENCE_READ, TRADE_FULFILLMENT_WRITE } from '../../shared/auth/auth-context'
import { PaymentRows } from '../payments/PaymentsPage'
import { ReceiptRows } from '../receipts/ReceiptsPage'
import { RefundRows } from '../refunds/RefundRows'
import { ReturnRows } from '../returns/ReturnRows'

export function OrderDetailPage() {
  const { orderNo = '' } = useParams()
  const auth = useAuth()
  const canFulfill = auth.hasScope(TRADE_FULFILLMENT_WRITE)
  const canEvidence = auth.hasScope(TRADE_ADMIN_EVIDENCE_READ)
  const [payAfter, setPayAfter] = useState('0')
  const [receiptAfter, setReceiptAfter] = useState('0')
  const [refundAfter, setRefundAfter] = useState(0)
  const [returnAfter, setReturnAfter] = useState('0')

  const detail = useQuery({
    queryKey: ['admin-order', orderNo],
    queryFn: () => api.order(orderNo),
    retry: false,
    enabled: Boolean(orderNo),
  })
  const payments = useQuery({
    queryKey: ['admin-payments', orderNo, payAfter],
    queryFn: () => api.payments({ orderNo, after: payAfter }),
    retry: false,
    enabled: Boolean(orderNo),
  })
  const receipts = useQuery({
    queryKey: ['admin-receipts', orderNo, receiptAfter],
    queryFn: () => api.paymentReceipts({ orderNo, after: receiptAfter }),
    retry: false,
    enabled: Boolean(orderNo),
  })
  const refunds = useQuery({
    queryKey: ['admin-refunds', orderNo, 'ALL', refundAfter],
    queryFn: () => api.refunds({ orderNo, status: 'ALL', after: refundAfter }),
    retry: false,
    enabled: Boolean(orderNo),
  })
  const returns = useQuery({
    queryKey: ['admin-returns', orderNo, returnAfter],
    queryFn: () => api.returns({ orderNo, after: returnAfter }),
    retry: false,
    enabled: Boolean(orderNo),
  })
  const evidence = useQuery({
    queryKey: ['admin-evidence', orderNo],
    queryFn: () => api.orderReferralEvidence(orderNo),
    retry: false,
    enabled: Boolean(orderNo) && canEvidence,
  })

  if (detail.isLoading) {
    return <div className="workspace"><StateBanner tone="loading" title="正在读取订单" detail={`GET /admin/orders/${orderNo}`} /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }

  const order = detail.data
  const showFulfill = canFulfill && (!order.fulfillment || !order.fulfillment.completedAt)

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Order"
        title={order.orderNo}
        description="CLOSING 不是取消成功。关联表按本单号过滤，不额外拉详情。金额为最小单位整数。"
        actions={showFulfill ? <Link className="button primary" to={`/fulfillment?orderNo=${encodeURIComponent(order.orderNo)}`}>声明履约</Link> : undefined}
      />
      <div className="section-gap">
        <div className="page-grid">
          <Panel>
            <h2>订单</h2>
            <dl className="kv">
              <dt>orderId</dt><dd className="mono">{order.orderId}</dd>
              <dt>会员</dt><dd className="mono">{order.memberId}</dd>
              <dt>报价</dt><dd><Link className="mono" to={`/quotes/${encodeURIComponent(order.quoteId)}`}>{order.quoteId}</Link></dd>
              <dt>商户</dt><dd className="mono">{order.merchantNo}</dd>
              <dt>仓库</dt><dd className="mono">{dash(order.warehouseId)}</dd>
              <dt>状态</dt><dd><Badge tone={statusTone(order.status)}>{order.status}</Badge></dd>
              <dt>财务</dt><dd><Badge tone={statusTone(order.financialStatus)}>{order.financialStatus}</Badge></dd>
              <dt>应付</dt><dd>{formatMinor(order.payableAmount, order.currency)} <small className="mono">{order.currency} {order.payableAmount}</small></dd>
              <dt>paidAt</dt><dd className="mono">{dash(order.paidAt)}</dd>
              <dt>完成</dt><dd className="mono">{dash(order.completedAt)}</dd>
              <dt>取消</dt><dd className="mono">{dash(order.cancelledAt)}</dd>
              <dt>过期</dt><dd className="mono">{order.expiresAt}</dd>
              <dt>版本</dt><dd className="mono">{order.version}</dd>
              <dt>观察时刻</dt><dd className="mono">{order.observedAt}</dd>
            </dl>
          </Panel>
          <Panel>
            <h2>履约摘要</h2>
            {order.fulfillment ? (
              <dl className="kv">
                <dt>履约单</dt><dd className="mono">{order.fulfillment.fulfillmentNo}</dd>
                <dt>已交付</dt><dd className="mono">{order.fulfillment.deliveredAt}</dd>
                <dt>完成</dt><dd className="mono">{dash(order.fulfillment.completedAt)}</dd>
                <dt>版本</dt><dd className="mono">{order.fulfillment.version}</dd>
              </dl>
            ) : <p>尚无履约记录。</p>}
          </Panel>
        </div>
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>冻结商品</h2>
          <table className="data-table">
            <thead><tr><th>明细</th><th>SKU</th><th>标题</th><th>数量</th><th>已退</th><th>应付</th></tr></thead>
            <tbody>
              {order.items.map((item) => (
                <tr key={item.itemId}>
                  <td className="mono">{item.itemId}</td>
                  <td className="mono">{item.skuId} <small>rev {item.skuRevision}</small></td>
                  <td>{item.title}</td>
                  <td>{item.quantity}</td>
                  <td>{item.returnedQuantity}</td>
                  <td>{formatMinor(item.payableAmount, order.currency)}<small className="mono">{item.unitPrice} × {item.quantity}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>支付意图</h2>
          <PageQueryGate query={payments} emptyTitle="本单无支付意图" emptyDetail="没有匹配的支付记录。">
            {(page) => (
              <>
                <PaymentRows rows={page.items} />
                <PagePager after={payAfter} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setPayAfter('0')} onNext={setPayAfter} />
              </>
            )}
          </PageQueryGate>
        </Panel>
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>实收</h2>
          <PageQueryGate query={receipts} emptyTitle="本单无实收" emptyDetail="没有匹配的收款记录。">
            {(page) => (
              <>
                <ReceiptRows rows={page.items} />
                <PagePager after={receiptAfter} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setReceiptAfter('0')} onNext={setReceiptAfter} />
              </>
            )}
          </PageQueryGate>
        </Panel>
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>退款</h2>
          <QueryGate query={refunds} emptyTitle="本单无退款" emptyDetail="status=ALL 下没有退款单。">
            {(rows) => (
              <>
                <RefundRows rows={rows} />
                <CursorPager after={refundAfter} pageSize={20} lastId={rows.at(-1)?.cursor} length={rows.length} onReset={() => setRefundAfter(0)} onNext={setRefundAfter} />
              </>
            )}
          </QueryGate>
        </Panel>
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>退货</h2>
          <PageQueryGate query={returns} emptyTitle="本单无退货" emptyDetail="没有匹配的退货申请。">
            {(page) => (
              <>
                <ReturnRows rows={page.items} />
                <PagePager after={returnAfter} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setReturnAfter('0')} onNext={setReturnAfter} />
              </>
            )}
          </PageQueryGate>
        </Panel>
        {canEvidence ? (
          <Panel>
            <h2>交易证据</h2>
            <p>当前可信交易证据，不能作为营销资格预占。</p>
            {evidence.isLoading ? <StateBanner tone="loading" title="正在读取证据" detail={`GET /admin/orders/${orderNo}/referral-evidence`} /> : null}
            {evidence.isError ? <StateBanner tone="error" title={describeProblem(evidence.error).title} detail={describeProblem(evidence.error).detail} /> : null}
            {evidence.data ? (
              <dl className="kv">
                <dt>政策</dt><dd className="mono">{evidence.data.firstOrderPolicy}</dd>
                <dt>首笔已确认支付</dt><dd>{evidence.data.isFirstConfirmedPaidOrder ? '是' : '否'}</dd>
                <dt>实付</dt><dd>{formatMinor(evidence.data.paidAmount, evidence.data.currency)}</dd>
                <dt>已退</dt><dd>{formatMinor(evidence.data.refundedAmount, evidence.data.currency)}</dd>
                <dt>在途退款</dt><dd>{formatMinor(evidence.data.pendingRefundAmount, evidence.data.currency)}</dd>
                <dt>净付</dt><dd>{formatMinor(evidence.data.netPaidAmount, evidence.data.currency)}</dd>
                <dt>保守可计</dt><dd>{formatMinor(evidence.data.conservativeEligibleAmount, evidence.data.currency)}</dd>
                <dt>asOf</dt><dd className="mono">{evidence.data.asOf}</dd>
              </dl>
            ) : null}
          </Panel>
        ) : null}
      </div>
    </div>
  )
}
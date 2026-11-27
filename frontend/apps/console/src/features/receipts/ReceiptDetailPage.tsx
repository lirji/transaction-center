import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { formatBeijingTime } from '../../shared/ui/display'
import { dispositionLabels } from './receiptDisposition'

export function ReceiptDetailPage() {
  const { receiptNo = '' } = useParams()
  const detail = useQuery({
    queryKey: ['admin-receipt', receiptNo],
    queryFn: () => api.paymentReceipt(receiptNo),
    retry: false,
    enabled: Boolean(receiptNo),
  })

  if (detail.isLoading) {
    return <div className="workspace"><StateBanner tone="loading" title="正在读取实收" detail="正在获取当前记录。" /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }
  const row = detail.data

  return (
    <div className="workspace">
      <PageHeader
        title={row.businessNo ?? row.receiptNo}
        description="unreservedAmount 是尚未被退款预占的余额，不是退款授权。"
      />
      <Panel>
        <h2>实收摘要</h2>
        <dl className="summary-fields">
          <div><dt>处置</dt><dd><Badge tone={row.disposition === 'NORMAL' ? 'good' : 'warn'}>{dispositionLabels[row.disposition]}</Badge></dd></div>
          <div><dt>金额</dt><dd className="summary-amount">{formatMinor(row.amount, row.currency)} <small className="mono">{row.currency} {row.amount}</small></dd></div>
          <div><dt>已退</dt><dd>{formatMinor(row.refundedAmount, row.currency)}</dd></div>
          <div><dt>退款预占</dt><dd>{formatMinor(row.refundReservedAmount, row.currency)}</dd></div>
          <div><dt>未占用</dt><dd>{formatMinor(row.unreservedAmount, row.currency)}</dd></div>
          <div><dt>订单</dt><dd><Link className="mono" to={`/orders/${encodeURIComponent(row.orderNo)}`}>{row.orderBusinessNo ?? row.orderNo}</Link></dd></div>
          <div><dt>支付</dt><dd><Link className="mono" to={`/payments/${encodeURIComponent(row.paymentNo)}`}>{row.paymentBusinessNo ?? row.paymentNo}</Link></dd></div>
          <div><dt>渠道</dt><dd className="mono">{row.channelCode}</dd></div>
        </dl>
        <dl className="kv detail-meta">
          <dt>尝试</dt><dd className="mono">{row.attemptNo}</dd>
          <dt>渠道支付时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.providerPaidAt)}</dd>
          <dt>确认时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.confirmedAt)}</dd>
          <dt>版本</dt><dd className="mono">{row.version}</dd>
          <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.observedAt)}</dd>
        </dl>
      </Panel>
    </div>
  )
}

import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, PageHeader, PagePager, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { dash, statusTone, formatBeijingTime } from '../../shared/ui/display'

export function PaymentDetailPage() {
  const { paymentNo = '' } = useParams()
  const [after, setAfter] = useState('0')
  const [status, setStatus] = useState('')
  const detail = useQuery({
    queryKey: ['admin-payment', paymentNo],
    queryFn: () => api.payment(paymentNo),
    retry: false,
    enabled: Boolean(paymentNo),
  })
  const attempts = useQuery({
    queryKey: ['admin-attempts', paymentNo, after, status],
    queryFn: () => api.paymentAttempts(paymentNo, { after, status: status || undefined }),
    retry: false,
    enabled: Boolean(paymentNo),
  })

  if (detail.isLoading) {
    return <div className="workspace"><StateBanner tone="loading" title="正在读取支付" detail={`GET /admin/payments/${paymentNo}`} /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }
  const payment = detail.data

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Payment"
        title={payment.paymentNo}
        description="UNKNOWN 不等于失败。closeFenceAt 只是本地关闭屏障，不是渠道已关单证明。"
      />
      <div className="section-gap">
        <Panel>
          <dl className="kv">
            <dt>订单</dt><dd><Link className="mono" to={`/orders/${encodeURIComponent(payment.orderNo)}`}>{payment.orderNo}</Link></dd>
            <dt>会员</dt><dd className="mono">{payment.memberId}</dd>
            <dt>状态</dt><dd><Badge tone={statusTone(payment.status)}>{payment.status}</Badge></dd>
            <dt>期望金额</dt><dd>{formatMinor(payment.expectedAmount, payment.currency)} <small className="mono">{payment.currency} {payment.expectedAmount}</small></dd>
            <dt>当前尝试</dt><dd className="mono">{dash(payment.activeAttemptNo)}</dd>
            <dt>成功实收</dt>
            <dd>{payment.succeededReceiptNo
              ? <Link className="mono" to={`/receipts/${encodeURIComponent(payment.succeededReceiptNo)}`}>{payment.succeededReceiptNo}</Link>
              : '—'}</dd>
            <dt>关闭屏障（北京时间）</dt><dd className="mono">{formatBeijingTime(payment.closeFenceAt)}</dd>
            <dt>版本</dt><dd className="mono">{payment.version}</dd>
            <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(payment.observedAt)}</dd>
          </dl>
        </Panel>
        <Panel className="table-panel">
          <div style={{ padding: '16px 18px 0', display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
            <h2>尝试</h2>
            <label>status
              <select value={status} onChange={(event) => { setStatus(event.target.value); setAfter('0') }}>
                <option value="">全部</option>
                <option value="PREPARED">PREPARED</option>
                <option value="UNKNOWN">UNKNOWN</option>
                <option value="SUCCEEDED">SUCCEEDED</option>
                <option value="CLOSED">CLOSED</option>
              </select>
            </label>
          </div>
          <PageQueryGate query={attempts} emptyTitle="没有尝试" emptyDetail="该支付意图没有匹配的尝试记录。">
            {(page) => (
              <>
                <table className="data-table">
                  <thead><tr><th>尝试编号</th><th>尝试 ID</th><th>渠道</th><th>状态</th><th>提交时间（北京时间）</th><th>关闭时间（北京时间）</th><th>关闭证据</th></tr></thead>
                  <tbody>
                    {page.items.map((row) => (
                      <tr key={row.attemptId}>
                        <td className="mono">{row.attemptNo}</td><td className="mono">{row.attemptId}</td>
                        <td className="mono">{row.channelCode}</td>
                        <td><Badge tone={row.status === 'UNKNOWN' ? 'warn' : statusTone(row.status)}>{row.status}</Badge></td>
                        <td className="mono">{formatBeijingTime(row.submittedAt)}</td>
                        <td className="mono">{formatBeijingTime(row.closedAt)}</td>
                        <td>{row.hasCloseEvidence ? '有' : '无'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        </Panel>
      </div>
    </div>
  )
}

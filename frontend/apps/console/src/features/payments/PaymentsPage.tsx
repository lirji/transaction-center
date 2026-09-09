import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api, type AdminPaymentView } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { dash, statusTone } from '../../shared/ui/display'
import { paymentStatusSchema } from '../../shared/api/schemas'

const PAYMENT_STATUSES = paymentStatusSchema.options

export function PaymentRows({ rows }: { rows: AdminPaymentView[] }) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>支付意图</th><th>订单</th><th>状态</th><th>期望金额</th><th>成功实收</th><th>关闭屏障</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.paymentNo}>
            <td>
              <Link to={`/payments/${encodeURIComponent(row.paymentNo)}`} className="mono">{row.paymentNo}</Link>
              <small className="mono">attempt {dash(row.activeAttemptNo)}</small>
            </td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderNo}</Link></td>
            <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
            <td>{formatMinor(row.expectedAmount, row.currency)}<small className="mono">{row.currency} {row.expectedAmount}</small></td>
            <td className="mono">{dash(row.succeededReceiptNo)}</td>
            <td className="mono">{dash(row.closeFenceAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function PaymentsPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const [paymentNo, setPaymentNo] = useState(() => params.get('paymentNo') ?? '')
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [status, setStatus] = useState(() => params.get('status') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [formError, setFormError] = useState('')

  const filters = {
    paymentNo: params.get('paymentNo') || undefined,
    orderNo: params.get('orderNo') || undefined,
    memberId: params.get('memberId') || undefined,
    status: params.get('status') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const memberUrlError = filters.memberId ? (() => {
    try { parseMemberId(filters.memberId); return '' } catch { return 'memberId 须为正整数文本' }
  })() : ''
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)

  const query = useQuery({
    queryKey: ['admin-payments', after, filters],
    queryFn: () => api.payments({ after, ...filters }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  })

  function submit() {
    setFormError('')
    try {
      const window = parseTimeWindow(from, to)
      apply({
        paymentNo: paymentNo.trim() || undefined,
        orderNo: orderNo.trim() || undefined,
        memberId: memberId.trim() ? parseMemberId(memberId) : undefined,
        status: status || undefined,
        createdFrom: window?.createdFrom,
        createdTo: window?.createdTo,
      })
    } catch (cause) {
      setFormError(cause instanceof FormValidationError || cause instanceof Error ? cause.message : '筛选无效')
    }
  }

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Payments"
        title="支付"
        description="查询支付意图。UNKNOWN 尝试不等于失败；closeFenceAt 不是渠道关闭证明。本台不能发起付款跳转。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar onSubmit={submit}>
          <Field label="paymentNo"><input value={paymentNo} onChange={(event) => setPaymentNo(event.target.value)} /></Field>
          <Field label="orderNo"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
          <Field label="memberId"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
          <Field label="status"><EmptySelect value={status} onChange={setStatus} options={PAYMENT_STATUSES} /></Field>
          <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        {memberUrlError || incompleteWindow ? (
          <p className="empty-state" style={{ minHeight: 120 }}>当前筛选无效，未向服务器发送请求。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="没有支付意图" emptyDetail="当前筛选没有支付记录。">
            {(page) => (
              <>
                <PaymentRows rows={page.items} />
                <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}

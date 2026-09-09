import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api, type AdminReceiptView } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { FormValidationError, parseReceiptDispositionFilter, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { receiptDispositionSchema } from '../../shared/api/schemas'

const DISPOSITIONS = receiptDispositionSchema.options

export function ReceiptRows({ rows }: { rows: AdminReceiptView[] }) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>实收</th><th>订单 / 支付</th><th>处置</th><th>金额</th><th>未占用</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.receiptNo}>
            <td>
              <Link to={`/receipts/${encodeURIComponent(row.receiptNo)}`} className="mono">{row.receiptNo}</Link>
              <small className="mono">{row.channelCode}</small>
            </td>
            <td>
              <Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderNo}</Link>
              <small><Link to={`/payments/${encodeURIComponent(row.paymentNo)}`} className="mono">{row.paymentNo}</Link></small>
            </td>
            <td><Badge tone={row.disposition === 'NORMAL' ? 'good' : 'warn'}>{row.disposition}</Badge></td>
            <td>{formatMinor(row.amount, row.currency)}<small className="mono">{row.currency} {row.amount}</small></td>
            <td>{formatMinor(row.unreservedAmount, row.currency)}<small>非退款授权</small></td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function ReceiptsPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const [receiptNo, setReceiptNo] = useState(() => params.get('receiptNo') ?? '')
  const [paymentNo, setPaymentNo] = useState(() => params.get('paymentNo') ?? '')
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [channelCode, setChannelCode] = useState(() => params.get('channelCode') ?? '')
  const [disposition, setDisposition] = useState(() => params.get('disposition') ?? '')
  const [abnormalOnly, setAbnormalOnly] = useState(() => params.get('abnormalOnly') === 'true')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [formError, setFormError] = useState('')

  const conflict = abnormalOnly && disposition === 'NORMAL'
  const filters = {
    receiptNo: params.get('receiptNo') || undefined,
    paymentNo: params.get('paymentNo') || undefined,
    orderNo: params.get('orderNo') || undefined,
    channelCode: params.get('channelCode') || undefined,
    disposition: params.get('disposition') || undefined,
    abnormalOnly: params.get('abnormalOnly') === 'true' || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const urlConflict = filters.abnormalOnly && filters.disposition === 'NORMAL'
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)

  const query = useQuery({
    queryKey: ['admin-receipts', after, filters],
    queryFn: () => api.paymentReceipts({ after, ...filters }),
    retry: false,
    enabled: !urlConflict && !incompleteWindow,
  })

  function submit() {
    setFormError('')
    try {
      const parsed = parseReceiptDispositionFilter(abnormalOnly, disposition)
      const window = parseTimeWindow(from, to)
      apply({
        receiptNo: receiptNo.trim() || undefined,
        paymentNo: paymentNo.trim() || undefined,
        orderNo: orderNo.trim() || undefined,
        channelCode: channelCode.trim() || undefined,
        disposition: parsed.disposition,
        abnormalOnly: parsed.abnormalOnly ? 'true' : undefined,
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
        eyebrow="Receipts"
        title="实收"
        description="查询渠道实收。unreservedAmount 不是退款授权。abnormalOnly 与 disposition=NORMAL 互斥。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar onSubmit={submit}>
          <Field label="receiptNo"><input value={receiptNo} onChange={(event) => setReceiptNo(event.target.value)} /></Field>
          <Field label="paymentNo"><input value={paymentNo} onChange={(event) => setPaymentNo(event.target.value)} /></Field>
          <Field label="orderNo"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
          <Field label="channelCode"><input value={channelCode} onChange={(event) => setChannelCode(event.target.value)} /></Field>
          <Field label="disposition"><EmptySelect value={disposition} onChange={setDisposition} options={DISPOSITIONS} /></Field>
          <Field label="abnormalOnly" error={conflict ? '不能与 disposition=NORMAL 同时使用' : formError || undefined}>
            <select value={abnormalOnly ? 'true' : ''} onChange={(event) => setAbnormalOnly(event.target.value === 'true')}>
              <option value="">否</option>
              <option value="true">仅异常</option>
            </select>
          </Field>
          <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} />
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        {urlConflict || incompleteWindow ? (
          <p className="empty-state" style={{ minHeight: 120 }}>当前筛选无效，未向服务器发送请求。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="没有收款" emptyDetail="当前筛选没有实收记录。">
            {(page) => (
              <>
                <ReceiptRows rows={page.items} />
                <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}
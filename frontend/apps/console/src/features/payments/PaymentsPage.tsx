import { NumberedPagePager } from '../../components/NumberedPager'
import { useNumberedList, pageProjection } from '../../shared/ui/useNumberedList'
import { appliedFilters } from '../../shared/ui/appliedFilters'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Badge, Button, Field, PageHeader, Panel, DataTable, CopyValue } from '../../components/ui'
import { api, type AdminPaymentView } from '../../shared/api/client'
import { formatMinorAmount } from '../../shared/api/money'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch, useListLinkState } from '../../shared/ui/useAdminListSearch'
import { dash, formatBeijingTime, statusTone } from '../../shared/ui/display'
import { paymentStatusSchema, adminPaymentViewSchema } from '../../shared/api/schemas'

const PAYMENT_STATUSES = paymentStatusSchema.options
const paymentLabels: Record<(typeof PAYMENT_STATUSES)[number], string> = {
  READY: '待发起', PENDING: '待确认', SUCCEEDED: '支付成功', CLOSED: '已关闭', REVIEW: '待复核',
}

export function PaymentRows({ rows }: { rows: AdminPaymentView[] }) {
  const listState = useListLinkState('/payments')
  return (
    <DataTable label="支付列表" className="trade-list-table pin-key">
      <thead>
        <tr>
          <th>支付单号</th><th>订单号</th><th>支付状态</th><th className="numeric-cell">期望金额</th><th className="currency-cell">币种</th><th>成功实收单号</th><th className="time-cell">关闭屏障（北京时间）</th><th className="time-cell">更新时间（北京时间）</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.paymentNo}>
            <td>
              <CopyValue value={row.businessNo ?? row.paymentNo} name="支付单号"><Link to={`/payments/${encodeURIComponent(row.paymentNo)}`} state={listState} className="mono">{row.businessNo ?? row.paymentNo}</Link></CopyValue>
            </td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderBusinessNo ?? row.orderNo}</Link></td>
            <td><Badge tone={statusTone(row.status)}>{paymentLabels[row.status]}</Badge></td>
            <td className="numeric-cell">{formatMinorAmount(row.expectedAmount)}</td>
            <td className="currency-cell">{row.currency}</td>
            <td className="mono">{row.succeededReceiptNo ? <Link to={`/receipts/${encodeURIComponent(row.succeededReceiptNo)}`}>{row.succeededReceiptBusinessNo ?? row.succeededReceiptNo}</Link> : dash(row.succeededReceiptNo)}</td>
            <td className="time-cell">{formatBeijingTime(row.closeFenceAt)}</td>
            <td className="time-cell">{formatBeijingTime(row.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  )
}

export function PaymentsPage() {
  const { params, after, page, setPage, apply, remove, setAfter, pageSize, setPageSize } = useAdminListSearch()
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

  const query = useNumberedList({
    queryKey: ['admin-payments', after, pageSize, filters],
    queryFn: () => api.payments({ after, limit: pageSize, ...filters }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  }, { path: 'numbered-pages/payments', schema: adminPaymentViewSchema, after: after, page: page, pageSize: pageSize, filters: filters }, pageProjection)

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

  function resetFilters() {
    setPaymentNo('')
    setOrderNo('')
    setMemberId('')
    setStatus('')
    setFrom('')
    setTo('')
    setFormError('')
    apply({})
  }

  return (
    <div className="workspace queue-workspace">
      <PageHeader
        title="支付"
        description="核对订单支付、发起进度和待复核记录。"
      />
      <Panel className="table-panel queue-results">
        <div className="queue-heading"><h2 className="table-title">支付意图列表</h2><p>按支付单号或订单号定位渠道处理进度。</p></div>
        <div className="queue-filter">
          <div className="queue-actions" role="group" aria-label="支付队列">
            {(['', paymentStatusSchema.enum.PENDING, paymentStatusSchema.enum.REVIEW, paymentStatusSchema.enum.SUCCEEDED] as const).map((value) => (
              <Button key={value} aria-pressed={(filters.status ?? '') === value} onClick={() => {
                setStatus(value)
                setFormError('')
                apply({ ...filters, status: value || undefined })
              }}>{value ? paymentLabels[value] : '全部支付'}</Button>
            ))}
          </div>
          <FilterBar applied={appliedFilters(params, remove, [
            ['支付单号', 'paymentNo', () => setPaymentNo('')],
            ['订单号', 'orderNo', () => setOrderNo('')],
            ['会员 ID', 'memberId', () => setMemberId('')],
            ['支付状态', 'status', () => setStatus(''), (value) => paymentLabels[value as keyof typeof paymentLabels] ?? value],
            ['创建时间', ['createdFrom', 'createdTo'], () => { setFrom(''); setTo('') }],
          ])}
            onSubmit={submit} onReset={resetFilters} invalid={Boolean(formError) || incompleteWindow}
            activeAdvanced={[filters.createdFrom || filters.createdTo].filter(Boolean).length}
            advanced={<>
              <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
            </>}>
            <Field label="支付单号"><input value={paymentNo} onChange={(event) => setPaymentNo(event.target.value)} /></Field>
            <Field label="订单号"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
            <Field label="会员 ID"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
            <Field label="支付状态"><EmptySelect value={status} onChange={setStatus} options={PAYMENT_STATUSES} optionLabel={(value) => paymentLabels[value as keyof typeof paymentLabels]} /></Field>
          </FilterBar>
        </div>
        {memberUrlError || incompleteWindow ? (
          <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
        ) : (
          <>
            <PageQueryGate query={query} emptyTitle="没有支付意图" emptyDetail="当前筛选没有支付记录。">
              {(page) => <PaymentRows rows={page.items} />}
            </PageQueryGate>
            <NumberedPagePager numbered={query.numbered} onNumber={setPage} scope={['payments', filters]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
              result={query.isSuccess ? query.data : undefined} />
          </>
        )}
      </Panel>
    </div>
  )
}

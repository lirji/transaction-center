import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api, type AdminOrderView } from '../../shared/api/client'
import { formatMinorAmount } from '../../shared/api/money'
import { FormValidationError, parseCurrencyCode, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { dash, formatBeijingTime, statusTone } from '../../shared/ui/display'
import { financialStatusSchema, orderStatusSchema } from '../../shared/api/schemas'
import { financialLabels, orderLabels } from './orderStatus'

const sortOptions = { ID_ASC: '编号升序', CREATED_DESC: '创建时间最新优先' } as const
const ORDER_STATUSES = orderStatusSchema.options
const FINANCIAL_STATUSES = financialStatusSchema.options

export function OrderRows({ rows }: { rows: AdminOrderView[] }) {
  const location = useLocation()
  const returnedOrder = location.state?.returnedOrder as string | undefined
  useEffect(() => {
    if (returnedOrder) document.getElementById(`order-row-${returnedOrder}`)?.scrollIntoView?.({ block: 'nearest' })
  }, [returnedOrder, rows])

  return (
    <table className="data-table trade-list-table">
      <thead>
        <tr>
          <th>订单号</th><th>会员 ID</th><th>订单状态</th><th>资金状态</th><th>应付金额</th><th>币种</th><th>仓库 ID</th><th>创建时间（北京时间）</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.orderNo} id={`order-row-${row.orderNo}`}>
            <td>
              <Link to={`/orders/${encodeURIComponent(row.orderNo)}`} state={{ ordersSearch: location.search }} className="mono">{row.businessNo ?? row.orderNo}</Link>
            </td>
            <td className="mono">{row.memberId}</td>
            <td><Badge tone={statusTone(row.status)}>{orderLabels[row.status]}</Badge></td>
            <td><Badge tone={statusTone(row.financialStatus)}>{financialLabels[row.financialStatus]}</Badge></td>
            <td>{formatMinorAmount(row.payableAmount)}</td>
            <td>{row.currency}</td>
            <td>{dash(row.warehouseId)}</td>
            <td>{formatBeijingTime(row.createdAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function OrdersPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const [sort, setSort] = useState(() => params.get('sort') ?? 'ID_ASC')
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [status, setStatus] = useState(() => params.get('status') ?? '')
  const [financialStatus, setFinancialStatus] = useState(() => params.get('financialStatus') ?? '')
  const [currency, setCurrency] = useState(() => params.get('currency') ?? '')
  const [warehouseId, setWarehouseId] = useState(() => params.get('warehouseId') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [formError, setFormError] = useState('')

  const memberDraftError = memberId.trim() && (() => {
    try { parseMemberId(memberId); return '' } catch (cause) { return cause instanceof Error ? cause.message : '无效' }
  })()

  const filters = {
    sort: params.get('sort') || undefined,
    orderNo: params.get('orderNo') || undefined,
    memberId: params.get('memberId') || undefined,
    status: params.get('status') || undefined,
    financialStatus: params.get('financialStatus') || undefined,
    currency: params.get('currency') || undefined,
    warehouseId: params.get('warehouseId') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const memberUrlError = filters.memberId ? (() => {
    try { parseMemberId(filters.memberId); return '' } catch { return 'memberId 须为正整数文本' }
  })() : ''
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)

  const query = useQuery({
    queryKey: ['admin-orders', after, filters],
    queryFn: () => api.orders({ after, ...filters }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  })

  function applyStatus(value: string) {
    setStatus(value)
    apply({ ...filters, status: value || undefined })
  }

  function submit() {
    setFormError('')
    try {
      const window = parseTimeWindow(from, to)
      apply({
        sort: sort === 'ID_ASC' ? undefined : sort,
        orderNo: orderNo.trim() || undefined,
        memberId: memberId.trim() ? parseMemberId(memberId) : undefined,
        status: status || undefined,
        financialStatus: financialStatus || undefined,
        currency: currency.trim() ? parseCurrencyCode(currency) : undefined,
        warehouseId: warehouseId.trim() || undefined,
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
        eyebrow="Orders"
        title="订单"
        description="按订单号、会员或状态查询，可按创建时间查看最近订单。"
      />
      <Panel>
        <h2>筛选</h2>
        <div className="queue-actions" role="group" aria-label="订单状态快捷筛选">
          {[orderStatusSchema.enum.PENDING_PAYMENT, orderStatusSchema.enum.PAID].map((value) => (
            <Button key={value} aria-pressed={filters.status === value} onClick={() => applyStatus(value)}>{orderLabels[value]}</Button>
          ))}
        </div>
        <FilterBar onSubmit={submit}>
          <Field label="订单号" hint="完整订单号、旧编号或日期加四位尾号，例如 261126-1634"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
          <Field label="会员 ID" error={memberDraftError || undefined}><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
          <Field label="订单状态"><EmptySelect value={status} onChange={setStatus} options={ORDER_STATUSES} optionLabel={(value) => orderLabels[value as keyof typeof orderLabels]} /></Field>
          <Field label="资金状态"><EmptySelect value={financialStatus} onChange={setFinancialStatus} options={FINANCIAL_STATUSES} optionLabel={(value) => financialLabels[value as keyof typeof financialLabels]} /></Field>
          <Field label="币种"><input value={currency} onChange={(event) => setCurrency(event.target.value)} maxLength={3} /></Field>
          <Field label="仓库 ID"><input value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} /></Field>
          <Field label="排序">
            <select value={sort} onChange={(event) => setSort(event.target.value)}>
              {Object.entries(sortOptions).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
            </select>
          </Field>
          <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        {memberUrlError || incompleteWindow ? (
          <p className="empty-state" style={{ minHeight: 120 }}>当前筛选无效，未向服务器发送请求。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="当前筛选无订单" emptyDetail="没有匹配的本租户订单。改变筛选会从 after=0 重新读取。">
            {(page) => (
              <>
                <OrderRows rows={page.items} />
                <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}

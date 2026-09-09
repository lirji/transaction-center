import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api, type AdminOrderView } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { statusTone } from '../../shared/ui/display'
import { financialStatusSchema, orderStatusSchema } from '../../shared/api/schemas'

const ORDER_STATUSES = orderStatusSchema.options
const FINANCIAL_STATUSES = financialStatusSchema.options

export function OrderRows({ rows }: { rows: AdminOrderView[] }) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>订单</th><th>会员</th><th>状态</th><th>财务</th><th>应付</th><th>创建</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.orderNo}>
            <td>
              <Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderNo}</Link>
              <small className="mono">{row.orderId}</small>
            </td>
            <td className="mono">{row.memberId}</td>
            <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
            <td><Badge tone={statusTone(row.financialStatus)}>{row.financialStatus}</Badge></td>
            <td>{formatMinor(row.payableAmount, row.currency)}<small className="mono">{row.currency} {row.payableAmount}</small></td>
            <td className="mono">{row.createdAt}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function OrdersPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [status, setStatus] = useState(() => params.get('status') ?? '')
  const [financialStatus, setFinancialStatus] = useState(() => params.get('financialStatus') ?? '')
  const [warehouseId, setWarehouseId] = useState(() => params.get('warehouseId') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [formError, setFormError] = useState('')

  const memberDraftError = memberId.trim() && (() => {
    try { parseMemberId(memberId); return '' } catch (cause) { return cause instanceof Error ? cause.message : '无效' }
  })()

  const filters = {
    orderNo: params.get('orderNo') || undefined,
    memberId: params.get('memberId') || undefined,
    status: params.get('status') || undefined,
    financialStatus: params.get('financialStatus') || undefined,
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

  function submit() {
    setFormError('')
    try {
      const window = parseTimeWindow(from, to)
      apply({
        orderNo: orderNo.trim() || undefined,
        memberId: memberId.trim() ? parseMemberId(memberId) : undefined,
        status: status || undefined,
        financialStatus: financialStatus || undefined,
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
        description="按编号、状态或会员查询本租户订单。列表按主键升序，不是最新优先。零元订单 paidAt 为空是正常投影。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar onSubmit={submit}>
          <Field label="orderNo"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
          <Field label="memberId" error={memberDraftError || undefined}><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
          <Field label="status"><EmptySelect value={status} onChange={setStatus} options={ORDER_STATUSES} /></Field>
          <Field label="financialStatus"><EmptySelect value={financialStatus} onChange={setFinancialStatus} options={FINANCIAL_STATUSES} /></Field>
          <Field label="warehouseId"><input value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} /></Field>
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

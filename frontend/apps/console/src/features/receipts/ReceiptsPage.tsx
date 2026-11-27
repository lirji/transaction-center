import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, Field, PageHeader, PagePager, Panel, DataTable } from '../../components/ui'
import { api, type AdminReceiptView } from '../../shared/api/client'
import { formatMinorAmount } from '../../shared/api/money'
import { FormValidationError, parseReceiptDispositionFilter, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch, useListLinkState } from '../../shared/ui/useAdminListSearch'
import { formatBeijingTime } from '../../shared/ui/display'
import { receiptDispositionSchema } from '../../shared/api/schemas'
import { dispositionLabels } from './receiptDisposition'

const DISPOSITIONS = receiptDispositionSchema.options
const NORMAL_RECEIPT = receiptDispositionSchema.enum.NORMAL

export function ReceiptRows({ rows }: { rows: AdminReceiptView[] }) {
  const listState = useListLinkState('/receipts')
  return (
    <DataTable label="实收列表" className="receipt-table pin-key">
      <thead>
        <tr>
          <th>实收单号</th><th>实收分类</th><th className="numeric-cell">实收金额</th><th>币种</th><th>渠道</th>
          <th>订单号</th><th>支付单号</th><th className="time-cell">确认时间（北京时间）</th><th>未占用金额</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.receiptNo}>
            <td><Link to={`/receipts/${encodeURIComponent(row.receiptNo)}`} state={listState} className="mono">{row.businessNo ?? row.receiptNo}</Link></td>
            <td><Badge tone={row.disposition === NORMAL_RECEIPT ? 'good' : 'warn'}>{dispositionLabels[row.disposition]}</Badge></td>
            <td className="numeric-cell">{formatMinorAmount(row.amount)}</td>
            <td>{row.currency}</td>
            <td>{row.channelCode}</td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderBusinessNo ?? row.orderNo}</Link></td>
            <td><Link to={`/payments/${encodeURIComponent(row.paymentNo)}`} className="mono">{row.paymentBusinessNo ?? row.paymentNo}</Link></td>
            <td className="time-cell">{formatBeijingTime(row.confirmedAt)}</td>
            <td className="numeric-cell">{formatMinorAmount(row.unreservedAmount)}</td>
          </tr>
        ))}
      </tbody>
    </DataTable>
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
  const [businessFrom, setBusinessFrom] = useState(() => isoFromSearch(params, 'confirmedFrom'))
  const [businessTo, setBusinessTo] = useState(() => isoFromSearch(params, 'confirmedTo'))
  const [formError, setFormError] = useState('')

  const conflict = abnormalOnly && disposition === NORMAL_RECEIPT
  const filters = {
    receiptNo: params.get('receiptNo') || undefined,
    paymentNo: params.get('paymentNo') || undefined,
    orderNo: params.get('orderNo') || undefined,
    channelCode: params.get('channelCode') || undefined,
    disposition: params.get('disposition') || undefined,
    abnormalOnly: params.get('abnormalOnly') === 'true' || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
    confirmedFrom: params.get('confirmedFrom') || undefined,
    confirmedTo: params.get('confirmedTo') || undefined,
  }
  const urlConflict = filters.abnormalOnly && filters.disposition === NORMAL_RECEIPT
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo) ||
    Boolean(filters.confirmedFrom) !== Boolean(filters.confirmedTo)

  const query = useQuery({
    queryKey: ['admin-receipts', after, filters],
    queryFn: () => api.paymentReceipts({ after, ...filters }),
    retry: false,
    enabled: !urlConflict && !incompleteWindow,
  })

  function applyPreset(onlyAbnormal: boolean) {
    setFormError('')
    setAbnormalOnly(onlyAbnormal)
    setDisposition('')
    apply({ ...filters, disposition: undefined, abnormalOnly: onlyAbnormal ? 'true' : undefined })
  }

  function submit() {
    setFormError('')
    try {
      const parsed = parseReceiptDispositionFilter(abnormalOnly, disposition)
      const window = parseTimeWindow(from, to)
      const businessWindow = parseTimeWindow(businessFrom, businessTo)
      apply({
        receiptNo: receiptNo.trim() || undefined,
        paymentNo: paymentNo.trim() || undefined,
        orderNo: orderNo.trim() || undefined,
        channelCode: channelCode.trim() || undefined,
        disposition: parsed.disposition,
        abnormalOnly: parsed.abnormalOnly ? 'true' : undefined,
        createdFrom: window?.createdFrom,
        createdTo: window?.createdTo,
        confirmedFrom: businessWindow?.createdFrom,
        confirmedTo: businessWindow?.createdTo,
      })
    } catch (cause) {
      setFormError(cause instanceof FormValidationError || cause instanceof Error ? cause.message : '筛选无效')
    }
  }

  function resetFilters() {
    setReceiptNo('')
    setPaymentNo('')
    setOrderNo('')
    setChannelCode('')
    setDisposition('')
    setAbnormalOnly(false)
    setFrom('')
    setTo('')
    setBusinessFrom('')
    setBusinessTo('')
    setFormError('')
    apply({})
  }

  return (
    <div className="workspace">
      <PageHeader
        title="实收"
        description="查看渠道实际收款和异常分类。未占用金额用于核对资金，退款资格由退款流程判定。"
      />
      <Panel>
        <h2>筛选</h2>
        <div className="queue-actions" role="group" aria-label="实收队列">
          <Button aria-pressed={!filters.abnormalOnly && !filters.disposition} onClick={() => applyPreset(false)}>全部实收</Button>
          <Button aria-pressed={Boolean(filters.abnormalOnly && !filters.disposition)} onClick={() => applyPreset(true)}>异常实收</Button>
        </div>
        <FilterBar onSubmit={submit} onReset={resetFilters} invalid={incompleteWindow || (Boolean(formError) && !conflict)}
          activeAdvanced={[filters.paymentNo, filters.channelCode, filters.createdFrom || filters.createdTo, filters.confirmedFrom || filters.confirmedTo].filter(Boolean).length}
          advanced={<>
            <Field label="支付单号"><input value={paymentNo} onChange={(event) => setPaymentNo(event.target.value)} /></Field>
            <Field label="渠道"><input value={channelCode} onChange={(event) => setChannelCode(event.target.value)} /></Field>
            <TimeRangeFields labelPrefix="登记" from={from} to={to} onFrom={setFrom} onTo={setTo} />
            <TimeRangeFields labelPrefix="确认" from={businessFrom} to={businessTo} onFrom={setBusinessFrom} onTo={setBusinessTo} />
          </>}>
          <Field label="实收单号"><input value={receiptNo} onChange={(event) => setReceiptNo(event.target.value)} /></Field>
          <Field label="订单号"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
          <Field label="实收分类"><EmptySelect value={disposition} onChange={setDisposition} options={DISPOSITIONS} optionLabel={(value) => dispositionLabels[value as keyof typeof dispositionLabels]} /></Field>
          <Field label="异常范围" error={conflict ? '仅异常与正常实收互斥' : formError || undefined}>
            <select value={abnormalOnly ? 'true' : ''} onChange={(event) => setAbnormalOnly(event.target.value === 'true')}>
              <option value="">否</option>
              <option value="true">仅异常</option>
            </select>
          </Field>
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        <h2 className="table-title">实收列表</h2>
        {urlConflict || incompleteWindow ? (
          <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="没有收款" emptyDetail="当前筛选没有实收记录。">
            {(page) => (
              <>
                <ReceiptRows rows={page.items} />
                <PagePager count={page.items.length} after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}
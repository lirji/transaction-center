import { appliedFilters } from '../../shared/ui/appliedFilters'
import { Select } from '../../components/Select'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, Field, Modal, PageHeader, PagePager, Panel, DataTable, CopyValue } from '../../components/ui'
import { api, type AdminReceiptView } from '../../shared/api/client'
import { formatMinor, formatMinorAmount } from '../../shared/api/money'
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
  const [preview, setPreview] = useState<string>()
  const selected = rows.find((row) => row.receiptNo === preview)
  return (
    <>
    <DataTable label="实收列表" className="receipt-table pin-key">
      <thead>
        <tr>
          <th>实收单号</th><th>实收分类</th><th className="numeric-cell">实收金额</th><th>币种</th><th>渠道</th>
          <th>订单号</th><th>支付单号</th><th className="time-cell">确认时间（北京时间）</th><th>未占用金额</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.receiptNo}>
            <td><CopyValue value={row.businessNo ?? row.receiptNo} name="实收单号"><Link to={`/receipts/${encodeURIComponent(row.receiptNo)}`} state={listState} className="mono">{row.businessNo ?? row.receiptNo}</Link></CopyValue></td>
            <td><Badge tone={row.disposition === NORMAL_RECEIPT ? 'good' : 'warn'}>{dispositionLabels[row.disposition]}</Badge></td>
            <td className="numeric-cell">{formatMinorAmount(row.amount)}</td>
            <td>{row.currency}</td>
            <td>{row.channelCode}</td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderBusinessNo ?? row.orderNo}</Link></td>
            <td><Link to={`/payments/${encodeURIComponent(row.paymentNo)}`} className="mono">{row.paymentBusinessNo ?? row.paymentNo}</Link></td>
            <td className="time-cell">{formatBeijingTime(row.confirmedAt)}</td>
            <td className="numeric-cell">{formatMinorAmount(row.unreservedAmount)}</td>
            <td className="row-action-cell"><Button tone="ghost" aria-haspopup="dialog" onClick={() => setPreview(row.receiptNo)}>查看摘要</Button></td>
          </tr>
        ))}
      </tbody>
    </DataTable>
    {selected && <Modal variant="drawer" title={selected.businessNo ?? selected.receiptNo}
      description="实收摘要" onClose={() => setPreview(undefined)}>
      <div className="drawer-body">
        <dl className="summary-fields">
          <div><dt>实收金额</dt><dd className="summary-amount">{formatMinor(selected.amount, selected.currency)}</dd></div>
          <div><dt>实收分类</dt><dd><Badge tone={selected.disposition === NORMAL_RECEIPT ? 'good' : 'warn'}>{dispositionLabels[selected.disposition]}</Badge></dd></div>
          <div><dt>已退金额</dt><dd>{formatMinor(selected.refundedAmount, selected.currency)}</dd></div>
          <div><dt>退款预占</dt><dd>{formatMinor(selected.refundReservedAmount, selected.currency)}</dd></div>
        </dl>
        <dl className="kv detail-meta">
          <dt>渠道</dt><dd>{selected.channelCode}</dd>
          <dt>渠道支付时间（北京时间）</dt><dd>{formatBeijingTime(selected.providerPaidAt)}</dd>
          <dt>确认时间（北京时间）</dt><dd>{formatBeijingTime(selected.confirmedAt)}</dd>
          <dt>订单号</dt><dd><Link to={`/orders/${encodeURIComponent(selected.orderNo)}`}>{selected.orderBusinessNo ?? selected.orderNo}</Link></dd>
        </dl>
      </div>
      <footer className="modal-actions drawer-footer"><Link className="button primary" state={listState}
        to={`/receipts/${encodeURIComponent(selected.receiptNo)}`}>查看完整实收详情</Link></footer>
    </Modal>}
    </>
  )
}

export function ReceiptsPage() {
  const { params, after, apply, remove, setAfter, pageSize, setPageSize } = useAdminListSearch()
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
    queryKey: ['admin-receipts', after, pageSize, filters],
    queryFn: () => api.paymentReceipts({ after, limit: pageSize, ...filters }),
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
        <FilterBar applied={appliedFilters(params, remove, [
          ['实收单号', 'receiptNo', () => setReceiptNo('')],
          ['支付单号', 'paymentNo', () => setPaymentNo('')],
          ['订单号', 'orderNo', () => setOrderNo('')],
          ['渠道', 'channelCode', () => setChannelCode('')],
          ['实收分类', 'disposition', () => setDisposition(''), (value) => dispositionLabels[value as keyof typeof dispositionLabels] ?? value],
          ['异常范围', 'abnormalOnly', () => setAbnormalOnly(false), () => '仅异常'],
          ['登记时间', ['createdFrom', 'createdTo'], () => { setFrom(''); setTo('') }],
          ['确认时间', ['confirmedFrom', 'confirmedTo'], () => { setBusinessFrom(''); setBusinessTo('') }],
        ])}
          onSubmit={submit} onReset={resetFilters} invalid={incompleteWindow || (Boolean(formError) && !conflict)}
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
            <Select value={abnormalOnly ? 'true' : ''} onChange={(value) => setAbnormalOnly(value === 'true')}>
              <option value="">否</option>
              <option value="true">仅异常</option>
            </Select>
          </Field>
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        <h2 className="table-title">实收列表</h2>
        {urlConflict || incompleteWindow ? (
          <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
        ) : (
          <>
            <PageQueryGate query={query} emptyTitle="没有收款" emptyDetail="当前筛选没有实收记录。">
              {(page) => <ReceiptRows rows={page.items} />}
            </PageQueryGate>
            <PagePager scope={['receipts', filters]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
              result={query.isSuccess ? query.data : undefined} />
          </>
        )}
      </Panel>
    </div>
  )
}
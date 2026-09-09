import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, CursorPager, Field, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api, type RefundQueueItem } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { formatMinor, parseInteger } from '../../shared/api/money'
import { describeProblem } from '../../shared/api/client'
import { FormValidationError, parseCurrencyCode, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch } from '../../shared/ui/useAdminListSearch'
import { RefundRows } from './RefundRows'

const STATUSES = ['ALL', 'PENDING_APPROVAL', 'READY', 'SUBMITTED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED'] as const

export function RefundsPage() {
  const [params, setParams] = useSearchParams()
  const status = (params.get('status') as typeof STATUSES[number] | null) || 'PENDING_APPROVAL'
  const [after, setAfter] = useState(0)
  const [refundNo, setRefundNo] = useState(() => params.get('refundNo') ?? '')
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [currency, setCurrency] = useState('CNY')
  const [limit, setLimit] = useState('')
  const [formError, setFormError] = useState('')
  const [filterError, setFilterError] = useState('')
  const queryClient = useQueryClient()

  const filters = {
    status,
    refundNo: params.get('refundNo') || undefined,
    orderNo: params.get('orderNo') || undefined,
    memberId: params.get('memberId') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const memberUrlError = filters.memberId ? (() => {
    try { parseMemberId(filters.memberId); return '' } catch { return 'memberId 须为正整数文本' }
  })() : ''
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)

  const policy = useQuery({ queryKey: ['refund-policy'], queryFn: api.refundPolicy })
  const queue = useQuery({
    queryKey: ['refunds', after, filters],
    queryFn: () => api.refunds({ ...filters, after }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  })
  const update = useMutation({
    mutationFn: api.updateRefundPolicy,
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['refund-policy'] }) },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['refund-policy']),
  })

  function submitPolicy(event: FormEvent) {
    event.preventDefault()
    if (!policy.data) return
    setFormError('')
    try {
      update.mutate({
        currency: parseCurrencyCode(currency || policy.data.currency),
        autoApproveLimit: parseInteger(limit || String(policy.data.autoApproveLimit), '自动批准额度', { min: 0 }),
        expectedVersion: policy.data.version,
      })
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function submitFilters() {
    setFilterError('')
    try {
      const window = parseTimeWindow(from, to)
      const search = new URLSearchParams()
      search.set('status', status)
      if (refundNo.trim()) search.set('refundNo', refundNo.trim())
      if (orderNo.trim()) search.set('orderNo', orderNo.trim())
      if (memberId.trim()) search.set('memberId', parseMemberId(memberId))
      if (window) {
        search.set('createdFrom', window.createdFrom)
        search.set('createdTo', window.createdTo)
      }
      setAfter(0)
      setParams(search, { replace: true })
    } catch (cause) {
      setFilterError(cause instanceof FormValidationError || cause instanceof Error ? cause.message : '筛选无效')
    }
  }

  function changeStatus(next: string) {
    const search = new URLSearchParams(params)
    search.set('status', next)
    search.delete('after')
    setAfter(0)
    setParams(search, { replace: true })
  }

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Refunds"
        title="退款"
        description="队列只读。批准/拒绝需要 trade.refund.approval 与独立签收，本台不提供审批按钮。未配置政策时 version=0、autoApproveLimit=0，必须人工审批。查全部状态须显式选择 ALL。"
      />
      <StateBanner tone="warn" title="不能在此批准退款" detail="OpenAPI 未向本控制台开放 approve/reject。展示金额为最小单位整数，不会改成可付动作。" />
      <MutationBanner error={update.error} success={update.data ? `政策已更新，version=${update.data.version}` : undefined} />
      <div className="page-grid">
        <Panel>
          <h2>退款政策</h2>
          {policy.isLoading ? <StateBanner tone="loading" title="正在读取政策" detail="GET /admin/refund-policy" /> : null}
          {policy.isError ? <StateBanner tone="error" title={describeProblem(policy.error).title} detail={describeProblem(policy.error).detail} /> : null}
          {policy.data ? (
            <StackForm onSubmit={submitPolicy} actions={<Button tone="primary" type="submit" disabled={update.isPending}>保存</Button>}>
              <p>当前版本 {policy.data.version} · 自动批准额度 {formatMinor(policy.data.autoApproveLimit, policy.data.currency)}（展示换算，提交仍用整数）</p>
              <Field label="币种"><input value={currency} placeholder={policy.data.currency} onChange={(event) => setCurrency(event.target.value)} /></Field>
              <Field label="autoApproveLimit（最小单位）" error={formError}>
                <input value={limit} placeholder={String(policy.data.autoApproveLimit)} onChange={(event) => setLimit(event.target.value)} />
              </Field>
            </StackForm>
          ) : null}
        </Panel>
        <Panel>
          <h2>筛选</h2>
          <FilterBar onSubmit={submitFilters}>
            <Field label="状态">
              <select value={status} onChange={(event) => changeStatus(event.target.value)}>
                {STATUSES.map((item) => <option key={item} value={item}>{item}</option>)}
              </select>
            </Field>
            <Field label="refundNo"><input value={refundNo} onChange={(event) => setRefundNo(event.target.value)} /></Field>
            <Field label="orderNo"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
            <Field label="memberId"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
            <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={filterError} />
          </FilterBar>
        </Panel>
      </div>
      <Panel className="table-panel">
        {memberUrlError || incompleteWindow ? (
          <p className="empty-state" style={{ minHeight: 120 }}>当前筛选无效，未向服务器发送请求。</p>
        ) : (
          <QueryGate query={queue} emptyTitle="队列为空" emptyDetail="当前状态下没有退款单。">
            {(rows: RefundQueueItem[]) => (
              <>
                <RefundRows rows={rows} />
                <CursorPager after={after} pageSize={20} lastId={rows.at(-1)?.cursor} length={rows.length} onReset={() => setAfter(0)} onNext={setAfter} />
              </>
            )}
          </QueryGate>
        )}
      </Panel>
    </div>
  )
}

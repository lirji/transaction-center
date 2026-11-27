import { useCallback, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, CursorPager, Field, Modal, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api, type RefundQueueItem } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { formatMinor, parseInteger } from '../../shared/api/money'
import { describeProblem } from '../../shared/api/client'
import { FormValidationError, parseCurrencyCode, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch } from '../../shared/ui/useAdminListSearch'
import { RefundRows } from './RefundRows'
import { refundLabels } from './refundStatus'

const STATUSES = ['ALL', 'PENDING_APPROVAL', 'READY', 'SUBMITTED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED'] as const
const PRESETS = ['PENDING_APPROVAL', 'SUBMITTED', 'SUCCEEDED', 'EXPIRED', 'ALL'] as const
const statusLabel = (value: typeof STATUSES[number]) => value === 'ALL' ? '全部' : refundLabels[value]

export function RefundsPage() {
  const [params, setParams] = useSearchParams()
  const status = STATUSES.find((value) => value === params.get('status')) ?? 'PENDING_APPROVAL'
  const [after, setAfter] = useState(0)
  const [approvalNo, setApprovalNo] = useState(() => params.get('approvalNo') ?? '')
  const [refundNo, setRefundNo] = useState(() => params.get('refundNo') ?? '')
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [businessFrom, setBusinessFrom] = useState(() => isoFromSearch(params, 'approvalDeadlineFrom'))
  const [businessTo, setBusinessTo] = useState(() => isoFromSearch(params, 'approvalDeadlineTo'))
  const [policyOpen, setPolicyOpen] = useState(false)
  const [currency, setCurrency] = useState<string>()
  const [limit, setLimit] = useState<string>()
  const [formError, setFormError] = useState('')
  const [filterError, setFilterError] = useState('')
  const queryClient = useQueryClient()

  const filters = {
    status,
    approvalNo: params.get('approvalNo') || undefined,
    refundNo: params.get('refundNo') || undefined,
    orderNo: params.get('orderNo') || undefined,
    memberId: params.get('memberId') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
    approvalDeadlineFrom: params.get('approvalDeadlineFrom') || undefined,
    approvalDeadlineTo: params.get('approvalDeadlineTo') || undefined,
  }
  const memberUrlError = filters.memberId ? (() => {
    try { parseMemberId(filters.memberId); return '' } catch { return 'memberId 须为正整数文本' }
  })() : ''
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo) ||
    Boolean(filters.approvalDeadlineFrom) !== Boolean(filters.approvalDeadlineTo)

  const policy = useQuery({ queryKey: ['refund-policy'], queryFn: api.refundPolicy, enabled: policyOpen, retry: false })
  const queue = useQuery({
    queryKey: ['refunds', after, filters],
    queryFn: () => api.refunds({ ...filters, after }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  })
  const update = useMutation({
    mutationFn: api.updateRefundPolicy,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['refund-policy'] })
      setCurrency(undefined)
      setLimit(undefined)
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['refund-policy']),
  })

  const closePolicy = useCallback(() => {
    if (!update.isPending) setPolicyOpen(false)
  }, [update.isPending])

  function openPolicy() {
    update.reset()
    setCurrency(undefined)
    setLimit(undefined)
    setFormError('')
    setPolicyOpen(true)
  }

  function submitPolicy(event: FormEvent) {
    event.preventDefault()
    if (!policy.data) return
    setFormError('')
    try {
      update.mutate({
        currency: parseCurrencyCode(currency ?? policy.data.currency),
        autoApproveLimit: parseInteger(limit ?? String(policy.data.autoApproveLimit), '自动批准额度', { min: 0 }),
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
      const businessWindow = parseTimeWindow(businessFrom, businessTo)
      const search = new URLSearchParams()
      search.set('status', status)
      if (approvalNo.trim()) search.set('approvalNo', approvalNo.trim())
      if (refundNo.trim()) search.set('refundNo', refundNo.trim())
      if (orderNo.trim()) search.set('orderNo', orderNo.trim())
      if (memberId.trim()) search.set('memberId', parseMemberId(memberId))
      if (window) {
        search.set('createdFrom', window.createdFrom)
        search.set('createdTo', window.createdTo)
      }
      if (businessWindow) {
        search.set('approvalDeadlineFrom', businessWindow.createdFrom)
        search.set('approvalDeadlineTo', businessWindow.createdTo)
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

  function resetFilters() {
    setApprovalNo('')
    setRefundNo('')
    setOrderNo('')
    setMemberId('')
    setFrom('')
    setTo('')
    setBusinessFrom('')
    setBusinessTo('')
    setFilterError('')
    setAfter(0)
    setParams({}, { replace: true })
  }

  return (
    <div className="workspace">
      <PageHeader
        title="退款"
        description="查询退款进度、审批截止时间及关联订单。"
        actions={<Button aria-haspopup="dialog" onClick={openPolicy}>退款政策</Button>}
      />
      <StateBanner tone="info" title="不能在此批准退款" detail="审批结果由独立审批流程签收，可在此查询退款进度。" />
      <div className="section-gap">
        <Panel>
          <h2>筛选</h2>
          <div className="queue-actions" role="group" aria-label="退款队列">
            {PRESETS.map((value) => <Button key={value} aria-pressed={status === value} onClick={() => changeStatus(value)}>{statusLabel(value)}</Button>)}
          </div>
          <FilterBar onSubmit={submitFilters} onReset={resetFilters} invalid={Boolean(filterError) || incompleteWindow}
            activeAdvanced={[filters.approvalNo, filters.memberId, filters.createdFrom || filters.createdTo, filters.approvalDeadlineFrom || filters.approvalDeadlineTo].filter(Boolean).length}
            advanced={<>
              <Field label="审批单号"><input value={approvalNo} onChange={(event) => setApprovalNo(event.target.value)} /></Field>
              <Field label="会员 ID"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
              <TimeRangeFields labelPrefix="申请" from={from} to={to} onFrom={setFrom} onTo={setTo} error={filterError} />
                <TimeRangeFields labelPrefix="审批截止" from={businessFrom} to={businessTo} onFrom={setBusinessFrom} onTo={setBusinessTo} />
            </>}>
            <Field label="状态">
              <select value={status} onChange={(event) => changeStatus(event.target.value)}>
                {STATUSES.map((item) => <option key={item} value={item}>{statusLabel(item)}</option>)}
              </select>
            </Field>
            <Field label="退款单号"><input value={refundNo} onChange={(event) => setRefundNo(event.target.value)} /></Field>
            <Field label="订单号"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
          </FilterBar>
        </Panel>
        <Panel className="table-panel">
          <h2 className="table-title">退款列表</h2>
          {memberUrlError || incompleteWindow ? (
            <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
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
      {policyOpen ? (
        <Modal title="退款政策" description="设置同一订单的累计免审退款金额上限。额度为零时需审批。" onClose={closePolicy} pending={update.isPending}>
          {policy.isLoading ? <StateBanner tone="loading" title="正在读取政策" detail="请等待载入当前币种和额度。" /> : null}
          {policy.isError ? <StateBanner tone="error" title={describeProblem(policy.error).title} detail={describeProblem(policy.error).detail} /> : null}
          {policy.data ? (
            <StackForm onSubmit={submitPolicy} actions={<Button tone="primary" type="submit" disabled={update.isPending || policy.isFetching}>保存政策</Button>}>
              <MutationBanner error={update.error} success={update.data ? `政策已更新，版本 ${update.data.version}` : undefined} />
              <p>当前版本 {policy.data.version} · 免审额度 {formatMinor(policy.data.autoApproveLimit, policy.data.currency)}</p>
              <Field label="币种"><input value={currency ?? policy.data.currency} onChange={(event) => setCurrency(event.target.value)} /></Field>
              <Field label="免审额度（最小单位）" error={formError}>
                <input inputMode="numeric" value={limit ?? String(policy.data.autoApproveLimit)} onChange={(event) => setLimit(event.target.value)} />
              </Field>
            </StackForm>
          ) : null}
        </Modal>
      ) : null}
    </div>
  )
}

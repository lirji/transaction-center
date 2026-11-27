import { focusWorkSection } from '../../shared/ui/listScroll'
import { WorkSections } from '../../components/WorkspaceNavigation'
import { NumberedPagePager } from '../../components/NumberedPager'
import { useNumberedList, pageProjection } from '../../shared/ui/useNumberedList'
import { adminReturnViewSchema } from '../../shared/api/schemas'
import { appliedFilters } from '../../shared/ui/appliedFilters'
import { Select } from '../../components/Select'
import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, ConfirmDialog, Field, PageHeader, Panel, StackForm, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { MutationBanner, PageQueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { parseInteger, parsePositiveId } from '../../shared/api/money'
import { describeProblem } from '../../shared/api/client'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_RETURN_WRITE } from '../../shared/auth/auth-context'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { dash, formatBeijingTime } from '../../shared/ui/display'
import { ReturnRows } from './ReturnRows'

export function ReturnsPage() {
  const auth = useAuth()
  const canWrite = auth.hasScope(TRADE_RETURN_WRITE)
  const { params, after, page, setPage, apply, remove, setAfter, pageSize, setPageSize } = useAdminListSearch()
  const [lookup, setLookup] = useState('')
  const [activeNo, setActiveNo] = useState('')
  const [orderItemId, setOrderItemId] = useState('')
  const [warehouseId, setWarehouseId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [result, setResult] = useState<'ACCEPTED' | 'PARTIAL' | 'REJECTED'>('ACCEPTED')
  const [restockQuantity, setRestockQuantity] = useState('0')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [formError, setFormError] = useState('')
  const [filterError, setFilterError] = useState('')
  const [confirmInspect, setConfirmInspect] = useState(false)
  const [returnNoFilter, setReturnNoFilter] = useState(() => params.get('returnNo') ?? '')
  const [orderNo, setOrderNo] = useState(() => params.get('orderNo') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [itemId, setItemId] = useState(() => params.get('orderItemId') ?? '')
  const [warehouseFilter, setWarehouseFilter] = useState(() => params.get('warehouseId') ?? '')
  const [status, setStatus] = useState(() => params.get('status') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const queryClient = useQueryClient()

  const filters = {
    returnNo: params.get('returnNo') || undefined,
    orderNo: params.get('orderNo') || undefined,
    memberId: params.get('memberId') || undefined,
    orderItemId: params.get('orderItemId') || undefined,
    warehouseId: params.get('warehouseId') || undefined,
    status: params.get('status') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const memberUrlError = filters.memberId ? (() => {
    try { parseMemberId(filters.memberId); return '' } catch { return 'memberId 须为正整数文本' }
  })() : ''
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)

  const list = useNumberedList({
    queryKey: ['admin-returns', after, pageSize, filters],
    queryFn: () => api.returns({ after, limit: pageSize, ...filters }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  }, { path: 'numbered-pages/returns', schema: adminReturnViewSchema, after: after, page: page, pageSize: pageSize, filters: filters }, pageProjection)
  const detail = useQuery({
    queryKey: ['return', activeNo],
    queryFn: () => api.returnByNo(activeNo),
    enabled: Boolean(activeNo),
    retry: false,
  })
  const create = useMutation({
    mutationFn: api.createReturn,
    onSuccess: async (resource) => {
      setActiveNo(resource.resourceNo)
      await queryClient.invalidateQueries({ queryKey: ['return'] })
      await queryClient.invalidateQueries({ queryKey: ['admin-returns'] })
    },
  })
  const inspect = useMutation({
    mutationFn: (payload: { returnNo: string; version: number; result: 'ACCEPTED' | 'PARTIAL' | 'REJECTED'; restockQuantity: number; evidenceRef: string }) =>
      api.inspectReturn(payload.returnNo, payload.version, {
        result: payload.result,
        restockQuantity: payload.restockQuantity,
        evidenceRef: payload.evidenceRef,
      }),
    onSuccess: async () => {
      setConfirmInspect(false)
      await queryClient.invalidateQueries({ queryKey: ['return', activeNo] })
      await queryClient.invalidateQueries({ queryKey: ['admin-returns'] })
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['return', activeNo]),
  })

  function selectReturn(returnNo: string) {
    setLookup(returnNo)
    setActiveNo(returnNo)
    focusWorkSection('return-lookup')
  }

  function submitLookup(event: FormEvent) {
    event.preventDefault()
    setActiveNo(lookup.trim())
  }

  function submitCreate(event: FormEvent) {
    event.preventDefault()
    setFormError('')
    try {
      parsePositiveId(orderItemId, 'orderItemId')
      parsePositiveId(warehouseId, 'warehouseId')
      create.mutate({
        orderItemId: orderItemId.trim(),
        warehouseId: warehouseId.trim(),
        quantity: parseInteger(quantity, '数量', { min: 1 }),
      })
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function submitInspect(event: FormEvent) {
    event.preventDefault()
    if (!detail.data) return
    setFormError('')
    try {
      parseInteger(restockQuantity, '回库数量', { min: 0 })
      setConfirmInspect(true)
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '输入无效')
    }
  }

  function submitFilters() {
    setFilterError('')
    try {
      const window = parseTimeWindow(from, to)
      apply({
        returnNo: returnNoFilter.trim() || undefined,
        orderNo: orderNo.trim() || undefined,
        memberId: memberId.trim() ? parseMemberId(memberId) : undefined,
        orderItemId: itemId.trim() || undefined,
        warehouseId: warehouseFilter.trim() || undefined,
        status: status || undefined,
        createdFrom: window?.createdFrom,
        createdTo: window?.createdTo,
      })
    } catch (cause) {
      setFilterError(cause instanceof FormValidationError || cause instanceof Error ? cause.message : '筛选无效')
    }
  }

  function resetFilters() {
    setReturnNoFilter('')
    setOrderNo('')
    setMemberId('')
    setItemId('')
    setWarehouseFilter('')
    setStatus('')
    setFrom('')
    setTo('')
    setFilterError('')
    apply({})
  }

  return (
    <div className="workspace queue-workspace">
      <PageHeader
        title="退货"
        description="定位退货单并核对验收记录。实际回库以验收结果为准。"
      />
      <WorkSections sections={[{ id: 'return-queue', label: '退货记录' }, { id: 'return-lookup', label: '查询与验收记录' }, { id: 'return-apply', label: '申请退货' }, { id: 'return-inspect', label: '验收退货' }]} />
      {!canWrite ? <StateBanner tone="warn" title="当前令牌不能申请或验收退货" detail="仍可查询列表与详情。写入需要 trade.return.write。" /> : null}
      <MutationBanner error={create.error ?? inspect.error} success={create.data ? `已申请 ${create.data.resourceNo}` : inspect.data ? '已验收' : undefined} />
      <Panel className="table-panel queue-results" id="return-queue" tabIndex={-1}>
        <div className="queue-heading"><h2 className="table-title">退货结果</h2><p>按退货单号、订单或验收状态定位记录。</p></div>
        <div className="queue-filter">
          <FilterBar applied={appliedFilters(params, remove, [
            ['退货单号', 'returnNo', () => setReturnNoFilter('')],
            ['订单号', 'orderNo', () => setOrderNo('')],
            ['会员 ID', 'memberId', () => setMemberId('')],
            ['订单明细 ID', 'orderItemId', () => setItemId('')],
            ['仓库 ID', 'warehouseId', () => setWarehouseFilter('')],
            ['验收状态', 'status', () => setStatus(''), (value) => value === 'OPEN' ? '待验收' : value === 'INSPECTED' ? '已验收' : value],
            ['创建时间', ['createdFrom', 'createdTo'], () => { setFrom(''); setTo('') }],
          ])}
            onSubmit={submitFilters} onReset={resetFilters} invalid={Boolean(filterError) || incompleteWindow}
            activeAdvanced={[filters.orderItemId, filters.warehouseId, filters.createdFrom || filters.createdTo].filter(Boolean).length}
            advanced={<>
              <Field label="订单明细 ID"><input value={itemId} onChange={(event) => setItemId(event.target.value)} /></Field>
              <Field label="仓库 ID"><input value={warehouseFilter} onChange={(event) => setWarehouseFilter(event.target.value)} /></Field>
              <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={filterError} />
            </>}>
            <Field label="退货单号"><input value={returnNoFilter} onChange={(event) => setReturnNoFilter(event.target.value)} /></Field>
            <Field label="订单号"><input value={orderNo} onChange={(event) => setOrderNo(event.target.value)} /></Field>
            <Field label="会员 ID"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
            <Field label="验收状态"><EmptySelect value={status} onChange={setStatus} options={['OPEN', 'INSPECTED']} /></Field>
          </FilterBar>
        </div>
        {memberUrlError || incompleteWindow ? (
          <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
        ) : (
          <>
            <PageQueryGate query={list} emptyTitle="没有退货申请" emptyDetail="当前筛选没有退货记录。">
              {(page) => <ReturnRows rows={page.items} onSelect={selectReturn} />}
            </PageQueryGate>
            <NumberedPagePager numbered={list.numbered} onNumber={setPage} scope={['returns', filters]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
              result={list.isSuccess ? list.data : undefined} />
          </>
        )}
      </Panel>
      <div className="returns-operations">
        <Panel id="return-lookup" tabIndex={-1}>
          <h2>按单号查询</h2>
          <StackForm onSubmit={submitLookup} actions={<Button tone="primary" type="submit">读取</Button>}>
            <Field label="退货单号"><input value={lookup} onChange={(event) => setLookup(event.target.value)} /></Field>
          </StackForm>
          {detail.isFetching ? <StateBanner tone="loading" title="正在读取退货" detail="正在查找当前退货单及验收记录。" /> : null}
          {detail.isError ? <StateBanner tone="error" title={describeProblem(detail.error).title} detail={describeProblem(detail.error).detail} /> : null}
          {detail.data ? (
            <dl className="kv">
              <dt>申请</dt><dd className="mono">{detail.data.businessNo ?? detail.data.returnNo}</dd>
              <dt>订单</dt><dd className="mono">{detail.data.orderBusinessNo ?? detail.data.orderNo}</dd>
              <dt>会员</dt><dd className="mono">{detail.data.memberId}</dd>
              <dt>订单明细 ID</dt><dd className="mono">{detail.data.orderItemId}</dd><dt>仓库 ID</dt><dd className="mono">{detail.data.warehouseId}</dd>
              <dt>数量</dt><dd>{detail.data.quantity}</dd>
              <dt>状态</dt><dd><Badge>{detail.data.status}</Badge></dd>
              <dt>版本</dt><dd className="mono">{detail.data.version}</dd>
              <dt>实收号</dt><dd>{detail.data.inspection?.businessNo ?? detail.data.receiptNo ?? '尚未验收'}</dd>
              <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(detail.data.observedAt)}</dd>
              {detail.data.inspection ? (
                <>
                  <dt>验收数量</dt><dd>{detail.data.inspection.receivedQuantity}</dd><dt>回库数量</dt><dd>{detail.data.inspection.restockQuantity}</dd>
                  <dt>登记时间（北京时间）</dt><dd className="mono">{formatBeijingTime(detail.data.inspection.recordedAt)}</dd>
                  <dt>验收实收</dt><dd className="mono">{detail.data.inspection.businessNo ?? detail.data.inspection.receiptNo}</dd>
                </>
              ) : <><dt>验收</dt><dd>{dash(null)}</dd></>}
            </dl>
          ) : null}
        </Panel>
        <Panel id="return-apply" tabIndex={-1}>
          <h2>申请退货</h2>
          <StackForm onSubmit={submitCreate} actions={<Button tone="primary" type="submit" loading={create.isPending} disabled={!canWrite}>申请</Button>}>
            <Field label="订单明细 ID" error={formError}><input value={orderItemId} onChange={(event) => setOrderItemId(event.target.value)} required /></Field>
            <Field label="仓库 ID"><input value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)} required /></Field>
            <Field label="申请数量"><input value={quantity} onChange={(event) => setQuantity(event.target.value)} required /></Field>
          </StackForm>
        </Panel>
        <Panel id="return-inspect" tabIndex={-1}>
          <h2>验收</h2>
          {detail.data ? <StackForm onSubmit={submitInspect} actions={<Button tone="primary" type="submit" aria-haspopup="dialog" loading={inspect.isPending} disabled={!canWrite}>提交验收</Button>}>
            <Field label="验收结果">
              <Select value={result} onChange={(value) => setResult(value as typeof result)}>
                <option value="ACCEPTED">验收通过</option>
                <option value="PARTIAL">部分通过</option>
                <option value="REJECTED">拒绝验收</option>
              </Select>
            </Field>
            <Field label="回库数量"><input value={restockQuantity} onChange={(event) => setRestockQuantity(event.target.value)} /></Field>
            <Field label="验收凭证"><input value={evidenceRef} onChange={(event) => setEvidenceRef(event.target.value)} required /></Field>
          </StackForm> : <p>选择一笔退货记录后填写验收结果。</p>}
        </Panel>
      </div>
      {confirmInspect && detail.data ? (
        <ConfirmDialog
          title="确认验收"
          description={`returnNo=${detail.data.businessNo ?? detail.data.returnNo} · expectedVersion=${detail.data.version}。验收才会产生实收事实。`}
          confirmLabel="确认验收"
          tone="primary"
          pending={inspect.isPending}
          onClose={() => setConfirmInspect(false)}
          onConfirm={() => inspect.mutate({
            returnNo: detail.data.returnNo,
            version: detail.data.version,
            result,
            restockQuantity: parseInteger(restockQuantity, '回库数量', { min: 0 }),
            evidenceRef: evidenceRef.trim(),
          })}
        >
          <MutationBanner error={inspect.error} />
          <p>结论 {result} · 回库 {restockQuantity}</p>
        </ConfirmDialog>
      ) : null}
    </div>
  )
}

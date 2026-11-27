import { WorkSections } from '../../components/WorkspaceNavigation'
import { NumberedCursorPager } from '../../components/NumberedPager'
import { reconciliationCaseSchema, reconciliationOperationSchema } from '../../shared/api/schemas'
import { useLocalListPage, useNumberedList, pageItems } from '../../shared/ui/useNumberedList'
import { usePageSize } from '../../shared/ui/usePageSize'
import { Select } from '../../components/Select'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, Field, Modal, PageHeader, Panel, StateBanner, DataTable } from '../../components/ui'
import { api, type ReconciliationCase } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_RECONCILIATION_QUERY } from '../../shared/auth/auth-context'
import { formatBeijingTime } from '../../shared/ui/display'

const queueStates = ['OPEN', 'PROCESSING', 'CLOSED'] as const

export function ReconciliationPage() {
  const auth = useAuth()
  const canQuery = auth.hasScope(TRADE_RECONCILIATION_QUERY)
  const [params, setParams] = useSearchParams()
  const state = queueStates.find((value) => value === params.get('state')) ?? 'OPEN'
  const [opState, setOpState] = useState<'QUEUED' | 'RUNNING' | 'RESULT_ACCEPTED' | 'REVIEW'>('REVIEW')
  const [after, setAfter, page, setPage] = useLocalListPage<number>(0)
  const [pageSize, setPageSize] = usePageSize('recon-cases', 50, () => setAfter(0))
  const [opAfter, setOpAfter, operationPage, setOperationPage] = useLocalListPage<number>(0)
  const [operationSize, setOperationSize] = usePageSize('recon-operations', 50, () => setOpAfter(0))
  const [queryNumber, setQueryNumber] = useState('')
  const [number, setNumber] = useState('')
  const [selected, setSelected] = useState<ReconciliationCase>()
  const [action, setAction] = useState<'QUERY_PAYMENT' | 'QUERY_REFUND'>('QUERY_PAYMENT')
  const [reason, setReason] = useState('')
  const [ticketRef, setTicketRef] = useState('')
  const queryClient = useQueryClient()
  const cases = useNumberedList({ queryKey: ['recon-cases', state, after, pageSize, number], queryFn: () => api.reconciliationCases(state, after, pageSize, number || undefined) }, { path: 'numbered-operations/reconciliation-cases', schema: reconciliationCaseSchema, after: after, page: page, pageSize: pageSize, filters: { state, number: number || undefined } }, pageItems)
  const operations = useNumberedList({ queryKey: ['recon-ops', opState, opAfter, operationSize, number], enabled: cases.isFetched, queryFn: () => api.reconciliationOperations(opState, opAfter, operationSize, number || undefined) }, { path: 'numbered-operations/reconciliation-operations', schema: reconciliationOperationSchema, after: opAfter, page: operationPage, pageSize: operationSize, filters: { state: opState, number: number || undefined } }, pageItems)
  const queryCase = useMutation({
    mutationFn: (payload: { caseNo: string; expectedVersion: number; action: 'QUERY_PAYMENT' | 'QUERY_REFUND'; reason: string; ticketRef: string }) =>
      api.queryReconciliationCase(payload.caseNo, { expectedVersion: payload.expectedVersion, action: payload.action, reason: payload.reason, ticketRef: payload.ticketRef }),
    onSuccess: async () => {
      setSelected(undefined)
      await queryClient.invalidateQueries({ queryKey: ['recon-ops'] })
      await queryClient.invalidateQueries({ queryKey: ['recon-cases'] })
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['recon-cases']),
  })

  return (
    <div className="workspace queue-workspace">
      <PageHeader
        title="对账差异"
        description="核对账务差异，追踪原渠道只查单的受理和执行结果。"
      />
      <WorkSections sections={[{ id: 'reconciliation-queue', label: '差异队列' }, { id: 'reconciliation-history', label: '查单作业' }]} />
      {!canQuery ? <StateBanner tone="warn" title="当前令牌不能只查单" detail="仍可浏览差异列表。写入需要 trade.reconciliation.query。" /> : null}
      <MutationBanner error={queryCase.error} success={queryCase.data ? `已排队 operationNo=${queryCase.data.businessNo ?? queryCase.data.operationNo}，状态 QUEUED` : undefined} />
      <Panel className="table-panel queue-results" id="reconciliation-queue" tabIndex={-1}>
        <div className="queue-heading"><h2 className="table-title">差异队列</h2><p>按差异状态查看待处置记录，或使用编号精确定位。</p></div>
        <div className="queue-filter queue-filter-inline">
          <form className="action-bar" onSubmit={(event) => {
            event.preventDefault()
            setNumber(queryNumber.trim())
            setAfter(0)
            setOpAfter(0)
          }}>
            <Field label="差异或操作编号">
              <input value={queryNumber} maxLength={64} onChange={(event) => setQueryNumber(event.target.value)} />
            </Field>
            <Button type="submit">精确查询</Button>
          </form>
          <Field label="差异状态">
            <Select value={state} onChange={(value) => { setParams({ state: value }, { replace: true }); setAfter(0) }}>
              <option value="OPEN">OPEN</option>
              <option value="PROCESSING">PROCESSING</option>
              <option value="CLOSED">CLOSED</option>
            </Select>
          </Field>
        </div>
        <QueryGate query={cases} emptyTitle="没有差异" emptyDetail="当前状态下没有对账差异。">
          {(rows) => (
            <>
              <DataTable label="对账差异列表" className="trade-list-table pin-actions">
                <thead><tr><th>差异编号</th><th>账单日期</th><th>渠道 ID</th><th>状态</th><th>差异类型</th><th>操作</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.businessNo ?? row.caseNo}</td><td>{row.statementDate}</td>
                      <td className="mono">{row.channelId}</td>
                      <td><Badge>{row.status}</Badge></td>
                      <td><Badge>{row.differenceType}</Badge></td>
                      <td>
                        <Button tone="ghost" aria-haspopup="dialog" disabled={!canQuery} onClick={() => { setSelected(row); setReason(''); setTicketRef('') }}>只查单</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </DataTable>
            </>
          )}
        </QueryGate>
        <NumberedCursorPager numbered={cases.numbered} onNumber={setPage} scope={['recon-cases', state, number]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
          rows={cases.isSuccess ? cases.data : undefined} getCursor={(row) => row.id} />
      </Panel>
      <Panel className="table-panel" id="reconciliation-history" tabIndex={-1}>
        <h2 className="table-title">查单作业</h2>
        <div className="panel-inset">
          <Select value={opState} onChange={(value) => { setOpState(value as typeof opState); setOpAfter(0) }}>
            <option value="REVIEW">REVIEW</option>
            <option value="QUEUED">QUEUED</option>
            <option value="RUNNING">RUNNING</option>
            <option value="RESULT_ACCEPTED">RESULT_ACCEPTED</option>
          </Select>
        </div>
        {cases.isFetched ? <QueryGate query={operations} emptyTitle="没有作业" emptyDetail="当前状态下没有查单作业。">
          {(rows) => (
            <>
              <DataTable label="对账查单操作列表">
                <thead><tr><th>作业编号</th><th>差异编号</th><th>查单动作</th><th>状态</th><th className="time-cell">受理时间（北京时间）</th><th>结果代码</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.businessNo ?? row.operationNo}</td><td className="mono">{row.caseNo}</td>
                      <td>{row.action}</td>
                      <td><Badge>{row.status}</Badge></td>
                      <td className="time-cell">{formatBeijingTime(row.acceptedAt)}</td><td>{row.lastCode ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </DataTable>
            </>
          )}
        </QueryGate> : <StateBanner tone="loading" title="正在读取查单作业" detail="差异队列读取结束后显示查单作业。" />}
        <NumberedCursorPager numbered={operations.numbered} onNumber={setOperationPage} scope={['recon-operations', opState, number]} after={opAfter} onPage={setOpAfter} pageSize={operationSize} onPageSize={setOperationSize}
          rows={operations.isSuccess ? operations.data : undefined} getCursor={(row) => row.id} />
      </Panel>
      {selected && (
        <Modal title="原渠道只查单" description={`caseNo=${selected.caseNo} · version=${selected.version}。202 只表示排队。`} pending={queryCase.isPending} onClose={() => setSelected(undefined)}>
          <form onSubmit={(event) => {
            event.preventDefault()
            queryCase.mutate({
              caseNo: selected.caseNo,
              expectedVersion: selected.version,
              action,
              reason: reason.trim(),
              ticketRef: ticketRef.trim(),
            })
          }}>
            <MutationBanner error={queryCase.error} />
            <Field label="查单动作">
              <Select value={action} onChange={(value) => setAction(value as typeof action)}>
                <option value="QUERY_PAYMENT">QUERY_PAYMENT</option>
                <option value="QUERY_REFUND">QUERY_REFUND</option>
              </Select>
            </Field>
            <Field label="原因"><textarea value={reason} onChange={(event) => setReason(event.target.value)} required /></Field>
            <Field label="工单引用"><input value={ticketRef} onChange={(event) => setTicketRef(event.target.value)} required /></Field>
            <div className="modal-actions">
              <Button onClick={() => setSelected(undefined)} disabled={queryCase.isPending}>取消</Button>
              <Button tone="primary" type="submit" loading={queryCase.isPending}>排队查单</Button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

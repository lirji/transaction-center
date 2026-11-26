import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, CursorPager, Field, Modal, PageHeader, Panel, StateBanner } from '../../components/ui'
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
  const [after, setAfter] = useState(0)
  const [opAfter, setOpAfter] = useState(0)
  const [selected, setSelected] = useState<ReconciliationCase>()
  const [action, setAction] = useState<'QUERY_PAYMENT' | 'QUERY_REFUND'>('QUERY_PAYMENT')
  const [reason, setReason] = useState('')
  const [ticketRef, setTicketRef] = useState('')
  const queryClient = useQueryClient()
  const cases = useQuery({ queryKey: ['recon-cases', state, after], queryFn: () => api.reconciliationCases(state, after) })
  const operations = useQuery({ queryKey: ['recon-ops', opState, opAfter], queryFn: () => api.reconciliationOperations(opState, opAfter) })
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
    <div className="workspace">
      <PageHeader
        eyebrow="Reconciliation"
        title="对账差异"
        description="只查单需要 trade.reconciliation.query，默认可能关闭。202 表示已排队，不是当前执行结果。禁止在此关单、补建或派发退款。"
      />
      {!canQuery ? <StateBanner tone="warn" title="当前令牌不能只查单" detail="仍可浏览差异列表。写入需要 trade.reconciliation.query。" /> : null}
      <MutationBanner error={queryCase.error} success={queryCase.data ? `已排队 operationNo=${queryCase.data.operationNo}，状态 QUEUED` : undefined} />
      <Panel>
        <Field label="差异状态">
          <select value={state} onChange={(event) => { setParams({ state: event.target.value }, { replace: true }); setAfter(0) }}>
            <option value="OPEN">OPEN</option>
            <option value="PROCESSING">PROCESSING</option>
            <option value="CLOSED">CLOSED</option>
          </select>
        </Field>
      </Panel>
      <Panel className="table-panel">
        <QueryGate query={cases} emptyTitle="没有差异" emptyDetail="当前状态下没有对账差异。">
          {(rows) => (
            <>
              <table className="data-table trade-list-table">
                <thead><tr><th>差异编号</th><th>账单日期</th><th>渠道 ID</th><th>状态</th><th>差异类型</th><th>操作</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.caseNo}</td><td>{row.statementDate}</td>
                      <td className="mono">{row.channelId}</td>
                      <td><Badge>{row.status}</Badge></td>
                      <td><Badge>{row.differenceType}</Badge></td>
                      <td>
                        <Button tone="ghost" disabled={!canQuery} onClick={() => { setSelected(row); setReason(''); setTicketRef('') }}>只查单</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <CursorPager after={after} pageSize={50} lastId={rows.at(-1)?.id} length={rows.length} onReset={() => setAfter(0)} onNext={setAfter} />
            </>
          )}
        </QueryGate>
      </Panel>
      <Panel className="table-panel">
        <h2 className="table-title">查单作业</h2>
        <div style={{ padding: '0 18px 8px' }}>
          <select value={opState} onChange={(event) => { setOpState(event.target.value as typeof opState); setOpAfter(0) }}>
            <option value="REVIEW">REVIEW</option>
            <option value="QUEUED">QUEUED</option>
            <option value="RUNNING">RUNNING</option>
            <option value="RESULT_ACCEPTED">RESULT_ACCEPTED</option>
          </select>
        </div>
        <QueryGate query={operations} emptyTitle="没有作业" emptyDetail="当前状态下没有查单作业。">
          {(rows) => (
            <>
              <table className="data-table">
                <thead><tr><th>作业编号</th><th>差异编号</th><th>查单动作</th><th>状态</th><th>受理时间（北京时间）</th><th>结果代码</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.operationNo}</td><td className="mono">{row.caseNo}</td>
                      <td>{row.action}</td>
                      <td><Badge>{row.status}</Badge></td>
                      <td>{formatBeijingTime(row.acceptedAt)}</td><td>{row.lastCode ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <CursorPager after={opAfter} pageSize={50} lastId={rows.at(-1)?.id} length={rows.length} onReset={() => setOpAfter(0)} onNext={setOpAfter} />
            </>
          )}
        </QueryGate>
      </Panel>
      {selected && (
        <Modal title="原渠道只查单" description={`caseNo=${selected.caseNo} · version=${selected.version}。202 只表示排队。`} onClose={() => setSelected(undefined)}>
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
            <Field label="查单动作">
              <select value={action} onChange={(event) => setAction(event.target.value as typeof action)}>
                <option value="QUERY_PAYMENT">QUERY_PAYMENT</option>
                <option value="QUERY_REFUND">QUERY_REFUND</option>
              </select>
            </Field>
            <Field label="原因"><textarea value={reason} onChange={(event) => setReason(event.target.value)} required /></Field>
            <Field label="工单引用"><input value={ticketRef} onChange={(event) => setTicketRef(event.target.value)} required /></Field>
            <div className="modal-actions">
              <Button onClick={() => setSelected(undefined)}>取消</Button>
              <Button tone="primary" type="submit" disabled={queryCase.isPending}>排队查单</Button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

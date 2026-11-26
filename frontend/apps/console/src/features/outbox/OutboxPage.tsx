import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, CursorPager, Field, Modal, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api, type OutboxPublication } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_EVENTS_REPLAY } from '../../shared/auth/auth-context'

const queueStates = ['REVIEW', 'PENDING', 'SENT'] as const

export function OutboxPage() {
  const auth = useAuth()
  const canReplay = auth.hasScope(TRADE_EVENTS_REPLAY)
  const [params, setParams] = useSearchParams()
  const state = queueStates.find((value) => value === params.get('state')) ?? 'REVIEW'
  const [taskState, setTaskState] = useState<'READY' | 'RUNNING' | 'DONE' | 'REVIEW'>('REVIEW')
  const [after, setAfter] = useState(0)
  const [taskAfter, setTaskAfter] = useState(0)
  const [selected, setSelected] = useState<OutboxPublication>()
  const [reason, setReason] = useState('')
  const [ticketRef, setTicketRef] = useState('')
  const queryClient = useQueryClient()
  const publications = useQuery({ queryKey: ['outbox', state, after], queryFn: () => api.outboxPublications(state, after) })
  const tasks = useQuery({ queryKey: ['outbox-tasks', taskState, taskAfter], queryFn: () => api.outboxTasks(taskState, taskAfter) })
  const replay = useMutation({
    mutationFn: (payload: { eventId: string; expectedVersion: number; reason: string; ticketRef: string }) =>
      api.replayOutbox(payload.eventId, { expectedVersion: payload.expectedVersion, reason: payload.reason, ticketRef: payload.ticketRef }),
    onSuccess: async () => {
      setSelected(undefined)
      await queryClient.invalidateQueries({ queryKey: ['outbox'] })
      await queryClient.invalidateQueries({ queryKey: ['outbox-tasks'] })
    },
    onError: (error) => void invalidateIfVersionConflict(queryClient, error, ['outbox']),
  })

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Outbox"
        title="发布隔离"
        description="列表不含 payload。重放同时要求 trade.admin 与 trade.events.replay，且仅 REVIEW 匹配版本可重放。重放不改变资金事实，返回成功不代表已发送。"
      />
      {!canReplay ? <StateBanner tone="warn" title="当前令牌不能重放" detail="缺少 trade.events.replay。仍可查看队列。" /> : null}
      <MutationBanner error={replay.error} success={replay.data ? `已受理重放 eventId=${replay.data.eventId} replayCount=${replay.data.replayCount}` : undefined} />
      <Panel>
        <Field label="发布状态">
          <select value={state} onChange={(event) => { setParams({ state: event.target.value }, { replace: true }); setAfter(0) }}>
            <option value="REVIEW">REVIEW</option>
            <option value="PENDING">PENDING</option>
            <option value="SENT">SENT</option>
          </select>
        </Field>
      </Panel>
      <Panel className="table-panel">
        <QueryGate query={publications} emptyTitle="没有发布记录" emptyDetail="当前状态下没有 Outbox 元数据。">
          {(rows) => (
            <>
              <table className="data-table">
                <thead><tr><th>事件</th><th>状态</th><th>失败</th><th /></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td><strong>{row.eventType}</strong><small className="mono">{row.eventId}</small></td>
                      <td><Badge tone={row.status === 'REVIEW' ? 'warn' : 'neutral'}>{row.status}</Badge></td>
                      <td>{row.lastError ?? '—'}<small>replay {row.replayCount}</small></td>
                      <td>
                        <Button
                          tone="ghost"
                          disabled={!canReplay || row.status !== 'REVIEW'}
                          onClick={() => { setSelected(row); setReason(''); setTicketRef('') }}
                        >
                          重放
                        </Button>
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
        <h2 style={{ padding: '16px 18px 0' }}>任务</h2>
        <div style={{ padding: '0 18px' }}>
          <select value={taskState} onChange={(event) => { setTaskState(event.target.value as typeof taskState); setTaskAfter(0) }}>
            <option value="REVIEW">REVIEW</option>
            <option value="READY">READY</option>
            <option value="RUNNING">RUNNING</option>
            <option value="DONE">DONE</option>
          </select>
        </div>
        <QueryGate query={tasks} emptyTitle="没有任务" emptyDetail="当前状态下没有发布任务。">
          {(rows) => (
            <>
              <table className="data-table">
                <thead><tr><th>任务</th><th>状态</th><th>尝试</th><th>错误</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.eventId ?? row.referenceHash}</td>
                      <td><Badge>{row.status}</Badge></td>
                      <td>{row.attempts}</td>
                      <td>{row.lastError ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <CursorPager after={taskAfter} pageSize={50} lastId={rows.at(-1)?.id} length={rows.length} onReset={() => setTaskAfter(0)} onNext={setTaskAfter} />
            </>
          )}
        </QueryGate>
      </Panel>
      {selected && (
        <Modal title="受控重放" description={`eventId=${selected.eventId} · expectedVersion=${selected.version}。ticketRef 不代表自动开单。`} onClose={() => setSelected(undefined)}>
          <form onSubmit={(event) => {
            event.preventDefault()
            replay.mutate({ eventId: selected.eventId, expectedVersion: selected.version, reason: reason.trim(), ticketRef: ticketRef.trim() })
          }}>
            <Field label="原因"><textarea value={reason} onChange={(event) => setReason(event.target.value)} required /></Field>
            <Field label="ticketRef"><input value={ticketRef} onChange={(event) => setTicketRef(event.target.value)} required /></Field>
            <div className="modal-actions">
              <Button onClick={() => setSelected(undefined)}>取消</Button>
              <Button tone="primary" type="submit" disabled={replay.isPending}>确认重放</Button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

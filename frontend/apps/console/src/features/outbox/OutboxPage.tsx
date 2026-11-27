import { WorkSections } from '../../components/WorkspaceNavigation'
import { NumberedCursorPager } from '../../components/NumberedPager'
import { outboxPublicationSchema, outboxTaskSchema } from '../../shared/api/schemas'
import { useLocalListPage, useNumberedList, pageItems } from '../../shared/ui/useNumberedList'
import { usePageSize } from '../../shared/ui/usePageSize'
import { Select } from '../../components/Select'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, Field, Modal, PageHeader, Panel, StateBanner, DataTable } from '../../components/ui'
import { api, type OutboxPublication } from '../../shared/api/client'
import { MutationBanner, QueryGate } from '../../shared/ui/QueryGate'
import { invalidateIfVersionConflict } from '../../shared/ui/invalidateIfVersionConflict'
import { useAuth } from '../../shared/auth/useAuth'
import { TRADE_EVENTS_REPLAY } from '../../shared/auth/auth-context'
import { formatBeijingTime } from '../../shared/ui/display'

const queueStates = ['REVIEW', 'PENDING', 'SENT'] as const

export function OutboxPage() {
  const auth = useAuth()
  const canReplay = auth.hasScope(TRADE_EVENTS_REPLAY)
  const [params, setParams] = useSearchParams()
  const state = queueStates.find((value) => value === params.get('state')) ?? 'REVIEW'
  const [taskState, setTaskState] = useState<'READY' | 'RUNNING' | 'DONE' | 'REVIEW'>('REVIEW')
  const [after, setAfter, page, setPage] = useLocalListPage<number>(0)
  const [pageSize, setPageSize] = usePageSize('outbox', 50, () => setAfter(0))
  const [taskAfter, setTaskAfter, taskPage, setTaskPage] = useLocalListPage<number>(0)
  const [taskSize, setTaskSize] = usePageSize('outbox-tasks', 50, () => setTaskAfter(0))
  const [selected, setSelected] = useState<OutboxPublication>()
  const [reason, setReason] = useState('')
  const [ticketRef, setTicketRef] = useState('')
  const queryClient = useQueryClient()
  const publications = useNumberedList({ queryKey: ['outbox', state, after, pageSize], queryFn: () => api.outboxPublications(state, after, pageSize) }, { path: 'numbered-operations/outbox-publications', schema: outboxPublicationSchema, after: after, page: page, pageSize: pageSize, filters: { state } }, pageItems)
  const tasks = useNumberedList({ queryKey: ['outbox-tasks', taskState, taskAfter, taskSize], enabled: publications.isFetched, queryFn: () => api.outboxTasks(taskState, taskAfter, taskSize) }, { path: 'numbered-operations/outbox-tasks', schema: outboxTaskSchema, after: taskAfter, page: taskPage, pageSize: taskSize, filters: { state: taskState } }, pageItems)
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
    <div className="workspace queue-workspace">
      <PageHeader
        title="发布隔离"
        description="查看事件来源和发布状态，追踪已经受理的重放任务。"
      />
      <WorkSections sections={[{ id: 'outbox-queue', label: '发布事件' }, { id: 'outbox-history', label: '维护任务' }]} />
      {!canReplay ? <StateBanner tone="warn" title="当前令牌不能重放" detail="缺少 trade.events.replay。仍可查看队列。" /> : null}
      <MutationBanner error={replay.error} success={replay.data ? `已受理重放 eventId=${replay.data.eventId} replayCount=${replay.data.replayCount}` : undefined} />
      <Panel className="table-panel queue-results" id="outbox-queue" tabIndex={-1}>
        <div className="queue-heading"><h2 className="table-title">发布事件</h2><p>按发布状态筛选后核对事件与业务对象。</p></div>
        <div className="queue-filter queue-filter-inline">
          <Field label="发布状态">
            <Select value={state} onChange={(value) => { setParams({ state: value }, { replace: true }); setAfter(0) }}>
              <option value="REVIEW">REVIEW</option>
              <option value="PENDING">PENDING</option>
              <option value="SENT">SENT</option>
            </Select>
          </Field>
        </div>
        <QueryGate query={publications} emptyTitle="没有发布记录" emptyDetail="当前状态下没有 Outbox 元数据。">
          {(rows) => (
            <>
              <DataTable label="事件发布列表" className="trade-list-table pin-actions">
                <thead><tr><th>事件编号</th><th>事件类型</th><th>业务对象 ID</th><th>发布状态</th><th>失败次数</th><th className="time-cell">最近失败（北京时间）</th><th>最近错误</th><th>重放次数</th><th>操作</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.eventId}</td>
                      <td>{row.eventType}</td><td className="mono">{row.aggregateId}</td>
                      <td><Badge tone={row.status === 'REVIEW' ? 'warn' : 'neutral'}>{row.status}</Badge></td>
                      <td>{row.failureCount}</td><td className="time-cell">{formatBeijingTime(row.lastFailureAt)}</td><td>{row.lastError ?? '—'}</td><td>{row.replayCount}</td>
                      <td>
                        <Button
                          tone="ghost"
                          aria-haspopup="dialog"
                          disabled={!canReplay || row.status !== 'REVIEW'}
                          onClick={() => { setSelected(row); setReason(''); setTicketRef('') }}
                        >
                          重放
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </DataTable>
            </>
          )}
        </QueryGate>
        <NumberedCursorPager numbered={publications.numbered} onNumber={setPage} scope={['outbox', state]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
          rows={publications.isSuccess ? publications.data : undefined} getCursor={(row) => row.id} />
      </Panel>
      <Panel className="table-panel" id="outbox-history" tabIndex={-1}>
        <h2 className="table-title">维护任务</h2>
        <div className="panel-inset">
          <Select value={taskState} onChange={(value) => { setTaskState(value as typeof taskState); setTaskAfter(0) }}>
            <option value="REVIEW">REVIEW</option>
            <option value="READY">READY</option>
            <option value="RUNNING">RUNNING</option>
            <option value="DONE">DONE</option>
          </Select>
        </div>
        {publications.isFetched ? <QueryGate query={tasks} emptyTitle="没有任务" emptyDetail="当前状态下没有发布任务。">
          {(rows) => (
            <>
              <DataTable label="重放任务列表">
                <thead><tr><th>任务编号</th><th>事件编号</th><th>引用摘要</th><th>状态</th><th>尝试次数</th><th className="time-cell">下次运行（北京时间）</th><th>最近错误</th></tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.id}</td><td>{row.eventId ?? '—'}</td><td className="mono">{row.referenceHash}</td>
                      <td><Badge>{row.status}</Badge></td>
                      <td>{row.attempts}</td><td className="time-cell">{formatBeijingTime(row.nextRunAt)}</td>
                      <td>{row.lastError ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </DataTable>
            </>
          )}
        </QueryGate> : <StateBanner tone="loading" title="正在读取维护任务" detail="事件队列读取结束后显示维护任务。" />}
        <NumberedCursorPager numbered={tasks.numbered} onNumber={setTaskPage} scope={['outbox-tasks', taskState]} after={taskAfter} onPage={setTaskAfter} pageSize={taskSize} onPageSize={setTaskSize}
          rows={tasks.isSuccess ? tasks.data : undefined} getCursor={(row) => row.id} />
      </Panel>
      {selected && (
        <Modal title="受控重放" description={`eventId=${selected.eventId} · expectedVersion=${selected.version}。ticketRef 不代表自动开单。`} pending={replay.isPending} onClose={() => setSelected(undefined)}>
          <form onSubmit={(event) => {
            event.preventDefault()
            replay.mutate({ eventId: selected.eventId, expectedVersion: selected.version, reason: reason.trim(), ticketRef: ticketRef.trim() })
          }}>
            <MutationBanner error={replay.error} />
            <Field label="原因"><textarea value={reason} onChange={(event) => setReason(event.target.value)} required /></Field>
            <Field label="工单引用"><input value={ticketRef} onChange={(event) => setTicketRef(event.target.value)} required /></Field>
            <div className="modal-actions">
              <Button onClick={() => setSelected(undefined)} disabled={replay.isPending}>取消</Button>
              <Button tone="primary" type="submit" loading={replay.isPending}>确认重放</Button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

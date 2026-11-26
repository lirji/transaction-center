import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api, type AdminJobView } from '../../shared/api/client'
import { FormValidationError, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { statusTone, formatBeijingTime } from '../../shared/ui/display'
import { jobStatusSchema, jobTypeSchema } from '../../shared/api/schemas'

const JOB_TYPES = jobTypeSchema.options
const JOB_STATUSES = jobStatusSchema.options

export function JobRows({ rows }: { rows: AdminJobView[] }) {
  return (
    <table className="data-table trade-list-table">
      <thead><tr><th>任务编号</th><th>任务类型</th><th>状态</th><th>尝试次数</th><th>租约过期</th><th>下次运行（北京时间）</th><th>最近错误</th><th>更新时间（北京时间）</th></tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.jobId}>
            <td><Link to={`/jobs/${encodeURIComponent(row.jobId)}`} className="mono">{row.jobId}</Link></td>
            <td className="mono">{row.jobType}</td>
            <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
            <td>{row.attempts}</td>
            <td>{row.leaseExpired ? '是' : '否'}</td>
            <td>{formatBeijingTime(row.nextRunAt)}</td><td>{row.lastError ?? '—'}</td><td>{formatBeijingTime(row.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function JobsPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const [callbackNo, setCallbackNo] = useState(() => params.get('callbackNo') ?? '')
  const [jobType, setJobType] = useState(() => params.get('jobType') ?? '')
  const [status, setStatus] = useState(() => params.get('status') ?? '')
  const [leaseExpired, setLeaseExpired] = useState(() => params.get('leaseExpired') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [formError, setFormError] = useState('')
  const filters = {
    callbackNo: params.get('callbackNo') || undefined,
    jobType: params.get('jobType') || undefined,
    status: params.get('status') || undefined,
    leaseExpired: params.get('leaseExpired') === 'true' ? true : params.get('leaseExpired') === 'false' ? false : undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)
  const query = useQuery({
    queryKey: ['admin-jobs', after, filters],
    queryFn: () => api.jobs({ after, ...filters }),
    retry: false,
    enabled: !incompleteWindow,
  })

  function submit() {
    setFormError('')
    try {
      const window = parseTimeWindow(from, to)
      apply({
        callbackNo: callbackNo.trim() || undefined,
        jobType: jobType || undefined,
        status: status || undefined,
        leaseExpired: leaseExpired || undefined,
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
        eyebrow="Jobs"
        title="任务"
        description="只读观察任务租约。leaseExpired 是观察值，本台不能领取、重试或解锁。不要与 Outbox 页混淆。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar onSubmit={submit}>
          <Field label="回调接收号"><input value={callbackNo} onChange={(event) => setCallbackNo(event.target.value)} /></Field>
          <Field label="任务类型"><EmptySelect value={jobType} onChange={setJobType} options={JOB_TYPES} /></Field>
          <Field label="状态"><EmptySelect value={status} onChange={setStatus} options={JOB_STATUSES} /></Field>
          <Field label="租约过期">
            <select value={leaseExpired} onChange={(event) => setLeaseExpired(event.target.value)}>
              <option value="">全部</option>
              <option value="true">已过期</option>
              <option value="false">未过期</option>
            </select>
          </Field>
          <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        {incompleteWindow ? (
          <p className="empty-state" style={{ minHeight: 120 }}>当前筛选无效，未向服务器发送请求。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="没有任务" emptyDetail="当前筛选没有任务记录。">
            {(page) => (
              <>
                <JobRows rows={page.items} />
                <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}
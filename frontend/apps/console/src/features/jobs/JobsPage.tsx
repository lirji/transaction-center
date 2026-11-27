import { NumberedPagePager } from '../../components/NumberedPager'
import { useNumberedList, pageProjection } from '../../shared/ui/useNumberedList'
import { appliedFilters } from '../../shared/ui/appliedFilters'
import { Select } from '../../components/Select'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Badge, Field, PageHeader, Panel, DataTable, CopyValue } from '../../components/ui'
import { api, type AdminJobView } from '../../shared/api/client'
import { FormValidationError, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch, useListLinkState } from '../../shared/ui/useAdminListSearch'
import { statusTone, formatBeijingTime } from '../../shared/ui/display'
import { jobStatusSchema, jobTypeSchema, adminJobViewSchema } from '../../shared/api/schemas'

const JOB_TYPES = jobTypeSchema.options
const JOB_STATUSES = jobStatusSchema.options

export function JobRows({ rows }: { rows: AdminJobView[] }) {
  const listState = useListLinkState('/jobs')
  return (
    <DataTable label="任务列表" className="trade-list-table pin-key">
      <thead><tr><th>任务编号</th><th>任务类型</th><th>状态</th><th>尝试次数</th><th>租约过期</th><th className="time-cell">下次运行（北京时间）</th><th>最近错误</th><th className="time-cell">更新时间（北京时间）</th></tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.jobId}>
            <td><CopyValue value={row.jobId} name="任务编号"><Link to={`/jobs/${encodeURIComponent(row.jobId)}`} state={listState} className="mono">{row.jobId}</Link></CopyValue></td>
            <td className="mono">{row.jobType}</td>
            <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
            <td>{row.attempts}</td>
            <td>{row.leaseExpired ? '是' : '否'}</td>
            <td className="time-cell">{formatBeijingTime(row.nextRunAt)}</td><td>{row.lastError ?? '—'}</td><td className="time-cell">{formatBeijingTime(row.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  )
}

export function JobsPage() {
  const { params, after, page, setPage, apply, remove, setAfter, pageSize, setPageSize } = useAdminListSearch()
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
  const query = useNumberedList({
    queryKey: ['admin-jobs', after, pageSize, filters],
    queryFn: () => api.jobs({ after, limit: pageSize, ...filters }),
    retry: false,
    enabled: !incompleteWindow,
  }, { path: 'numbered-pages/jobs', schema: adminJobViewSchema, after: after, page: page, pageSize: pageSize, filters: filters }, pageProjection)

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

  function resetFilters() {
    setCallbackNo('')
    setJobType('')
    setStatus('')
    setLeaseExpired('')
    setFrom('')
    setTo('')
    setFormError('')
    apply({})
  }

  return (
    <div className="workspace">
      <PageHeader
        title="任务"
        description="只读观察任务租约。leaseExpired 是观察值，本台不能领取、重试或解锁。不要与 Outbox 页混淆。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar applied={appliedFilters(params, remove, [
          ['回调接收号', 'callbackNo', () => setCallbackNo('')],
          ['任务类型', 'jobType', () => setJobType('')],
          ['状态', 'status', () => setStatus('')],
          ['租约过期', 'leaseExpired', () => setLeaseExpired(''), (value) => value === 'true' ? '已过期' : '未过期'],
          ['创建时间', ['createdFrom', 'createdTo'], () => { setFrom(''); setTo('') }],
        ])}
          onSubmit={submit} onReset={resetFilters} invalid={Boolean(formError) || incompleteWindow}
          activeAdvanced={[params.get('leaseExpired'), filters.createdFrom || filters.createdTo].filter(Boolean).length}
          advanced={<>
            <Field label="租约过期">
            <Select value={leaseExpired} onChange={(value) => setLeaseExpired(value)}>
              <option value="">全部</option>
              <option value="true">已过期</option>
              <option value="false">未过期</option>
            </Select>
          </Field>
            <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
          </>}>
          <Field label="回调接收号"><input value={callbackNo} onChange={(event) => setCallbackNo(event.target.value)} /></Field>
          <Field label="任务类型"><EmptySelect value={jobType} onChange={setJobType} options={JOB_TYPES} /></Field>
          <Field label="状态"><EmptySelect value={status} onChange={setStatus} options={JOB_STATUSES} /></Field>
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        <h2 className="table-title">任务列表</h2>
        {incompleteWindow ? (
          <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
        ) : (
          <>
            <PageQueryGate query={query} emptyTitle="没有任务" emptyDetail="当前筛选没有任务记录。">
              {(page) => <JobRows rows={page.items} />}
            </PageQueryGate>
            <NumberedPagePager numbered={query.numbered} onNumber={setPage} scope={['jobs', filters]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
              result={query.isSuccess ? query.data : undefined} />
          </>
        )}
      </Panel>
    </div>
  )
}
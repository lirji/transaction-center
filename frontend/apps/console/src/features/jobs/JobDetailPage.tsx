import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { dash, statusTone } from '../../shared/ui/display'

function referencePath(resourceType: string, resourceNo: string) {
  if (resourceType === 'ORDER') return `/orders/${encodeURIComponent(resourceNo)}`
  if (resourceType === 'PAYMENT') return `/payments/${encodeURIComponent(resourceNo)}`
  if (resourceType === 'REFUND') return `/refunds/${encodeURIComponent(resourceNo)}`
  return undefined
}

export function JobDetailPage() {
  const { jobId = '' } = useParams()
  const detail = useQuery({
    queryKey: ['admin-job', jobId],
    queryFn: () => api.job(jobId),
    retry: false,
    enabled: Boolean(jobId),
  })

  if (detail.isLoading) {
    return <div className="workspace"><StateBanner tone="loading" title="正在读取任务" detail={`GET /admin/jobs/${jobId}`} /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }
  const row = detail.data
  const href = row.businessReference ? referencePath(row.businessReference.resourceType, row.businessReference.resourceNo) : undefined

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Job"
        title={row.jobId}
        description="只读。leaseExpired 是观察值，不能在此重试或解锁。"
      />
      <Panel>
        <dl className="kv">
          <dt>类型</dt><dd className="mono">{row.jobType}</dd>
          <dt>状态</dt><dd><Badge tone={statusTone(row.status)}>{row.status}</Badge></dd>
          <dt>尝试</dt><dd>{row.attempts}</dd>
          <dt>下次运行</dt><dd className="mono">{row.nextRunAt}</dd>
          <dt>租约至</dt><dd className="mono">{dash(row.leaseUntil)}</dd>
          <dt>租约过期</dt><dd>{row.leaseExpired ? '是（观察值）' : '否'}</dd>
          <dt>最近错误</dt><dd>{dash(row.lastError)}</dd>
          <dt>业务引用</dt>
          <dd>{row.businessReference
            ? href
              ? <Link className="mono" to={href}>{row.businessReference.resourceType} {row.businessReference.resourceNo}</Link>
              : <span className="mono">{row.businessReference.resourceType} {row.businessReference.resourceNo}</span>
            : '—'}</dd>
          <dt>版本</dt><dd className="mono">{row.version}</dd>
          <dt>观察时刻</dt><dd className="mono">{row.observedAt}</dd>
        </dl>
      </Panel>
    </div>
  )
}

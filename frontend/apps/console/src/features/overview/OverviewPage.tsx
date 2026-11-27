import { ArrowRight, CircleDot, ClipboardList, ShieldCheck } from 'lucide-react'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge, Button, DataTable, PageHeader, Panel } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { merchantSchema } from '../../shared/api/schemas'
import { formatBeijingTime } from '../../shared/ui/display'
import { dispositionLabels } from '../receipts/receiptDisposition'
import { OperationsSummary, TodaySummary } from './OperationsSummary'
import { previewPath, type DesignVariant } from '../design-preview/previewRoutes'

const SAMPLE_LIMIT = 1
const PENDING_REFUND = { status: 'PENDING_APPROVAL', limit: SAMPLE_LIMIT } as const
const ABNORMAL_RECEIPT = { abnormalOnly: true, limit: SAMPLE_LIMIT } as const
const OPEN_CASE = 'OPEN'
const PUBLICATION_REVIEW = 'REVIEW'
const WORKBENCH_FRESHNESS_MS = 30_000

function QueueRow<T>({ label, href, query, sampleDetails }: {
  label: string
  href: string
  query: UseQueryResult<T[]>
  sampleDetails: (item: T) => { reference: string; note?: string }
}) {
  const row = query.data?.[0]
  const sample = row ? sampleDetails(row) : undefined
  return (
    <tr>
      <th scope="row">{label}</th>
      <td>{sample ? <span className="mono">{sample.reference}</span> : '—'}</td>
      <td>{query.isPending ? '读取中' : query.isError ? <>
        <Badge tone="warn">暂不可读</Badge>
        <Button tone="ghost" aria-label={`重试${label}`} loading={query.isFetching} onClick={() => void query.refetch()}>重试</Button>
      </> : sample ? sample.note ?? '—' : '当前无事项'}</td>
      <td><Link to={href} aria-label={`查看${label}`}>查看队列</Link></td>
    </tr>
  )
}

export function OverviewPage({ preview }: { preview?: DesignVariant } = {}) {
  const layout = preview ?? 'modern'
  const orderPath = preview ? previewPath(preview, 'orders') : '/orders'
  // 待办按优先顺序读取，避免首屏同时拉取多张队列占满租户并发额度。
  const queueOptions = { retry: false, staleTime: WORKBENCH_FRESHNESS_MS }
  const merchant = useQuery({ queryKey: ['merchant'], queryFn: api.merchant, ...queueOptions })
  const admission = useQuery({ queryKey: ['admission'], queryFn: api.admissionPolicy, ...queueOptions })
  const refunds = useQuery({
    queryKey: ['refunds', 'overview', PENDING_REFUND],
    queryFn: ({ signal }) => api.refunds(PENDING_REFUND, signal),
    ...queueOptions, enabled: admission.isFetched && !admission.isFetching,
  })
  const receipts = useQuery({
    queryKey: ['admin-receipts', 'overview', ABNORMAL_RECEIPT],
    queryFn: ({ signal }) => api.paymentReceipts(ABNORMAL_RECEIPT, signal),
    select: (page) => page.items,
    ...queueOptions, enabled: refunds.isFetched && !refunds.isFetching,
  })
  const reconciliation = useQuery({
    queryKey: ['recon-cases', OPEN_CASE, 0, SAMPLE_LIMIT],
    queryFn: () => api.reconciliationCases(OPEN_CASE, 0, SAMPLE_LIMIT),
    ...queueOptions, enabled: receipts.isFetched && !receipts.isFetching,
  })
  const publications = useQuery({
    queryKey: ['outbox', PUBLICATION_REVIEW, 0, SAMPLE_LIMIT],
    queryFn: () => api.outboxPublications(PUBLICATION_REVIEW, 0, SAMPLE_LIMIT),
    ...queueOptions, enabled: reconciliation.isFetched && !reconciliation.isFetching,
  })

  const missingMerchant = merchant.error instanceof ApiProblem && merchant.error.status === 404
  const activeMerchant = merchant.data?.status === merchantSchema.shape.status.enum.ACTIVE
  const merchantName = merchant.isPending ? '读取中' : merchant.data?.name ?? (missingMerchant ? '尚未配置' : '暂不可读')
  const refundPath = `/refunds?status=${PENDING_REFUND.status}`
  const receiptPath = '/receipts?abnormalOnly=true'
  const intakeStatus = (enabled?: boolean) => admission.isPending ? '读取中'
    : enabled === undefined ? '暂不可读' : enabled ? '接收中' : '已暂停'
  const summary = preview ? <OperationsSummary preview={preview} />
    : <TodaySummary enabled={merchant.isFetched && !merchant.isFetching} />

  const layoutClass = layout === 'analytics' ? ' analytics-workspace' : layout === 'operations' ? ' operations-preview-workspace' : ''
  return <div className={`workspace workbench-workspace${layoutClass}`}>
    <PageHeader title={preview ? '经营工作台' : '交易运营工作台'}
      description={layout === 'analytics' ? '按期间核对订单与确认资金，查看变化对应的交易明细。' : '处理交易待办、检查受理状态，或按编号查单。'}
      actions={<Link className="button primary" to={orderPath}><ClipboardList size={16} aria-hidden="true" />查订单</Link>} />
    {layout === 'modern' && <section className="workbench-banner" aria-label="交易运营入口">
      <div>
        <p><CircleDot size={14} aria-hidden="true" /> {merchantName}</p>
        <h2>处理交易异常</h2>
        <div className="workbench-shortcuts">
          <Link to={refundPath}>待审批退款 <ArrowRight size={15} aria-hidden="true" /></Link>
          <Link to={receiptPath}>异常实收 <ArrowRight size={15} aria-hidden="true" /></Link>
        </div>
      </div>
      <div className="workbench-intake">
        <ShieldCheck size={25} aria-hidden="true" />
        <div><span>订单受理</span><strong>{intakeStatus(admission.data?.orderEnabled)}</strong></div>
        <div><span>支付受理</span><strong>{intakeStatus(admission.data?.paymentEnabled)}</strong></div>
        {admission.isError && <button type="button" onClick={() => void admission.refetch()} disabled={admission.isFetching}>重试</button>}
      </div>
    </section>}

    {layout === 'analytics' && summary}
    <div className="workbench-top">
      <Panel className="table-panel">
        <h2 className="table-title">当前待处理</h2>
        <DataTable tools={false} label="当前待处理事项" className="queue-table overview-queue-table">
          <thead><tr><th scope="col">事项</th><th scope="col">样本编号</th><th scope="col">说明</th><th scope="col">处理入口</th></tr></thead>
          <tbody>
            <QueueRow label="待审批退款" href={refundPath} query={refunds} sampleDetails={(item) => ({
              reference: item.businessNo ?? item.refundNo,
              note: item.approvalDeadline ? `${formatBeijingTime(item.approvalDeadline)}（北京时间）截止` : undefined,
            })} />
            <QueueRow label="异常实收" href={receiptPath} query={receipts} sampleDetails={(item) => ({
              reference: item.businessNo ?? item.receiptNo, note: dispositionLabels[item.disposition],
            })} />
            <QueueRow label="未结对账差异" href={`/reconciliation?state=${OPEN_CASE}`} query={reconciliation} sampleDetails={(item) => ({
              reference: item.businessNo ?? item.caseNo, note: item.differenceType,
            })} />
            <QueueRow label="发布异常" href={`/outbox?state=${PUBLICATION_REVIEW}`} query={publications} sampleDetails={(item) => ({
              reference: item.eventId, note: item.eventType,
            })} />
          </tbody>
        </DataTable>
      </Panel>
      <Panel>
        <h2>{layout === 'modern' ? '商户信息' : '交易受理'}</h2>
        <dl className="summary-fields admission-summary">
          <div><dt>逻辑商户</dt><dd><Link to="/merchant">{merchantName}</Link></dd></div>
          <div><dt>商户状态</dt><dd>{merchant.data
            ? <Badge tone={activeMerchant ? 'good' : 'danger'}>{activeMerchant ? '正常' : '冻结'}</Badge> : '—'}</dd></div>
          {layout !== 'modern' && <>
            <div><dt>新订单</dt><dd>{intakeStatus(admission.data?.orderEnabled)}</dd></div>
            <div><dt>新支付</dt><dd>{intakeStatus(admission.data?.paymentEnabled)}</dd></div>
          </>}
        </dl>
        {layout === 'modern' && <Link className="button secondary" to="/admission">查看准入策略</Link>}
      </Panel>
    </div>
    {layout !== 'analytics' && summary}
  </div>
}

import type { ReactNode } from 'react'
import { ArrowRight, CircleDot, ClipboardList, ShieldCheck } from 'lucide-react'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge, Button, PageHeader, Panel, DataTable } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { merchantSchema } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'
import { formatBeijingTime } from '../../shared/ui/display'
import { dispositionLabels } from '../receipts/receiptDisposition'
import { OperationsSummary } from './OperationsSummary'
import { previewPath, type DesignVariant } from '../design-preview/previewRoutes'

const SAMPLE_LIMIT = 1
const PENDING_REFUND = { status: 'PENDING_APPROVAL', limit: SAMPLE_LIMIT } as const
const ABNORMAL_RECEIPT = { abnormalOnly: true, limit: SAMPLE_LIMIT } as const
const OPEN_CASE = 'OPEN'
const PUBLICATION_REVIEW = 'REVIEW'
const PREVIEW_QUEUE_FRESHNESS = 30_000

function QueueRow<T>({ label, href, query, describe, preview }: {
  label: string
  href: string
  query: UseQueryResult<T[]>
  describe: (item: T) => ReactNode
  preview?: DesignVariant
}) {
  const sample = query.data?.[0]
  return (
    <tr>
      <th scope="row">{label}</th>
      <td>{query.isPending ? '读取中' : query.isError ? <><Badge tone="warn">暂不可读</Badge>
        {preview && <Button tone="ghost" loading={query.isFetching} onClick={() => void query.refetch()}>重试</Button>}</>
        : sample ? describe(sample) : '当前无事项'}</td>
      <td><Link to={href} aria-label={`查看${label}`}>查看队列</Link></td>
    </tr>
  )
}

export function OverviewPage({ preview }: { preview?: DesignVariant } = {}) {
  const auth = useAuth()
  // 代表页把待办读取排成一列，给并行的经营汇总保留容量，避免首屏集中撞到租户读并发限制。
  const queueOptions = { retry: false, staleTime: preview ? PREVIEW_QUEUE_FRESHNESS : 0 }
  const merchant = useQuery({ queryKey: ['merchant'], queryFn: api.merchant, ...queueOptions })
  const admission = useQuery({ queryKey: ['admission'], queryFn: api.admissionPolicy, ...queueOptions, enabled: !preview || merchant.isFetched })
  const receipts = useQuery({
    queryKey: ['admin-receipts', 'overview', ABNORMAL_RECEIPT],
    queryFn: () => api.paymentReceipts(ABNORMAL_RECEIPT),
    select: (page) => page.items,
    ...queueOptions, enabled: !preview || admission.isFetched,
  })
  const refunds = useQuery({
    queryKey: ['refunds', 'overview', PENDING_REFUND],
    queryFn: () => api.refunds(PENDING_REFUND),
    ...queueOptions, enabled: !preview || receipts.isFetched,
  })
  const reconciliation = useQuery({
    queryKey: ['recon-cases', OPEN_CASE, 0, SAMPLE_LIMIT],
    queryFn: () => api.reconciliationCases(OPEN_CASE, 0, SAMPLE_LIMIT),
    ...queueOptions, enabled: !preview || refunds.isFetched,
  })
  const publications = useQuery({
    queryKey: ['outbox', PUBLICATION_REVIEW, 0, SAMPLE_LIMIT],
    queryFn: () => api.outboxPublications(PUBLICATION_REVIEW, 0, SAMPLE_LIMIT),
    ...queueOptions, enabled: !preview || reconciliation.isFetched,
  })
  const missingMerchant = merchant.error instanceof ApiProblem && merchant.error.status === 404
  const activeMerchant = merchant.data?.status === merchantSchema.shape.status.enum.ACTIVE
  const merchantName = merchant.isPending ? '读取中' : merchant.data?.name ?? (missingMerchant ? '尚未配置' : '暂不可读')

  const queues = <div className="workbench-top">
          <Panel className="table-panel">
            <h2 className="table-title">当前待处理</h2>
            <DataTable tools={false} label="当前待处理事项" className="queue-table overview-queue-table">
              <thead><tr><th>事项</th><th>当前样本</th><th>处理入口</th></tr></thead>
              <tbody>
                <QueueRow label="异常实收" href="/receipts?abnormalOnly=true" query={receipts} preview={preview}
                  describe={(item) => `${item.businessNo ?? item.receiptNo} · ${dispositionLabels[item.disposition]}`} />
                <QueueRow label="待审批退款" href={`/refunds?status=${PENDING_REFUND.status}`} query={refunds} preview={preview}
                  describe={(item) => item.approvalDeadline
                    ? `${item.businessNo ?? item.refundNo} · ${formatBeijingTime(item.approvalDeadline)}（北京时间）截止`
                    : item.businessNo ?? item.refundNo} />
                <QueueRow label="未结对账差异" href={`/reconciliation?state=${OPEN_CASE}`} query={reconciliation} preview={preview}
                  describe={(item) => `${item.businessNo ?? item.caseNo} · ${item.differenceType}`} />
                <QueueRow label="发布异常" href={`/outbox?state=${PUBLICATION_REVIEW}`} query={publications} preview={preview}
                  describe={(item) => `${item.eventId} · ${item.eventType}`} />
              </tbody>
            </DataTable>
          </Panel>
          <Panel>
            <h2>{preview === 'modern' ? '商户信息' : '交易受理'}</h2>
            <dl className="summary-fields admission-summary">
              {!preview && <div><dt>当前租户</dt><dd>{auth.tenantId || '未声明'}</dd></div>}
              <div><dt>逻辑商户</dt><dd><Link to="/merchant">{merchantName}</Link></dd></div>
              <div><dt>商户状态</dt><dd>{merchant.data ? <Badge tone={activeMerchant ? 'good' : 'danger'}>{activeMerchant ? '正常' : '冻结'}</Badge> : '—'}</dd></div>
              {preview !== 'modern' && <><div><dt>新订单</dt><dd>{admission.isPending ? '读取中' : admission.data ? <Badge tone={admission.data.orderEnabled ? 'good' : 'warn'}>{admission.data.orderEnabled ? '接收' : '已暂停'}</Badge> : '暂不可读'}</dd></div>
              <div><dt>新支付</dt><dd>{admission.isPending ? '读取中' : admission.data ? <Badge tone={admission.data.paymentEnabled ? 'good' : 'warn'}>{admission.data.paymentEnabled ? '接收' : '已暂停'}</Badge> : '暂不可读'}</dd></div></>}
            </dl>
          </Panel>
        </div>
  const summary = <OperationsSummary preview={preview} />

  if (preview) {
    const orders = previewPath(preview, 'orders')
    return <div className="workspace dp-workbench">
      <PageHeader title="经营工作台" description={preview === 'analytics' ? '按期间核对订单与确认资金，查看变化对应的交易明细。' : '查看交易受理、异常队列和期间经营情况。'}
        actions={<Link className="button primary" to={orders}><ClipboardList size={16} aria-hidden="true" /> 查订单</Link>} />
      {preview === 'modern' && <section className="dp-hero" aria-label="交易运营入口">
        <div><p><CircleDot size={14} aria-hidden="true" /> {merchantName}</p>
          <h2>处理交易异常</h2>
          <div className="dp-hero-links">
            <Link to="/refunds?status=PENDING_APPROVAL">待审批退款 <ArrowRight size={15} aria-hidden="true" /></Link>
            <Link to="/receipts?abnormalOnly=true">异常实收 <ArrowRight size={15} aria-hidden="true" /></Link>
          </div>
        </div>
        <div className="dp-hero-health"><ShieldCheck size={25} aria-hidden="true" />
          <div><span>订单受理</span><strong>{admission.data ? (admission.data.orderEnabled ? '接收中' : '已暂停') : admission.isPending ? '读取中' : '暂不可读'}</strong></div>
          <div><span>支付受理</span><strong>{admission.data ? (admission.data.paymentEnabled ? '接收中' : '已暂停') : admission.isPending ? '读取中' : '暂不可读'}</strong></div>
        </div>
      </section>}
      {preview === 'analytics' ? <>{summary}{queues}</> : <>{queues}{summary}</>}
    </div>
  }

  return (
    <div className="workspace">
      <PageHeader title="交易运营工作台" description="查看当前交易受理状态，进入需要跟进的业务队列。" />
      <div className="section-gap">
        {summary}
        {queues}
      </div>
    </div>
  )
}

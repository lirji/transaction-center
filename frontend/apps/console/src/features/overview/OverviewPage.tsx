import type { ReactNode } from 'react'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge, PageHeader, Panel, DataTable } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { merchantSchema } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'
import { formatBeijingTime } from '../../shared/ui/display'
import { dispositionLabels } from '../receipts/receiptDisposition'
import { OperationsSummary } from './OperationsSummary'

const SAMPLE_LIMIT = 1
const PENDING_REFUND = { status: 'PENDING_APPROVAL', limit: SAMPLE_LIMIT } as const
const ABNORMAL_RECEIPT = { abnormalOnly: true, limit: SAMPLE_LIMIT } as const
const OPEN_CASE = 'OPEN'
const PUBLICATION_REVIEW = 'REVIEW'

function QueueRow<T>({ label, href, query, describe }: {
  label: string
  href: string
  query: UseQueryResult<T[]>
  describe: (item: T) => ReactNode
}) {
  const sample = query.data?.[0]
  return (
    <tr>
      <th scope="row">{label}</th>
      <td>{query.isPending ? '读取中' : query.isError ? <Badge tone="warn">暂不可读</Badge> : sample ? describe(sample) : '当前无事项'}</td>
      <td><Link to={href} aria-label={`查看${label}`}>查看队列</Link></td>
    </tr>
  )
}

export function OverviewPage() {
  const auth = useAuth()
  const merchant = useQuery({ queryKey: ['merchant'], queryFn: api.merchant, retry: false })
  const admission = useQuery({ queryKey: ['admission'], queryFn: api.admissionPolicy, retry: false })
  const receipts = useQuery({
    queryKey: ['admin-receipts', 'overview', ABNORMAL_RECEIPT],
    queryFn: () => api.paymentReceipts(ABNORMAL_RECEIPT),
    select: (page) => page.items,
    retry: false,
  })
  const refunds = useQuery({
    queryKey: ['refunds', 'overview', PENDING_REFUND],
    queryFn: () => api.refunds(PENDING_REFUND),
    retry: false,
  })
  const reconciliation = useQuery({
    queryKey: ['recon-cases', OPEN_CASE, 0, SAMPLE_LIMIT],
    queryFn: () => api.reconciliationCases(OPEN_CASE, 0, SAMPLE_LIMIT),
    retry: false,
  })
  const publications = useQuery({
    queryKey: ['outbox', PUBLICATION_REVIEW, 0, SAMPLE_LIMIT],
    queryFn: () => api.outboxPublications(PUBLICATION_REVIEW, 0, SAMPLE_LIMIT),
    retry: false,
  })
  const missingMerchant = merchant.error instanceof ApiProblem && merchant.error.status === 404
  const activeMerchant = merchant.data?.status === merchantSchema.shape.status.enum.ACTIVE
  const merchantName = merchant.isPending ? '读取中' : merchant.data?.name ?? (missingMerchant ? '尚未配置' : '暂不可读')

  return (
    <div className="workspace">
      <PageHeader title="交易运营工作台" description="查看当前交易受理状态，进入需要跟进的业务队列。" />
      <div className="section-gap">
        <OperationsSummary />
        <div className="workbench-top">
          <Panel className="table-panel">
            <h2 className="table-title">当前待处理</h2>
            <DataTable tools={false} label="当前待处理事项" className="queue-table overview-queue-table">
              <thead><tr><th>事项</th><th>当前样本</th><th>处理入口</th></tr></thead>
              <tbody>
                <QueueRow label="异常实收" href="/receipts?abnormalOnly=true" query={receipts}
                  describe={(item) => `${item.businessNo ?? item.receiptNo} · ${dispositionLabels[item.disposition]}`} />
                <QueueRow label="待审批退款" href={`/refunds?status=${PENDING_REFUND.status}`} query={refunds}
                  describe={(item) => item.approvalDeadline
                    ? `${item.businessNo ?? item.refundNo} · ${formatBeijingTime(item.approvalDeadline)}（北京时间）截止`
                    : item.businessNo ?? item.refundNo} />
                <QueueRow label="未结对账差异" href={`/reconciliation?state=${OPEN_CASE}`} query={reconciliation}
                  describe={(item) => `${item.businessNo ?? item.caseNo} · ${item.differenceType}`} />
                <QueueRow label="发布异常" href={`/outbox?state=${PUBLICATION_REVIEW}`} query={publications}
                  describe={(item) => `${item.eventId} · ${item.eventType}`} />
              </tbody>
            </DataTable>
          </Panel>
          <Panel>
            <h2>交易受理</h2>
            <dl className="summary-fields admission-summary">
              <div><dt>当前租户</dt><dd>{auth.tenantId || '未声明'}</dd></div>
              <div><dt>逻辑商户</dt><dd><Link to="/merchant">{merchantName}</Link></dd></div>
              <div><dt>商户状态</dt><dd>{merchant.data ? <Badge tone={activeMerchant ? 'good' : 'danger'}>{activeMerchant ? '正常' : '冻结'}</Badge> : '—'}</dd></div>
              <div><dt>新订单</dt><dd>{admission.isPending ? '读取中' : admission.data ? <Badge tone={admission.data.orderEnabled ? 'good' : 'warn'}>{admission.data.orderEnabled ? '接收' : '已暂停'}</Badge> : '暂不可读'}</dd></div>
              <div><dt>新支付</dt><dd>{admission.isPending ? '读取中' : admission.data ? <Badge tone={admission.data.paymentEnabled ? 'good' : 'warn'}>{admission.data.paymentEnabled ? '接收' : '已暂停'}</Badge> : '暂不可读'}</dd></div>
            </dl>
          </Panel>
        </div>
      </div>
    </div>
  )
}

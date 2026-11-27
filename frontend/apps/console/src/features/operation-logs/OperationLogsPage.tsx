import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api } from '../../shared/api/client'
import type { OperationAuditEntry } from '../../shared/api/schemas'
import { parseTimeWindow } from '../../shared/api/validate'
import { dash, formatBeijingTime, statusTone } from '../../shared/ui/display'
import { EmptySelect, FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'
import { refundAuditActions, refundAuditResults } from '../../shared/ui/refundAuditLabels'

const resourceLabels: Record<string, string> = {
  ORDER: '订单',
  REFUND: '退款',
  RETURN: '退货',
  MEMBER: '会员',
  MERCHANT: '商户',
  SKU: '商品规格',
  QUOTE: '报价',
  CONSENT_DOCUMENT: '条款',
  PAYMENT_CHANNEL: '支付渠道',
  REFUND_POLICY: '退款策略',
  APPROVAL_REQUEST: '退款审批请求',
  ADMISSION_POLICY: '准入策略',
  FULFILLMENT_INGRESS: '履约受理',
  OUTBOX: 'Outbox',
  RECONCILIATION_CASE: '对账差异',
  RECONCILIATION_OPERATION: '对账处置',
}

const resourceTypes = Object.keys(resourceLabels)
const detailRoutes = new Map([['ORDER', '/orders'], ['REFUND', '/refunds'], ['QUOTE', '/quotes']])

const actionLabels: Record<string, string> = {
  ...refundAuditActions,
  'order.create': '创建订单',
  'order.paid': '确认收款',
  'order.cancelled': '取消订单',
  'order.CANCELLED': '取消订单',
  'order.CLOSING': '发起关单',
  'order.delivered': '确认交付',
  'order.completed': '完成订单',
  'order.return.inspect': '验收退货',
  'fulfillment.ingress.DELIVER': '受理交付',
  'fulfillment.ingress.COMPLETE': '受理完成订单',
  'fulfillment.ingress.CONFIRM': '受理确认收货',
  'fulfillment.ingress.RETURN_CREATE': '受理退货申请',
  'fulfillment.ingress.RETURN_INSPECT': '受理退货验收',
  'refund.policy': '更新退款策略',
  'refund.approval.rejected-request': '拒绝审批请求',
  'member.profile': '修改会员资料',
  'member.status.ACTIVE': '启用会员',
  'member.status.FROZEN': '冻结会员',
  'member.consent.GRANT': '同意条款',
  'member.consent.WITHDRAW': '撤回同意',
  'merchant.create': '创建商户',
  'merchant.status.ACTIVE': '启用商户',
  'merchant.status.FROZEN': '冻结商户',
  'sku.add': '新增商品规格',
  'sku.edit': '修改商品规格',
  'quote.create': '创建报价',
  'consent-document.publish': '发布条款',
  'payment.channel.create': '登记支付渠道',
  'admission.update': '更新准入策略',
  'outbox.replay': '重放 Outbox 事件',
  'reconciliation.report': '受理对账差异',
  'reconciliation.query': '发起对账查询',
  'reconciliation.query.result': '记录对账查询结果',
}

function ResourceReference({ row }: { row: OperationAuditEntry }) {
  const route = detailRoutes.get(row.resourceType)
  return route && row.resourceId
    ? <Link to={`${route}/${encodeURIComponent(row.resourceId)}`} className="mono">{row.resourceId}</Link>
    : <span className="mono">{dash(row.resourceId)}</span>
}

function OperationRows({ rows }: { rows: OperationAuditEntry[] }) {
  return (
    <table className="data-table trade-list-table operation-log-table">
      <thead>
        <tr><th>时间（北京时间）</th><th>对象类型</th><th>对象编号</th><th>动作</th><th>结果</th><th>操作人摘要</th><th>追踪编号</th></tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.id}>
            <td>{formatBeijingTime(row.occurredAt)}</td>
            <td>{resourceLabels[row.resourceType] ?? row.resourceType}</td>
            <td><ResourceReference row={row} /></td>
            <td>{actionLabels[row.action] ?? row.action}</td>
            <td>
              <Badge tone={statusTone(row.result)}>{row.result === 'RECORDED' ? '已记录' : refundAuditResults[row.result] ?? row.result}</Badge>
              {row.errorCode && <small className="mono">{row.errorCode}</small>}
            </td>
            <td className="mono audit-actor">{row.actorId}</td>
            <td className="mono audit-trace">{dash(row.traceId)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

type Filters = { resourceType?: string; resourceRef?: string; createdFrom?: string; createdTo?: string }

function OperationFilters({ params, apply }: { params: URLSearchParams; apply: (filters: Filters) => void }) {
  const [resourceType, setResourceType] = useState(params.get('resourceType') ?? '')
  const [resourceRef, setResourceRef] = useState(params.get('resourceRef') ?? '')
  const [from, setFrom] = useState(isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(isoFromSearch(params, 'createdTo'))
  const [error, setError] = useState('')

  function submit() {
    setError('')
    try {
      apply({
        resourceType: resourceType || undefined,
        resourceRef: resourceRef.trim() || undefined,
        ...parseTimeWindow(from, to),
      })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '筛选时间无效')
    }
  }

  function reset() {
    setResourceType('')
    setResourceRef('')
    setFrom('')
    setTo('')
    setError('')
    apply({})
  }

  return (
    <Panel>
      <h2>筛选</h2>
      <FilterBar onSubmit={submit} onReset={reset}>
        <Field label="对象类型">
          <EmptySelect value={resourceType} onChange={setResourceType}
            options={resourceType && !resourceTypes.includes(resourceType) ? [...resourceTypes, resourceType] : resourceTypes}
            optionLabel={(type) => Object.hasOwn(resourceLabels, type) ? resourceLabels[type] : type} />
        </Field>
        <Field label="对象编号" hint="精确匹配日志中的对象编号">
          <input value={resourceRef} onChange={(event) => setResourceRef(event.target.value)} maxLength={64} />
        </Field>
        <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={error} />
      </FilterBar>
    </Panel>
  )
}

export function OperationLogsPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const filters = {
    resourceType: params.get('resourceType') || undefined,
    resourceRef: params.get('resourceRef') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const query = useQuery({
    queryKey: ['operation-logs', after, filters],
    queryFn: ({ signal }) => api.operationAudits({ ...filters, cursor: after === '0' ? undefined : after }, signal),
    retry: false,
  })

  return (
    <div className="workspace">
      <PageHeader eyebrow="运行与准入" title="操作日志" description="查看当前租户已记录的交易业务操作。" />
      <div className="section-gap">
        <OperationFilters key={JSON.stringify(filters)} params={params} apply={apply} />
        <Panel className="table-panel">
          <PageQueryGate query={query} emptyTitle="暂无操作日志" emptyDetail="当前筛选范围没有操作记录。">
            {(page) => (
              <>
                <div className="table-scroll" role="region" aria-label="操作日志列表" tabIndex={0}>
                  <OperationRows rows={page.items} />
                </div>
                <PagePager after={after} nextAfter={page.nextCursor} hasMore={Boolean(page.nextCursor)}
                  onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
          {after !== '0' && (query.isError || query.data?.items.length === 0) && (
            <PagePager after={after} nextAfter={null} hasMore={false} onReset={() => setAfter('0')} onNext={setAfter} />
          )}
        </Panel>
      </div>
    </div>
  )
}

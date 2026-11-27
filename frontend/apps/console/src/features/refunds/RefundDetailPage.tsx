import { RecordHeader } from '../../components/WorkspaceNavigation'
import { NumberedPagePager } from '../../components/NumberedPager'
import { refundAuditEntrySchema } from '../../shared/api/schemas'
import { useLocalListPage, useNumberedList, auditProjection } from '../../shared/ui/useNumberedList'
import { Link, useParams } from 'react-router-dom'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, Panel, StateBanner, DataTable } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { dash, statusTone, formatBeijingTime } from '../../shared/ui/display'
import { refundAuditActions, refundAuditResults } from '../../shared/ui/refundAuditLabels'
import { usePageSize } from '../../shared/ui/usePageSize'
import { refundLabels } from './refundStatus'

/** 按已授权的原退款读取独立操作流水，部分日志故障不影响退款详情。 */
function RefundAuditHistory({ refundNo }: { refundNo: string }) {
  const [shown, setShown] = useState(false)
  const [cursor, setCursor, page, setPage] = useLocalListPage<string>('0')
  const [pageSize, setPageSize] = usePageSize('refund-operation-audits', 50, () => setCursor('0'))
  const history = useNumberedList({
    queryKey: ['refund-operation-audits', refundNo, cursor, pageSize],
    queryFn: ({ signal }) => api.refundAudit(refundNo, {
      cursor: cursor === '0' ? undefined : cursor,
      limit: pageSize === 50 ? undefined : pageSize,
    }, signal),
    enabled: shown,
    retry: false,
  }, { path: `numbered-operations/refund-audits/${encodeURIComponent(refundNo)}`, schema: refundAuditEntrySchema, after: cursor, page: page, pageSize: pageSize }, auditProjection)
  return (
    <Panel className="table-panel">
      <h2 className="table-title">操作日志</h2>
      <div className="panel-inset">
        <Button aria-expanded={shown} onClick={() => setShown(!shown)}>
          {shown ? '收起操作日志' : '查看操作日志'}
        </Button>
      </div>
      {shown && history.isLoading && <StateBanner tone="loading" title="正在读取操作日志" detail="请稍候。" />}
      {shown && history.isError && (
        <StateBanner tone="error" title="操作日志读取失败" detail={describeProblem(history.error).detail} />
      )}
      {shown && history.data?.items.length === 0 && <p className="panel-inset">暂无操作日志</p>}
      {shown && history.data && history.data.items.length > 0 && (
        <DataTable label="退款操作日志" refreshing={history.isFetching} onRefresh={() => void history.refetch()}>
          <thead><tr><th className="time-cell">时间（北京时间）</th><th>动作</th><th>结果</th><th>状态变化</th><th>提交证据</th></tr></thead>
          <tbody>
            {history.data.items.map((item) => (
              <tr key={item.id}>
                <td className="time-cell">{formatBeijingTime(item.occurredAt)}</td>
                <td>{refundAuditActions[item.action] ?? item.action}</td>
                <td><Badge tone={statusTone(item.result)}>{refundAuditResults[item.result] ?? item.result}</Badge>
                  {item.errorCode && <div className="mono">{item.errorCode}</div>}</td>
                <td>{item.afterState ? `${dash(item.beforeState)} → ${item.afterState}` : '—'}</td>
                <td className="mono">{dash(item.effectId)}</td>
              </tr>
            ))}
          </tbody>
        </DataTable>
      )}
      {shown && history.isError && (
        <div className="state-actions">
          <Button onClick={() => void history.refetch()}>重试</Button>
        </div>
      )}
      {shown && <NumberedPagePager numbered={history.numbered} onNumber={setPage} scope={['refund-operation-audits', refundNo]} after={cursor} onPage={setCursor} pageSize={pageSize} onPageSize={setPageSize}
        result={history.isSuccess ? history.data : undefined} />}
    </Panel>
  )
}

export function RefundDetailPage() {
  const { refundNo = '' } = useParams()
  const detail = useQuery({
    queryKey: ['admin-refund', refundNo],
    queryFn: () => api.refund(refundNo),
    retry: false,
    enabled: Boolean(refundNo),
  })

  if (detail.isLoading) {
    return <div className="workspace record-workspace"><RecordHeader listPath="/refunds" listLabel="返回退款列表" title="退款详情" description="核对退款金额、审批截止和分项。审批在独立流程签收。" /><StateBanner tone="loading" title="正在读取退款" detail="正在获取当前记录。" /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace record-workspace"><RecordHeader listPath="/refunds" listLabel="返回退款列表" title="退款详情" description="核对退款金额、审批截止和分项。审批在独立流程签收。" /><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }
  const row = detail.data

  return (
    <div className="workspace record-workspace">
      <RecordHeader listPath="/refunds" listLabel="返回退款列表"
        title={row.businessNo ?? row.refundNo}
        description="核对退款金额、审批截止和分项。审批在独立流程签收。"
      />
      <StateBanner tone="warn" title="不能在此批准退款" detail="没有批准或驳回按钮。需要独立审批签收。" />
      <div className="section-gap">
        <Panel className="record-summary">
          <h2>退款摘要</h2>
          <dl className="summary-fields">
            <div><dt>状态</dt><dd><Badge tone={statusTone(row.status)}>{refundLabels[row.status]}</Badge></dd></div>
            <div><dt>金额</dt><dd className="summary-amount">{formatMinor(row.amount, row.currency)} <small className="mono">最小单位 {row.amount}</small></dd></div>
            <div><dt>审批截止（北京时间）</dt><dd className="mono">{formatBeijingTime(row.approvalDeadline)}</dd></div>
            <div><dt>审批单号</dt><dd>{dash(row.approvalBusinessNo ?? row.approvalRef)}</dd></div>
            <div><dt>订单</dt><dd><Link className="mono" to={`/orders/${encodeURIComponent(row.orderNo)}`}>{row.orderBusinessNo ?? row.orderNo}</Link></dd></div>
            <div><dt>会员</dt><dd className="mono">{row.memberId}</dd></div>
          </dl>
          <dl className="kv detail-meta">
            <dt>政策版本</dt><dd className="mono">{row.policyVersion}</dd>
            <dt>版本</dt><dd className="mono">{row.version}</dd>
            <dt>处理分类</dt><dd className="mono">{dash(row.processingReasonCode)}</dd>
            <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.observedAt)}</dd>
          </dl>
        </Panel>
        <Panel className="table-panel">
          <h2 className="table-title">分项</h2>
          <DataTable label="退款分项">
            <thead><tr><th>类型</th><th>订单明细</th><th>数量</th><th className="numeric-cell">金额</th></tr></thead>
            <tbody>
              {row.items.map((item, index) => (
                <tr key={`${item.componentType}-${item.orderItemId ?? index}`}>
                  <td>{item.componentType}</td>
                  <td className="mono">{dash(item.orderItemId)}</td>
                  <td>{item.quantity}</td>
                  <td className="numeric-cell">{formatMinor(item.amount, row.currency)}</td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        </Panel>
        <RefundAuditHistory key={row.refundNo} refundNo={row.businessNo ?? row.refundNo} />
      </div>
    </div>
  )
}

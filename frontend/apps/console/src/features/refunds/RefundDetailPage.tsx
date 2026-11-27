import { Link, useParams } from 'react-router-dom'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { dash, statusTone, formatBeijingTime } from '../../shared/ui/display'
import { refundAuditActions, refundAuditResults } from '../../shared/ui/refundAuditLabels'

/** 按已授权的原退款读取独立操作流水，部分日志故障不影响退款详情。 */
function RefundAuditHistory({ refundNo }: { refundNo: string }) {
  const [shown, setShown] = useState(false)
  const [cursors, setCursors] = useState<string[]>([])
  const cursor = cursors.at(-1)
  const history = useQuery({
    queryKey: ['refund-operation-audits', refundNo, cursor],
    queryFn: ({ signal }) => api.refundAudit(refundNo, cursor, signal),
    enabled: shown,
    retry: false,
  })
  const next = history.data?.nextCursor
  return (
    <Panel className="table-panel">
      <h2 style={{ padding: '16px 18px 0' }}>操作日志</h2>
      <div style={{ padding: '8px 18px' }}>
        <Button aria-expanded={shown} onClick={() => setShown(!shown)}>
          {shown ? '收起操作日志' : '查看操作日志'}
        </Button>
      </div>
      {shown && history.isLoading && <StateBanner tone="loading" title="正在读取操作日志" detail="请稍候。" />}
      {shown && history.isError && (
        <StateBanner tone="error" title="操作日志读取失败" detail={describeProblem(history.error).detail} />
      )}
      {shown && history.data?.items.length === 0 && <p style={{ padding: '8px 18px' }}>暂无操作日志</p>}
      {shown && history.data && history.data.items.length > 0 && (
        <table className="data-table">
          <thead><tr><th>时间（北京时间）</th><th>动作</th><th>结果</th><th>状态变化</th><th>提交证据</th></tr></thead>
          <tbody>
            {history.data.items.map((item) => (
              <tr key={item.id}>
                <td>{formatBeijingTime(item.occurredAt)}</td>
                <td>{refundAuditActions[item.action] ?? item.action}</td>
                <td><Badge tone={statusTone(item.result)}>{refundAuditResults[item.result] ?? item.result}</Badge>
                  {item.errorCode && <div className="mono">{item.errorCode}</div>}</td>
                <td>{item.afterState ? `${dash(item.beforeState)} → ${item.afterState}` : '—'}</td>
                <td className="mono">{dash(item.effectId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {shown && (cursor || next || history.isError) && (
        <div style={{ display: 'flex', gap: 8, padding: '8px 18px' }}>
          {history.isError && <Button onClick={() => void history.refetch()}>重试</Button>}
          {cursor && <Button onClick={() => setCursors(cursors.slice(0, -1))}>上一页</Button>}
          {next && <Button onClick={() => setCursors([...cursors, next])}>下一页</Button>}
        </div>
      )}
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
    return <div className="workspace"><StateBanner tone="loading" title="正在读取退款" detail={`GET /admin/refunds/${refundNo}`} /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }
  const row = detail.data

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Refund"
        title={row.businessNo ?? row.refundNo}
        description="只读。不展示用户申请原因。processingReasonCode 是本地处理分类，不是渠道最终失败原因。本台不能批准或驳回。"
      />
      <StateBanner tone="warn" title="不能在此批准退款" detail="没有批准或驳回按钮。需要独立审批签收。" />
      <div className="section-gap">
        <Panel>
          <dl className="kv">
            <dt>订单</dt><dd><Link className="mono" to={`/orders/${encodeURIComponent(row.orderNo)}`}>{row.orderBusinessNo ?? row.orderNo}</Link></dd>
            <dt>会员</dt><dd className="mono">{row.memberId}</dd>
            <dt>状态</dt><dd><Badge tone={statusTone(row.status)}>{row.status}</Badge></dd>
            <dt>金额</dt><dd>{formatMinor(row.amount, row.currency)} <small className="mono">{row.currency} {row.amount}</small></dd>
            <dt>审批单号</dt><dd>{dash(row.approvalBusinessNo ?? row.approvalRef)}</dd>
            <dt>审批截止（北京时间）</dt><dd className="mono">{formatBeijingTime(row.approvalDeadline)}</dd>
            <dt>政策版本</dt><dd className="mono">{row.policyVersion}</dd>
            <dt>版本</dt><dd className="mono">{row.version}</dd>
            <dt>处理分类</dt><dd className="mono">{dash(row.processingReasonCode)}</dd>
            <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.observedAt)}</dd>
          </dl>
        </Panel>
        <RefundAuditHistory key={row.refundNo} refundNo={row.businessNo ?? row.refundNo} />
        <Panel className="table-panel">
          <h2 style={{ padding: '16px 18px 0' }}>分项</h2>
          <table className="data-table">
            <thead><tr><th>类型</th><th>订单明细</th><th>数量</th><th>金额</th></tr></thead>
            <tbody>
              {row.items.map((item, index) => (
                <tr key={`${item.componentType}-${item.orderItemId ?? index}`}>
                  <td>{item.componentType}</td>
                  <td className="mono">{dash(item.orderItemId)}</td>
                  <td>{item.quantity}</td>
                  <td>{formatMinor(item.amount, row.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      </div>
    </div>
  )
}

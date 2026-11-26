import { Link } from 'react-router-dom'
import { Badge } from '../../components/ui'
import { type RefundQueueItem } from '../../shared/api/client'
import { formatMinorAmount } from '../../shared/api/money'
import { formatBeijingTime, statusTone } from '../../shared/ui/display'
import { refundLabels } from './refundStatus'

export function RefundRows({ rows }: { rows: RefundQueueItem[] }) {
  return (
    <table className="data-table refund-table">
      <thead><tr><th>退款单号</th><th>订单号</th><th>退款状态</th><th>申请金额</th><th>币种</th><th>审批截止（北京时间）</th><th>审批引用</th></tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.refundNo}>
            <td><Link to={`/refunds/${encodeURIComponent(row.refundNo)}`} className="mono">{row.businessNo ?? row.refundNo}</Link></td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderNo}</Link></td>
            <td><Badge tone={statusTone(row.status)}>{refundLabels[row.status]}</Badge></td>
            <td>{formatMinorAmount(row.amount)}</td>
            <td>{row.currency}</td>
            <td>{formatBeijingTime(row.approvalDeadline)}</td>
            <td>{row.approvalRef ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

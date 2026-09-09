import { Link } from 'react-router-dom'
import { Badge } from '../../components/ui'
import { type RefundQueueItem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { statusTone } from '../../shared/ui/display'

export function RefundRows({ rows }: { rows: RefundQueueItem[] }) {
  return (
    <table className="data-table">
      <thead><tr><th>退款单</th><th>订单</th><th>金额</th><th>状态</th><th>审批引用</th></tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.refundNo}>
            <td><Link to={`/refunds/${encodeURIComponent(row.refundNo)}`} className="mono">{row.refundNo}</Link></td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderNo}</Link></td>
            <td>{formatMinor(row.amount, row.currency)}<small className="mono">{row.currency} {row.amount}</small></td>
            <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
            <td>{row.approvalRef ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

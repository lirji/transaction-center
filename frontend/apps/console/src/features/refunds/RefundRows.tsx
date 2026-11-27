import { Link } from 'react-router-dom'
import { Badge, DataTable, CopyValue } from '../../components/ui'
import { type RefundQueueItem } from '../../shared/api/client'
import { formatMinorAmount } from '../../shared/api/money'
import { useListLinkState } from '../../shared/ui/useAdminListSearch'
import { formatBeijingTime, statusTone } from '../../shared/ui/display'
import { refundLabels } from './refundStatus'

export function RefundRows({ rows }: { rows: RefundQueueItem[] }) {
  const listState = useListLinkState('/refunds')
  return (
    <DataTable label="退款列表" className="refund-table pin-key">
      <thead><tr><th>退款单号</th><th>订单号</th><th>退款状态</th><th className="numeric-cell">申请金额</th><th>币种</th><th className="time-cell">审批截止（北京时间）</th><th>审批单号</th></tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.refundNo}>
            <td><CopyValue value={row.businessNo ?? row.refundNo} name="退款单号"><Link to={`/refunds/${encodeURIComponent(row.refundNo)}`} state={listState} className="mono">{row.businessNo ?? row.refundNo}</Link></CopyValue></td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderBusinessNo ?? row.orderNo}</Link></td>
            <td><Badge tone={statusTone(row.status)}>{refundLabels[row.status]}</Badge></td>
            <td className="numeric-cell">{formatMinorAmount(row.amount)}</td>
            <td>{row.currency}</td>
            <td className="time-cell">{formatBeijingTime(row.approvalDeadline)}
              {row.status === 'PENDING_APPROVAL' && row.approvalDeadline && Date.parse(row.approvalDeadline) <= Date.now() &&
                <small><Badge tone="warn">已到审批截止</Badge></small>}
            </td>
            <td>{row.approvalBusinessNo ?? row.approvalRef ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  )
}

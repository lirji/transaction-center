import { Link } from 'react-router-dom'
import { Badge } from '../../components/ui'
import { type AdminReturnView } from '../../shared/api/client'
import { statusTone } from '../../shared/ui/display'

export function ReturnRows({ rows, onSelect }: { rows: AdminReturnView[]; onSelect?: (returnNo: string) => void }) {
  return (
    <table className="data-table">
      <thead><tr><th>申请</th><th>订单</th><th>明细 / 仓</th><th>数量</th><th>状态</th></tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.returnNo}
            className={onSelect ? 'clickable' : undefined}
            onClick={onSelect ? () => onSelect(row.returnNo) : undefined}
          >
            <td className="mono">{row.returnNo}</td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono" onClick={(event) => event.stopPropagation()}>{row.orderNo}</Link></td>
            <td className="mono">{row.orderItemId} / {row.warehouseId}</td>
            <td>{row.quantity}</td>
            <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

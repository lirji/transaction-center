import { Link } from 'react-router-dom'
import { Badge, Button } from '../../components/ui'
import { type AdminReturnView } from '../../shared/api/client'
import { formatBeijingTime, statusTone } from '../../shared/ui/display'

const returnStatusLabels: Record<AdminReturnView['status'], string> = { OPEN: '待验收', INSPECTED: '已验收' }

export function ReturnRows({ rows, onSelect }: { rows: AdminReturnView[]; onSelect?: (returnNo: string) => void }) {
  return (
    <table className="data-table trade-list-table">
      <thead><tr><th>退货单号</th><th>订单号</th><th>订单明细 ID</th><th>仓库 ID</th><th>申请数量</th><th>验收状态</th><th>申请时间（北京时间）</th>{onSelect ? <th>操作</th> : null}</tr></thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.returnNo}>
            <td className="mono">{onSelect ? row.businessNo ?? row.returnNo : <Link to={`/returns?returnNo=${encodeURIComponent(row.returnNo)}`}>{row.businessNo ?? row.returnNo}</Link>}</td>
            <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`} className="mono">{row.orderBusinessNo ?? row.orderNo}</Link></td>
            <td className="mono">{row.orderItemId}</td>
            <td className="mono">{row.warehouseId}</td>
            <td>{row.quantity}</td>
            <td><Badge tone={statusTone(row.status)}>{returnStatusLabels[row.status]}</Badge></td>
            <td>{formatBeijingTime(row.createdAt)}</td>
            {onSelect ? <td><Button onClick={() => onSelect(row.returnNo)}>查看验收</Button></td> : null}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

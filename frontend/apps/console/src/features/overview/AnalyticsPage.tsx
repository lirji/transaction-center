import { ClipboardList } from 'lucide-react'
import { Link } from 'react-router-dom'
import { PageHeader } from '../../components/ui'
import { OperationsSummary } from './OperationsSummary'

export function AnalyticsPage() {
  return <div className="workspace analytics-workspace">
    <PageHeader title="经营分析" description="按期间核对订单、收付款和付费转化，追溯到对应明细。"
      actions={<Link className="button secondary" to="/orders"><ClipboardList size={16} aria-hidden="true" />订单管理</Link>} />
    <OperationsSummary />
  </div>
}

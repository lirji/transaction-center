import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { describeProblem } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { formatBeijingTime } from '../../shared/ui/display'

export function QuoteDetailPage() {
  const { quoteId = '' } = useParams()
  const detail = useQuery({
    queryKey: ['admin-quote', quoteId],
    queryFn: () => api.quote(quoteId),
    retry: false,
    enabled: Boolean(quoteId),
  })

  if (detail.isLoading) {
    return <div className="workspace"><StateBanner tone="loading" title="正在读取报价" detail={`GET /admin/quotes/${quoteId}`} /></div>
  }
  if (detail.isError || !detail.data) {
    const problem = describeProblem(detail.error)
    return <div className="workspace"><StateBanner tone="error" title={problem.title} detail={problem.detail} /></div>
  }
  const row = detail.data
  const expired = row.observedAt >= row.expiresAt

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Quote"
        title={row.businessNo ?? row.quoteId}
        description="报价冻结商品。未过期不等于当前可下单。"
      />
      <Panel>
        <dl className="kv">
          <dt>会员</dt><dd className="mono">{row.memberId}</dd>
          <dt>应付</dt><dd>{formatMinor(row.payableAmount, row.currency)} <small className="mono">{row.currency} {row.payableAmount}</small></dd>
          <dt>策略</dt><dd className="mono">{row.pricingPolicy}</dd>
          <dt>创建时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.createdAt)}</dd>
          <dt>有效至（北京时间）</dt><dd className="mono">{formatBeijingTime(row.expiresAt)} {expired ? <Badge tone="warn">已过期</Badge> : null}</dd>
          <dt>版本</dt><dd className="mono">{row.version}</dd>
          <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.observedAt)}</dd>
        </dl>
      </Panel>
      <Panel className="table-panel">
        <h2 style={{ padding: '16px 18px 0' }}>冻结商品</h2>
        <table className="data-table">
          <thead><tr><th>SKU ID</th><th>SKU 修订</th><th>标题</th><th>数量</th><th>单价</th><th>应付</th></tr></thead>
          <tbody>
            {row.items.map((item) => (
              <tr key={`${item.skuId}-${item.skuRevision}`}>
                <td className="mono">{item.skuId}</td><td className="mono">{item.skuRevision}</td>
                <td>{item.title}</td>
                <td>{item.quantity}</td>
                <td>{formatMinor(item.unitPrice, row.currency)}</td>
                <td>{formatMinor(item.payableAmount, row.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
    </div>
  )
}
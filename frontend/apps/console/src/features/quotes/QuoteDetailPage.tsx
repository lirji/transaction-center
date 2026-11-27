import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, PageHeader, Panel, StateBanner, DataTable } from '../../components/ui'
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
    return <div className="workspace"><StateBanner tone="loading" title="正在读取报价" detail="正在获取当前记录。" /></div>
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
        title={row.businessNo ?? row.quoteId}
        description="报价冻结商品。未过期不等于当前可下单。"
      />
      <Panel>
        <h2>报价摘要</h2>
        <dl className="summary-fields">
          <div><dt>应付</dt><dd className="summary-amount">{formatMinor(row.payableAmount, row.currency)} <small className="mono">{row.currency} {row.payableAmount}</small></dd></div>
          <div><dt>有效至（北京时间）</dt><dd className="mono">{formatBeijingTime(row.expiresAt)} {expired ? <Badge tone="warn">已过期</Badge> : null}</dd></div>
          <div><dt>会员</dt><dd className="mono">{row.memberId}</dd></div>
          <div><dt>策略</dt><dd className="mono">{row.pricingPolicy}</dd></div>
        </dl>
        <dl className="kv detail-meta">
          <dt>创建时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.createdAt)}</dd>
          <dt>版本</dt><dd className="mono">{row.version}</dd>
          <dt>读取时间（北京时间）</dt><dd className="mono">{formatBeijingTime(row.observedAt)}</dd>
        </dl>
      </Panel>
      <Panel className="table-panel">
        <h2 className="table-title">冻结商品</h2>
        <DataTable label="报价商品明细" refreshing={detail.isFetching} onRefresh={() => void detail.refetch()}>
          <thead><tr><th>SKU ID</th><th>SKU 修订</th><th>标题</th><th>数量</th><th className="numeric-cell">单价</th><th>应付</th></tr></thead>
          <tbody>
            {row.items.map((item) => (
              <tr key={`${item.skuId}-${item.skuRevision}`}>
                <td className="mono">{item.skuId}</td><td className="mono">{item.skuRevision}</td>
                <td>{item.title}</td>
                <td>{item.quantity}</td>
                <td className="numeric-cell">{formatMinor(item.unitPrice, row.currency)}</td>
                <td className="numeric-cell">{formatMinor(item.payableAmount, row.currency)}</td>
              </tr>
            ))}
          </tbody>
        </DataTable>
      </Panel>
    </div>
  )
}
import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowUpRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { NumberedPager } from '../../components/NumberedPager'
import { Button, DataTable, Field, Panel } from '../../components/ui'
import { api, type OperationsRange } from '../../shared/api/client'
import { TRADE_ADMIN_EVIDENCE_READ } from '../../shared/auth/auth-context'
import { useAuth } from '../../shared/auth/useAuth'
import { runtimeConfig } from '../../shared/config/runtime'
import { formatBeijingTime, formatInteger } from '../../shared/ui/display'
import { QueryGate } from '../../shared/ui/QueryGate'
import { usePageSize } from '../../shared/ui/usePageSize'

const money = new Intl.NumberFormat('zh-CN')
const workspaces = [
  { key: 'commerce', title: '商城运营台', hint: '店铺、商品目录及商城活动预算' },
  { key: 'marketing', title: '营销中枢', hint: '营销活动、预算和营销执行结果' },
  { key: 'benefit', title: '权益运营台', hint: '实际发放、失败原因及权益库存' },
] as const

export function SourceWorkspaces({ range }: { range: OperationsRange }) {
  const { hasScope } = useAuth()
  const [expanded, setExpanded] = useState(false)
  const canRead = hasScope(TRADE_ADMIN_EVIDENCE_READ)
  return <Panel className="period-summary">
    <div className="panel-head">
      <h2>业务来源与交易关联</h2>
      {canRead && <Button aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
        {expanded ? '收起交易关联' : '查看交易关联'}
      </Button>}
    </div>
    <div className="source-workspaces">
      {workspaces.map(({ key, title, hint }) => {
        const url = runtimeConfig.workspaces[key]
        return <div className="source-workspace" key={key}>
          {url ? <a className="button ghost" href={url} target="_blank" rel="noopener noreferrer">
            {title}<ArrowUpRight size={14} aria-hidden="true" />
          </a> : <strong>{title} · 入口待配置</strong>}
          <small>{hint}</small>
        </div>
      })}
    </div>
    <p className="panel-note">来源系统各自验证身份和授权。</p>
    {canRead && expanded && <LinkedCheckouts range={range} key={JSON.stringify(range)} />}
  </Panel>
}

function LinkedCheckouts({ range }: { range: OperationsRange }) {
  const [draftStore, setDraftStore] = useState('')
  const [store, setStore] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = usePageSize('linked-checkouts', 20, () => setPage(1))
  const query = useQuery({
    queryKey: ['linked-checkouts', range, store, page, size],
    queryFn: ({ signal }) => api.operationsLinkedCheckouts(range, page, size, store || undefined, signal),
    retry: false,
  })
  function search(event: FormEvent) {
    event.preventDefault()
    setStore(draftStore.trim())
    setPage(1)
  }

  return <>
    <form className="queue-actions" onSubmit={search}>
      <Field label="商城店铺编号"><input value={draftStore} maxLength={64} onChange={(event) => setDraftStore(event.target.value)} /></Field>
      <Button type="submit">查询关联</Button>
    </form>
    <QueryGate query={query} emptyTitle="没有交易关联" emptyDetail="请选择交易期间。">
      {(result) => <>
        <div className="cohort-bar">
          <span>关联订单 {formatInteger(result.rows.totalCount)} 单</span>
          <strong>已关联预算回执 {formatInteger(result.fundingCount)} 笔</strong>
          <small>冻结优惠 {money.format(BigInt(result.frozenDiscount))} {range.currency} 最小单位</small>
        </div>
        {result.rows.items.length === 0 ? <p className="panel-note">当前范围内没有已绑定来源的交易。</p>
          : <DataTable label="交易来源关联">
            <thead><tr><th>订单</th><th>店铺</th><th>商城租户</th><th>商城报价</th><th>预算回执</th><th>创建时间</th></tr></thead>
            <tbody>{result.rows.items.map((row) => <tr key={row.orderNo}>
              <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`}>{row.orderBusinessNo ?? row.orderNo}</Link></td>
              <td>{row.storeId}</td><td>{row.commerceTenant}</td><td className="mono">{row.commerceQuoteId}</td>
              <td className="mono">{row.fundingApplicationId ?? '—'}</td><td>{formatBeijingTime(row.createdAt)}</td>
            </tr>)}</tbody>
          </DataTable>}
        <NumberedPager result={result.rows} onPage={setPage} onPageSize={setSize} />
      </>}
    </QueryGate>
  </>
}

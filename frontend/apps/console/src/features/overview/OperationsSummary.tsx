import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Button, Field, PagePager, Panel, StateBanner } from '../../components/ui'
import { api, type OperationsRange } from '../../shared/api/client'
import { formatBeijingInput } from '../../shared/api/businessTime'
import { type OperationsScope } from '../../shared/api/schemas'
import { parseCurrencyCode, parseTimeWindow } from '../../shared/api/validate'
import { FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { PageQueryGate, QueryGate } from '../../shared/ui/QueryGate'
import { formatBeijingTime } from '../../shared/ui/display'

const financeLabels = {
  NORMAL_RECEIPT: '正常确认收款',
  ABNORMAL_RECEIPT: '异常确认来款',
  NORMAL_REFUND: '正常实收的已确认退款',
  ABNORMAL_REFUND: '异常实收的已确认退款',
  NET: '期间净收款',
} satisfies Record<OperationsScope, string>
const integerFormat = new Intl.NumberFormat('zh-CN')

function displayAmount(amount: string | number) {
  return integerFormat.format(BigInt(amount))
}

function defaultWindow() {
  const day = formatBeijingInput(Date.now()).slice(0, 10)
  const nextDay = new Date(Date.parse(`${day}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10)
  return { from: `${day}T00:00`, to: `${nextDay}T00:00` }
}

export function OperationsSummary() {
  const [initial] = useState(defaultWindow)
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [currency, setCurrency] = useState('CNY')
  const [range, setRange] = useState<OperationsRange>()
  const [scope, setScope] = useState<OperationsScope>()
  const [error, setError] = useState('')
  const summary = useQuery({
    queryKey: ['operations-summary', range],
    queryFn: ({ signal }) => range ? api.operationsSummary(range, signal) : Promise.reject(new Error('请选择统计期间')),
    enabled: Boolean(range),
    retry: false,
  })

  function run() {
    setError('')
    try {
      const window = parseTimeWindow(from, to)
      if (!window) throw new Error('请选择统计起止时间')
      const next = { ...window, currency: parseCurrencyCode(currency) }
      setScope(undefined)
      if (range && range.createdFrom === next.createdFrom && range.createdTo === next.createdTo && range.currency === next.currency) {
        void summary.refetch()
      } else {
        setRange(next)
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '筛选无效')
    }
  }

  return (
    <div className="section-gap">
      <Panel>
        <h2>期间经营汇总</h2>
        <FilterBar onSubmit={run}>
          <Field label="统计币种"><input value={currency} onChange={(event) => setCurrency(event.target.value)} maxLength={3} required /></Field>
          <TimeRangeFields labelPrefix="统计" from={from} to={to} onFrom={setFrom} onTo={setTo} />
        </FilterBar>
        {error ? <StateBanner tone="warn" title="筛选条件无效" detail={error} /> : null}
        {range ? (
          <QueryGate query={summary} emptyTitle="暂无汇总" emptyDetail="请选择统计期间后重新读取。">
            {(result) => {
              const orderQuery = new URLSearchParams({ ...range, sort: 'CREATED_DESC' })
              const totals = [
                ['NORMAL_RECEIPT', result.normalReceipt],
                ['ABNORMAL_RECEIPT', result.abnormalReceipt],
                ['NORMAL_REFUND', result.normalRefund],
                ['ABNORMAL_REFUND', result.abnormalRefund],
                ['NET', result.netReceipt],
              ] as const
              return (
                <>
                  <p>{formatBeijingTime(result.from)} 至 {formatBeijingTime(result.to)}（北京时间，含起始、不含结束） · {result.currency}</p>
                  <p>读取时间 {formatBeijingTime(result.observedAt)}（北京时间）</p>
                  <div className="table-scroll">
                    <table className="data-table operations-summary-table">
                      <colgroup><col className="operations-metric-name" /><col /><col className="operations-metric-action" /></colgroup>
                      <thead><tr><th scope="col">指标</th><th scope="col">数值</th><th scope="col">明细</th></tr></thead>
                      <tbody>
                        <tr><th scope="row">新建订单</th><td>{displayAmount(result.orderCount)} 单</td><td rowSpan={2}><Link to={`/orders?${orderQuery}`}>查看创建订单</Link></td></tr>
                        <tr><th scope="row">创建应付额</th><td>{displayAmount(result.orderPayable)}（最小单位）</td></tr>
                        <tr><th scope="row">确认付费订单</th><td>{displayAmount(result.paidOrderCount)} 单</td><td><Button tone="ghost" onClick={() => setScope('NORMAL_RECEIPT')}>查看收款</Button></td></tr>
                        {totals.map(([kind, amount]) => (
                          <tr key={kind}>
                            <th scope="row">{financeLabels[kind]}</th>
                            <td>{displayAmount(amount)}（最小单位）</td>
                            <td><Button tone="ghost" aria-label={`查看${financeLabels[kind]}明细`} aria-expanded={scope === kind} onClick={() => setScope(kind)}>查看明细</Button></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <p>新建订单含取消及零元订单。资金按本地确认时间归期，期间净收款为正常确认收款减去正常实收的已确认退款。</p>
                </>
              )
            }}
          </QueryGate>
        ) : null}
      </Panel>
      {range && scope ? <FinancialFacts key={scope} range={range} scope={scope} /> : null}
    </div>
  )
}

function FinancialFacts({ range, scope }: { range: OperationsRange; scope: OperationsScope }) {
  const [after, setAfter] = useState('0')
  const query = useQuery({
    queryKey: ['operations-financial-facts', range, scope, after],
    queryFn: ({ signal }) => api.operationsFinancialFacts({ ...range, scope, after }, signal),
    retry: false,
  })

  return (
    <Panel className="table-panel">
      <h2 className="table-title">{financeLabels[scope]}明细</h2>
      <PageQueryGate query={query} emptyTitle="期间无资金记录" emptyDetail="当前币种、期间和资金范围没有匹配的确认记录。">
        {(result) => (
          <>
            <div className="table-scroll">
              <table className="data-table operations-facts-table">
                <thead><tr><th>账本流水</th><th>资金类型</th><th>最小单位金额</th><th>币种</th><th>订单号</th><th>实收号</th><th>退款单号</th><th>确认时间（北京时间）</th></tr></thead>
                <tbody>
                  {result.items.map((row) => (
                    <tr key={row.ledgerNo}>
                      <td className="mono">{row.businessNo ?? row.ledgerNo}</td>
                      <td>{financeLabels[row.kind]}</td>
                      <td>{displayAmount(row.amount)}</td>
                      <td>{row.currency}</td>
                      <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`}>{row.orderBusinessNo ?? row.orderNo}</Link></td>
                      <td><Link to={`/receipts/${encodeURIComponent(row.receiptNo)}`}>{row.receiptBusinessNo ?? row.receiptNo}</Link></td>
                      <td>{row.refundNo ? <Link to={`/refunds/${encodeURIComponent(row.refundNo)}`}>{row.refundBusinessNo ?? row.refundNo}</Link> : '—'}</td>
                      <td>{formatBeijingTime(row.confirmedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <PagePager after={after} nextAfter={result.nextAfter} hasMore={result.hasMore}
              observedAt={result.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
          </>
        )}
      </PageQueryGate>
    </Panel>
  )
}

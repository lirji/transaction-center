import { SourceWorkspaces } from './SourceWorkspaces'
import { OperationsAnalysis } from './OperationsAnalysis'
import { NumberedPagePager } from '../../components/NumberedPager'
import { useLocalListPage, useNumberedList, pageProjection } from '../../shared/ui/useNumberedList'
import { usePageSize } from '../../shared/ui/usePageSize'
import { useEffect, useRef, useState } from 'react'
import { ArrowUpRight, ArrowRight, BarChart3 } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Button, Field, Panel, StateBanner, DataTable } from '../../components/ui'
import { api, type OperationsRange } from '../../shared/api/client'
import { beijingRange } from '../../shared/api/businessTime'
import { type OperationsScope, operationsFinancialFactSchema } from '../../shared/api/schemas'
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

function displayAmount(amount: string | number | bigint) {
  return integerFormat.format(BigInt(amount))
}

type Summary = Awaited<ReturnType<typeof api.operationsSummary>>

const fundingKinds = ['NORMAL_RECEIPT', 'ABNORMAL_RECEIPT', 'NORMAL_REFUND', 'ABNORMAL_REFUND'] as const
const fundingFields = {
  NORMAL_RECEIPT: 'normalReceipt', ABNORMAL_RECEIPT: 'abnormalReceipt',
  NORMAL_REFUND: 'normalRefund', ABNORMAL_REFUND: 'abnormalRefund',
} as const
const fundingColors = {
  NORMAL_RECEIPT: 'receipt', ABNORMAL_RECEIPT: 'abnormal',
  NORMAL_REFUND: 'refund', ABNORMAL_REFUND: 'abnormal',
} as const

function precedingRange(range: OperationsRange): OperationsRange {
  const from = Date.parse(range.createdFrom)
  const duration = Date.parse(range.createdTo) - from
  return { currency: range.currency, createdFrom: new Date(from - duration).toISOString(), createdTo: range.createdFrom }
}

function comparisonText(current: string | number, previous: string | number) {
  const base = BigInt(previous)
  const delta = BigInt(current) - base
  if (!delta) return '较上期持平'
  const sign = delta > 0n ? '+' : '−'
  const magnitude = delta > 0n ? delta : -delta
  if (base === 0n) return `上期为 0，本期变化 ${sign}${displayAmount(magnitude)}`
  const tenths = magnitude * 1000n / (base > 0n ? base : -base)
  if (tenths === 0n) return `较上期 ${sign}<0.1%`
  return `较上期 ${sign}${displayAmount(tenths / 10n)}.${tenths % 10n}%`
}

function FundingBars({ result, select, active }: {
  result: Summary
  select: (scope: OperationsScope) => void
  active?: OperationsScope
}) {
  const amounts = fundingKinds.map((kind) => BigInt(result[fundingFields[kind]]))
  const peak = amounts.reduce((max, amount) => amount > max ? amount : max, 0n)
  return (
    <div className="funding-breakdown">
      <div className="funding-heading"><h3>确认收付款项</h3><span>{result.currency} · 最小单位</span></div>
      <ul className="funding-bars">
        {fundingKinds.map((kind, index) => (
          <li key={kind}>
            <button type="button" className="funding-bar-button" aria-expanded={active === kind}
              aria-label={`查看${financeLabels[kind]}明细`} onClick={() => select(kind)}>
              <span>{financeLabels[kind]}</span>
              <span className="funding-bar-track" aria-hidden="true"><i className={`funding-bar ${fundingColors[kind]}`}
                style={{ width: peak ? `${Number(amounts[index] * 10000n / peak) / 100}%` : '0%' }} /></span>
              <strong>{displayAmount(amounts[index].toString())}</strong><ArrowRight size={15} aria-hidden="true" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function MetricValue({ name, value, unit, baseline }: {
  name: string; value: string | number; unit: string; baseline?: string | number
}) {
  return <>
    <span className="metric-label">{name}<ArrowUpRight size={15} aria-hidden="true" /></span>
    <strong className="metric-value">{displayAmount(value)} <small>{unit}</small></strong>
    {baseline !== undefined && <span className="metric-comparison">{comparisonText(value, baseline)}</span>}
  </>
}

export function OperationsSummary() {
  const [initial] = useState(() => beijingRange('today'))
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [currency, setCurrency] = useState('CNY')
  const [range, setRange] = useState<OperationsRange>(() => ({ ...parseTimeWindow(initial.from, initial.to)!, currency: 'CNY' }))
  const [scope, setScope] = useState<OperationsScope>()
  const [contrast, setContrast] = useState(false)
  const [error, setError] = useState('')
  const factSection = useRef<HTMLElement>(null)
  const priorRange = precedingRange(range)
  const summary = useQuery({
    queryKey: ['operations-summary', range],
    queryFn: ({ signal }) => api.operationsSummary(range, signal),
    retry: false,
  })
  const prior = useQuery({
    queryKey: ['operations-summary', priorRange],
    queryFn: ({ signal }) => api.operationsSummary(priorRange, signal),
    enabled: contrast && summary.isSuccess,
    retry: false,
  })
  useEffect(() => {
    if (scope) factSection.current?.scrollIntoView?.({ block: 'start' })
  }, [scope])

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
      <Panel className="period-summary">
        <div className="panel-head"><h2>期间经营汇总</h2>
          <Button aria-pressed={contrast} disabled={!summary.isSuccess} onClick={() => setContrast(!contrast)}>
            <BarChart3 size={16} aria-hidden="true" />{contrast ? '关闭上期对比' : '对比上一期'}
          </Button>
        </div>
        <FilterBar onSubmit={run}>
          <Field label="统计币种"><input value={currency} onChange={(event) => setCurrency(event.target.value)} maxLength={3} required /></Field>
          <TimeRangeFields labelPrefix="统计" from={from} to={to} onFrom={setFrom} onTo={setTo} />
        </FilterBar>
        {error && <StateBanner tone="warn" title="筛选条件无效" detail={error} />}
        <QueryGate query={summary} emptyTitle="暂无汇总" emptyDetail="请选择统计期间后重新读取。">
          {(result) => {
            const orderQuery = new URLSearchParams({ ...range, sort: 'CREATED_DESC' })
            const baseline = contrast ? prior.data : undefined
            return (
              <>
                <div className="metrics-context">
                  <span>{formatBeijingTime(result.from)} 至 {formatBeijingTime(result.to)}（北京时间，含起始、不含结束）</span>
                  <span>读取于 {formatBeijingTime(result.observedAt)}</span>
                </div>
                {contrast && prior.isLoading && <p className="panel-note" role="status">正在读取上一期…</p>}
                {contrast && prior.isError && <div className="metric-error">
                  <span role="status">上一期读取失败</span><Button tone="ghost" onClick={() => void prior.refetch()}>重试上期</Button>
                </div>}
                {baseline && <p className="panel-note">上期 {formatBeijingTime(baseline.from)} 至 {formatBeijingTime(baseline.to)}（北京时间）</p>}
                <div className="metric-grid">
                  <Link className="metric" to={`/orders?${orderQuery}`} aria-label="查看创建订单">
                    <MetricValue name="新建订单" value={result.orderCount} unit="单" baseline={baseline?.orderCount} />
                  </Link>
                  <Link className="metric" to={`/orders?${orderQuery}`} aria-label="查看创建应付额关联订单">
                    <MetricValue name="创建应付额" value={result.orderPayable} unit="最小单位" baseline={baseline?.orderPayable} />
                  </Link>
                  <button type="button" className="metric" aria-expanded={scope === 'NORMAL_RECEIPT'}
                    aria-label="查看确认付费订单的收款" onClick={() => setScope('NORMAL_RECEIPT')}>
                    <MetricValue name="确认付费订单" value={result.paidOrderCount} unit="单" baseline={baseline?.paidOrderCount} />
                  </button>
                  <button type="button" className="metric metric-primary" aria-expanded={scope === 'NET'}
                    aria-label="查看期间净收款明细" onClick={() => setScope('NET')}>
                    <MetricValue name="期间净收款" value={result.netReceipt} unit="最小单位" baseline={baseline?.netReceipt} />
                  </button>
                </div>
                <FundingBars result={result} select={setScope} active={scope} />
                <p className="panel-note">新建订单含取消及零元订单。资金按本地确认时间归期，期间净收款为正常确认收款减去正常实收的已确认退款。</p>
              </>
            )
          }}
        </QueryGate>
      </Panel>
      <OperationsAnalysis range={range} />
      <SourceWorkspaces range={range} />
      {scope && <section ref={factSection} id="financial-facts"><FinancialFacts key={scope} range={range} scope={scope} /></section>}
    </div>
  )
}

function FinancialFacts({ range, scope }: { range: OperationsRange; scope: OperationsScope }) {
  const [after, setAfter, page, setPage] = useLocalListPage<string>('0')
  const [pageSize, setPageSize] = usePageSize('operations-financial-facts', 20, () => setAfter('0'))
  const query = useNumberedList({
    queryKey: ['operations-financial-facts', range, scope, after, pageSize],
    queryFn: ({ signal }) => api.operationsFinancialFacts({ ...range, scope, after, limit: pageSize }, signal),
    retry: false,
  }, { path: 'operations/financial-fact-pages', schema: operationsFinancialFactSchema, after: after, page: page, pageSize: pageSize, filters: { ...range, scope } }, pageProjection)

  return (
    <Panel className="table-panel">
      <h2 className="table-title">{financeLabels[scope]}明细</h2>
      <PageQueryGate query={query} emptyTitle="期间无资金记录" emptyDetail="当前币种、期间和资金范围没有匹配的确认记录。">
        {(result) => (
          <>
            <DataTable label="资金流水明细" className="operations-facts-table">
              <thead><tr><th>账本流水</th><th>资金类型</th><th>最小单位金额</th><th>币种</th><th>订单号</th><th>实收号</th><th>退款单号</th><th className="time-cell">确认时间（北京时间）</th></tr></thead>
              <tbody>
                {result.items.map((row) => (
                  <tr key={row.ledgerNo}>
                    <td className="mono">{row.businessNo ?? row.ledgerNo}</td>
                    <td>{financeLabels[row.kind]}</td>
                    <td className="numeric-cell">{displayAmount(row.amount)}</td>
                    <td>{row.currency}</td>
                    <td><Link to={`/orders/${encodeURIComponent(row.orderNo)}`}>{row.orderBusinessNo ?? row.orderNo}</Link></td>
                    <td><Link to={`/receipts/${encodeURIComponent(row.receiptNo)}`}>{row.receiptBusinessNo ?? row.receiptNo}</Link></td>
                    <td>{row.refundNo ? <Link to={`/refunds/${encodeURIComponent(row.refundNo)}`}>{row.refundBusinessNo ?? row.refundNo}</Link> : '—'}</td>
                    <td className="time-cell">{formatBeijingTime(row.confirmedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </DataTable>
          </>
        )}
      </PageQueryGate>
      <NumberedPagePager numbered={query.numbered} onNumber={setPage} scope={['operations-financial-facts', range, scope]} after={after} onPage={setAfter} pageSize={pageSize} onPageSize={setPageSize}
        result={query.isSuccess ? query.data : undefined} />
    </Panel>
  )
}

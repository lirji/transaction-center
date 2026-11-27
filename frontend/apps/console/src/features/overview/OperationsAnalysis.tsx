import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Button, DataTable, Panel } from '../../components/ui'
import { api, type OperationsRange } from '../../shared/api/client'
import { QueryGate } from '../../shared/ui/QueryGate'
import { formatInteger } from '../../shared/ui/display'
import { orderLabels } from '../orders/orderStatus'

const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 })
const wholeNumber = new Intl.NumberFormat('zh-CN')

export function OperationsAnalysis({ range }: { range: OperationsRange }) {
  const [expanded, setExpanded] = useState(false)
  const query = useQuery({
    queryKey: ['operations-analysis', range],
    queryFn: ({ signal }) => api.operationsAnalysis(range, signal),
    enabled: expanded,
    retry: false,
  })
  return <Panel className="period-summary">
    <div className="panel-head">
      <h2>订单状态与日趋势</h2>
      <Button aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{expanded ? '收起分析' : '查看经营分析'}</Button>
    </div>
    {expanded && <QueryGate query={query} emptyTitle="暂无经营分析" emptyDetail="请选择交易期间。">
      {(analysis) => {
        const peak = Math.max(1, ...analysis.daily.map((daily) => Math.max(daily.orderCount, daily.paidOrderCount)))
        return <>
          <div className="cohort-bar">
            <strong>创建订单付费转化率</strong>
            <span>{analysis.payableCount ? percent.format(analysis.convertedCount / analysis.payableCount) : '—'}</span>
            <small>同一期间创建且有应付额的订单，{formatInteger(analysis.convertedCount)} / {formatInteger(analysis.payableCount)} 单已确认收款。</small>
          </div>
          {analysis.orderStates.length > 0 && <div className="queue-actions">
            {analysis.orderStates.map(({ status, count }) => <Link key={status} className="button"
              to={`/orders?${new URLSearchParams({ ...range, status, sort: 'CREATED_DESC' })}`}>
              {orderLabels[status]} {formatInteger(count)}
            </Link>)}
          </div>}
          <DataTable label="每日订单与资金" tools={false}>
            <thead><tr><th>日期（北京时间）</th><th className="numeric-cell">创建订单</th>
              <th className="numeric-cell">确认付费订单</th><th className="numeric-cell">净收款（{analysis.currency} 最小单位）</th></tr></thead>
            <tbody>{analysis.daily.map((day) => <tr key={day.day}>
              <td>{day.day}</td>
              <td className="numeric-cell"><div className="day-metric"><i aria-hidden="true" style={{ width: `${day.orderCount / peak * 100}%` }} />{formatInteger(day.orderCount)}</div></td>
              <td className="numeric-cell"><div className="day-metric day-paid"><i aria-hidden="true" style={{ width: `${day.paidOrderCount / peak * 100}%` }} />{formatInteger(day.paidOrderCount)}</div></td>
              <td className="numeric-cell">{wholeNumber.format(BigInt(day.netReceipt))}</td>
            </tr>)}</tbody>
          </DataTable>
        </>
      }}
    </QueryGate>}
  </Panel>
}

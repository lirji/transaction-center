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

export function OperationsAnalysis({ range, orderPath, showTrend, periodReady }: {
  range: OperationsRange; orderPath: string; showTrend: boolean; periodReady: boolean
}) {
  const [expanded, setExpanded] = useState(false)
  const [selectedDate, setSelectedDate] = useState<string>()
  const [showTable, setShowTable] = useState(false)
  const dataView = showTrend
  const visible = expanded || dataView
  const query = useQuery({
    queryKey: ['operations-analysis', range],
    queryFn: ({ signal }) => api.operationsAnalysis(range, signal),
    enabled: visible && periodReady,
    retry: false,
  })
  return <Panel className="period-summary">
    <div className="panel-head">
      <h2>订单状态与日趋势</h2>
      {dataView
        ? <Button aria-pressed={showTable} onClick={() => setShowTable(!showTable)}>{showTable ? '查看图表' : '查看数据表'}</Button>
        : <Button aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{expanded ? '收起分析' : '查看经营分析'}</Button>}
    </div>
    {visible && !periodReady && <p className="panel-note" role="status">正在读取统计期间…</p>}
    {visible && periodReady && <QueryGate query={query} emptyTitle="暂无经营分析" emptyDetail="请选择交易期间。">
      {(analysis) => {
        const peak = Math.max(1, ...analysis.daily.map((daily) => Math.max(daily.orderCount, daily.paidOrderCount)))
        const selected = analysis.daily.find(({ day }) => day === selectedDate) ?? analysis.daily.at(-1)
        const statusContext = <>
          <div className="cohort-bar">
            <strong>创建订单付费转化率</strong>
            <span>{analysis.payableCount ? percent.format(analysis.convertedCount / analysis.payableCount) : '—'}</span>
            <small>同一期间创建且有应付额的订单，{formatInteger(analysis.convertedCount)} / {formatInteger(analysis.payableCount)} 单已确认收款。</small>
          </div>
          {analysis.orderStates.length > 0 && <div className="queue-actions">
            {analysis.orderStates.map(({ status, count }) => <Link key={status} className="button"
              to={`${orderPath}?${new URLSearchParams({ ...range, status, sort: 'CREATED_DESC' })}`}>
              {orderLabels[status]} {formatInteger(count)}
            </Link>)}
          </div>}
        </>
        return <>
          {!dataView && statusContext}
          {dataView && !showTable && <div className="order-trend">
            <div className="trend-heading">
              <div className="chart-legend"><span>创建订单</span><span>确认付费</span></div>
              <div role="status">{selected ? `${selected.day} · 创建 ${formatInteger(selected.orderCount)} 单 · 确认付费 ${formatInteger(selected.paidOrderCount)} 单` : '暂无日趋势'}</div>
            </div>
            <div className="chart-scroll">
              <div className="daily-chart" role="group" aria-label="每日订单趋势（北京时间）">
                {analysis.daily.map((day) => <button type="button" key={day.day}
                  aria-label={`${day.day} 创建 ${day.orderCount} 单，确认付费 ${day.paidOrderCount} 单`}
                  aria-pressed={selected?.day === day.day} onClick={() => setSelectedDate(day.day)}>
                  <span className="chart-bars" aria-hidden="true">
                    <i style={{ height: `${100 * day.orderCount / peak}%` }} />
                    <i style={{ height: `${100 * day.paidOrderCount / peak}%` }} />
                  </span>
                  <span aria-hidden="true">{day.day.slice(5)}</span>
                </button>)}
              </div>
            </div>
          </div>}
          {(!dataView || showTable) && <DataTable label="每日订单与资金" tools={false}>
            <thead><tr><th>日期（北京时间）</th><th className="numeric-cell">创建订单</th>
              <th className="numeric-cell">确认付费订单</th><th className="numeric-cell">净收款（{analysis.currency} 最小单位）</th></tr></thead>
            <tbody>{analysis.daily.map((day) => <tr key={day.day}>
              <td>{day.day}</td>
              <td className="numeric-cell"><div className="day-metric"><i aria-hidden="true" style={{ width: `${day.orderCount / peak * 100}%` }} />{formatInteger(day.orderCount)}</div></td>
              <td className="numeric-cell"><div className="day-metric day-paid"><i aria-hidden="true" style={{ width: `${day.paidOrderCount / peak * 100}%` }} />{formatInteger(day.paidOrderCount)}</div></td>
              <td className="numeric-cell">{wholeNumber.format(BigInt(day.netReceipt))}</td>
            </tr>)}</tbody>
          </DataTable>}
          {dataView && statusContext}
        </>
      }}
    </QueryGate>}
  </Panel>
}

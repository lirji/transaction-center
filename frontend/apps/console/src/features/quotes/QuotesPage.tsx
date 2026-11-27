import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Field, PageHeader, PagePager, Panel, DataTable } from '../../components/ui'
import { api, type AdminQuoteView } from '../../shared/api/client'
import { formatMinorAmount } from '../../shared/api/money'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { formatBeijingTime } from '../../shared/ui/display'
import { isoFromSearch, useAdminListSearch, useListLinkState } from '../../shared/ui/useAdminListSearch'

export function QuoteRows({ rows, observedAt }: { rows: AdminQuoteView[]; observedAt?: string }) {
  const listState = useListLinkState('/quotes')
  return (
    <DataTable label="报价列表" className="trade-list-table pin-key">
      <thead><tr><th>报价号</th><th>会员 ID</th><th className="numeric-cell">应付金额</th><th>币种</th><th>报价策略</th><th className="time-cell">创建时间（北京时间）</th><th className="time-cell">有效至（北京时间）</th><th>有效性</th></tr></thead>
      <tbody>
        {rows.map((row) => {
          const expired = observedAt ? Date.parse(observedAt) >= Date.parse(row.expiresAt) : undefined
          return (
            <tr key={row.quoteId}>
              <td><Link to={`/quotes/${encodeURIComponent(row.quoteId)}`} state={listState} className="mono">{row.businessNo ?? row.quoteId}</Link></td>
              <td className="mono">{row.memberId}</td>
              <td className="numeric-cell">{formatMinorAmount(row.payableAmount)}</td>
              <td>{row.currency}</td>
              <td className="mono">{row.pricingPolicy}</td>
              <td className="time-cell">{formatBeijingTime(row.createdAt)}</td>
              <td className="time-cell">{formatBeijingTime(row.expiresAt)}</td>
              <td>{expired === undefined ? '—' : expired ? '已过期' : '有效'}</td>
            </tr>
          )
        })}
      </tbody>
    </DataTable>
  )
}

export function QuotesPage() {
  const { params, after, apply, setAfter } = useAdminListSearch()
  const [quoteId, setQuoteId] = useState(() => params.get('quoteId') ?? '')
  const [memberId, setMemberId] = useState(() => params.get('memberId') ?? '')
  const [from, setFrom] = useState(() => isoFromSearch(params, 'createdFrom'))
  const [to, setTo] = useState(() => isoFromSearch(params, 'createdTo'))
  const [formError, setFormError] = useState('')
  const filters = {
    quoteId: params.get('quoteId') || undefined,
    memberId: params.get('memberId') || undefined,
    createdFrom: params.get('createdFrom') || undefined,
    createdTo: params.get('createdTo') || undefined,
  }
  const memberUrlError = filters.memberId ? (() => {
    try { parseMemberId(filters.memberId); return '' } catch { return 'memberId 须为正整数文本' }
  })() : ''
  const incompleteWindow = Boolean(filters.createdFrom) !== Boolean(filters.createdTo)
  const query = useQuery({
    queryKey: ['admin-quotes', after, filters],
    queryFn: () => api.quotes({ after, ...filters }),
    retry: false,
    enabled: !memberUrlError && !incompleteWindow,
  })

  function submit() {
    setFormError('')
    try {
      const window = parseTimeWindow(from, to)
      apply({
        quoteId: quoteId.trim() || undefined,
        memberId: memberId.trim() ? parseMemberId(memberId) : undefined,
        createdFrom: window?.createdFrom,
        createdTo: window?.createdTo,
      })
    } catch (cause) {
      setFormError(cause instanceof FormValidationError || cause instanceof Error ? cause.message : '筛选无效')
    }
  }

  function resetFilters() {
    setQuoteId('')
    setMemberId('')
    setFrom('')
    setTo('')
    setFormError('')
    apply({})
  }

  return (
    <div className="workspace">
      <PageHeader
        title="报价"
        description="不可变报价快照。观察时刻晚于 expiresAt 可标过期；未过期不等于可下单。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar onSubmit={submit} onReset={resetFilters} invalid={Boolean(formError) || incompleteWindow}
          activeAdvanced={[filters.createdFrom || filters.createdTo].filter(Boolean).length}
          advanced={<>
            <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
          </>}>
          <Field label="报价号"><input value={quoteId} onChange={(event) => setQuoteId(event.target.value)} /></Field>
          <Field label="会员 ID"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        <h2 className="table-title">报价列表</h2>
        {memberUrlError || incompleteWindow ? (
          <p className="invalid-filter" role="alert">当前筛选无效，请检查或清空筛选条件。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="没有报价" emptyDetail="当前筛选没有报价记录。">
            {(page) => (
              <>
                <QuoteRows rows={page.items} observedAt={page.observedAt} />
                <PagePager count={page.items.length} after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}

import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Field, PageHeader, PagePager, Panel } from '../../components/ui'
import { api, type AdminQuoteView } from '../../shared/api/client'
import { formatMinor } from '../../shared/api/money'
import { FormValidationError, parseMemberId, parseTimeWindow } from '../../shared/api/validate'
import { PageQueryGate } from '../../shared/ui/QueryGate'
import { FilterBar, TimeRangeFields } from '../../shared/ui/FilterBar'
import { isoFromSearch, useAdminListSearch } from '../../shared/ui/useAdminListSearch'

export function QuoteRows({ rows, observedAt }: { rows: AdminQuoteView[]; observedAt?: string }) {
  return (
    <table className="data-table">
      <thead><tr><th>报价</th><th>会员</th><th>应付</th><th>策略</th><th>失效</th></tr></thead>
      <tbody>
        {rows.map((row) => {
          const expired = observedAt ? observedAt >= row.expiresAt : false
          return (
            <tr key={row.quoteId}>
              <td><Link to={`/quotes/${encodeURIComponent(row.quoteId)}`} className="mono">{row.quoteId}</Link></td>
              <td className="mono">{row.memberId}</td>
              <td>{formatMinor(row.payableAmount, row.currency)}<small className="mono">{row.currency} {row.payableAmount}</small></td>
              <td className="mono">{row.pricingPolicy}</td>
              <td className="mono">{row.expiresAt}{expired ? ' · 已过期' : ''}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
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

  return (
    <div className="workspace">
      <PageHeader
        eyebrow="Quotes"
        title="报价"
        description="不可变报价快照。观察时刻晚于 expiresAt 可标过期；未过期不等于可下单。"
      />
      <Panel>
        <h2>筛选</h2>
        <FilterBar onSubmit={submit}>
          <Field label="quoteId"><input value={quoteId} onChange={(event) => setQuoteId(event.target.value)} /></Field>
          <Field label="memberId"><input value={memberId} onChange={(event) => setMemberId(event.target.value)} /></Field>
          <TimeRangeFields from={from} to={to} onFrom={setFrom} onTo={setTo} error={formError} />
        </FilterBar>
      </Panel>
      <Panel className="table-panel">
        {memberUrlError || incompleteWindow ? (
          <p className="empty-state" style={{ minHeight: 120 }}>当前筛选无效，未向服务器发送请求。</p>
        ) : (
          <PageQueryGate query={query} emptyTitle="没有报价" emptyDetail="当前筛选没有报价记录。">
            {(page) => (
              <>
                <QuoteRows rows={page.items} observedAt={page.observedAt} />
                <PagePager after={after} nextAfter={page.nextAfter} hasMore={page.hasMore} observedAt={page.observedAt} onReset={() => setAfter('0')} onNext={setAfter} />
              </>
            )}
          </PageQueryGate>
        )}
      </Panel>
    </div>
  )
}

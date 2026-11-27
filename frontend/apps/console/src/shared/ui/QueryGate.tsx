import type { ReactNode } from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import { Button, EmptyState, StateBanner } from '../../components/ui'
import { TableQueryContext } from './tableQuery'
import { ApiProblem, describeProblem, isVersionConflict } from '../api/client'

export function QueryError({ query }: { query: UseQueryResult<unknown> }) {
  const problem = describeProblem(query.error)
  const retryable = query.error instanceof ApiProblem && query.error.retryable
  return (
    <>
      <StateBanner tone="error" title={problem.title} detail={problem.detail} />
      {retryable ? <div className="state-actions"><Button disabled={query.isFetching} onClick={() => void query.refetch()}>重试</Button></div> : null}
    </>
  )
}

export function QueryGate<T>({ query, emptyTitle, emptyDetail, children }: {
  query: UseQueryResult<T>
  emptyTitle: string
  emptyDetail: string
  children: (data: T) => ReactNode
}) {
  if (query.isLoading) return <QuerySkeleton />
  if (query.isError) return <QueryError query={query} />
  const data = query.data
  if (data === undefined || (Array.isArray(data) && data.length === 0)) {
    return <EmptyState title={emptyTitle} detail={emptyDetail} action={<Button tone="ghost" loading={query.isFetching} onClick={() => void query.refetch()}>重新查询</Button>} />
  }
  return <TableQueryContext.Provider value={{ refreshing: query.isFetching, refresh: () => void query.refetch() }}>
    {children(data)}
  </TableQueryContext.Provider>
}

export function PageQueryGate<T extends { items: unknown[] }>({ query, emptyTitle, emptyDetail, children }: {
  query: UseQueryResult<T>
  emptyTitle: string
  emptyDetail: string
  children: (data: T) => ReactNode
}) {
  if (query.isLoading) return <QuerySkeleton />
  if (query.isError) return <QueryError query={query} />
  const data = query.data
  if (!data || data.items.length === 0) {
    return <EmptyState title={emptyTitle} detail={emptyDetail} action={<Button tone="ghost" loading={query.isFetching} onClick={() => void query.refetch()}>重新查询</Button>} />
  }
  return <TableQueryContext.Provider value={{ refreshing: query.isFetching, refresh: () => void query.refetch() }}>
    {children(data)}
  </TableQueryContext.Provider>
}

function QuerySkeleton() {
  return <div className="query-skeleton" role="status" aria-label="正在读取">
    <span /><span /><span />
  </div>
}

export function MutationBanner({ error, success }: { error: unknown; success?: string }) {
  if (error) {
    const problem = describeProblem(error)
    return <StateBanner tone={isVersionConflict(error) ? 'warn' : 'error'} title={problem.title} detail={problem.detail} />
  }
  if (success) return <StateBanner tone="success" title="已写入" detail={success} />
  return null
}

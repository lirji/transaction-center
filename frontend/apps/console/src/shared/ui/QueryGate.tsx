import type { ReactNode } from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import { Button, EmptyState, StateBanner } from '../../components/ui'
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
  if (query.isLoading) return <StateBanner tone="loading" title="正在读取" detail="正在获取当前记录，请稍候。" />
  if (query.isError) return <QueryError query={query} />
  const data = query.data
  if (data === undefined || (Array.isArray(data) && data.length === 0)) {
    return <EmptyState title={emptyTitle} detail={emptyDetail} />
  }
  return children(data)
}

export function PageQueryGate<T extends { items: unknown[] }>({ query, emptyTitle, emptyDetail, children }: {
  query: UseQueryResult<T>
  emptyTitle: string
  emptyDetail: string
  children: (data: T) => ReactNode
}) {
  if (query.isLoading) return <StateBanner tone="loading" title="正在读取" detail="正在获取当前记录，请稍候。" />
  if (query.isError) return <QueryError query={query} />
  const data = query.data
  if (!data || data.items.length === 0) {
    return <EmptyState title={emptyTitle} detail={emptyDetail} />
  }
  return children(data)
}

export function MutationBanner({ error, success }: { error: unknown; success?: string }) {
  if (error) {
    const problem = describeProblem(error)
    return <StateBanner tone={isVersionConflict(error) ? 'warn' : 'error'} title={problem.title} detail={problem.detail} />
  }
  if (success) return <StateBanner tone="success" title="已写入" detail={success} />
  return null
}

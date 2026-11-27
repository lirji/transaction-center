import { useState } from 'react'
import { useQuery, type QueryFunctionContext, type QueryKey } from '@tanstack/react-query'
import type { ZodType } from 'zod'
import { api } from '../api/client'
import type { QueryScalar } from '../api/keys'
import type { NumberedPage, Page } from '../api/schemas'

export function useLocalListPage<Cursor extends string | number>(first: Cursor) {
  const [cursor, setCursor] = useState(first)
  const [page, setPage] = useState(1)
  const setAfter = (after: Cursor) => {
    setCursor(after)
    setPage(1)
  }
  return [cursor, setAfter, page, setPage] as const
}

type NumberedSource<Row> = {
  path: string
  schema: ZodType<Row>
  after: string | number
  page: number
  pageSize: number
  filters?: Record<string, QueryScalar>
}

export function useNumberedList<Row, Data>(options: {
  queryKey: QueryKey
  queryFn: (context: QueryFunctionContext) => Promise<Data>
  enabled?: boolean
  retry?: boolean
}, source: NumberedSource<Row>, project: (data: NumberedPage<Row>) => Data) {
  const result = useQuery({
    ...options,
    queryKey: [...options.queryKey, source.page],
    queryFn: async (context) => {
      const page = String(source.after) === '0'
        ? await api.numberedList(source.path, source.schema, source.page, source.pageSize, source.filters ?? {}, context.signal)
        : undefined
      return page ? { numbered: page, value: project(page) } : { value: await options.queryFn(context) }
    },
  })
  if (result.isSuccess) {
    return { ...result, data: result.data.value, numbered: result.data.numbered }
  }
  return { ...result, data: result.data?.value, numbered: result.data?.numbered }
}

export function pageItems<Row>(page: NumberedPage<Row>) { return page.items }

export function pageProjection<Row>(page: NumberedPage<Row>): Page<Row> {
  return { items: page.items, observedAt: page.observedAt, nextAfter: null, hasMore: false }
}

export function auditProjection<Row>(page: NumberedPage<Row>) {
  return { items: page.items, nextCursor: null }
}

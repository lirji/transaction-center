import { useLocation, useSearchParams } from 'react-router-dom'
import { usePageSize, validPageSize } from './usePageSize'
import { toDatetimeLocal } from '../api/validate'

export function useAdminListSearch() {
  const [params, setParams] = useSearchParams()
  const { pathname } = useLocation()
  const [preferredSize, rememberSize] = usePageSize(pathname, 20)
  const after = params.get('after') || '0'
  // 旧链接没有页容量时继续解释为原默认值，已选容量随新链接传递。
  const pageSize = validPageSize(params.get('size'), after === '0' ? preferredSize : 20)

  function apply(next: Record<string, string | undefined>) {
    const search = new URLSearchParams()
    if (pageSize !== 20 || params.has('size')) search.set('size', String(pageSize))
    for (const [key, value] of Object.entries(next)) {
      if (value) search.set(key, value)
    }
    setParams(search, { replace: true })
  }

  function setAfter(value: string) {
    const search = new URLSearchParams(params)
    if (pageSize !== 20) search.set('size', String(pageSize))
    if (!value || value === '0') search.delete('after')
    else search.set('after', value)
    setParams(search, { replace: true })
  }

  function setPageSize(value: number) {
    const size = validPageSize(value, 20)
    rememberSize(size)
    const search = new URLSearchParams(params)
    search.delete('after')
    search.set('size', String(size))
    setParams(search, { replace: true })
  }

  function remove(keys: string[]) {
    const search = new URLSearchParams(params)
    keys.forEach((key) => search.delete(key))
    search.delete('after')
    if (pageSize !== 20) search.set('size', String(pageSize))
    setParams(search, { replace: true })
  }

  return { params, after, apply, remove, setAfter, pageSize, setPageSize }
}

export function isoFromSearch(params: URLSearchParams, key: string) {
  return toDatetimeLocal(params.get(key))
}

export function useListLinkState(path: string) {
  const location = useLocation()
  return location.pathname === path ? { listSearch: location.search } : undefined
}

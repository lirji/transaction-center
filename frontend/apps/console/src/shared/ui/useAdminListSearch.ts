import { useLocation, useSearchParams } from 'react-router-dom'
import { toDatetimeLocal } from '../api/validate'

export function useAdminListSearch() {
  const [params, setParams] = useSearchParams()
  const after = params.get('after') || '0'

  function apply(next: Record<string, string | undefined>) {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(next)) {
      if (value) search.set(key, value)
    }
    setParams(search, { replace: true })
  }

  function setAfter(value: string) {
    const search = new URLSearchParams(params)
    if (!value || value === '0') search.delete('after')
    else search.set('after', value)
    setParams(search, { replace: true })
  }

  return { params, after, apply, setAfter }
}

export function isoFromSearch(params: URLSearchParams, key: string) {
  return toDatetimeLocal(params.get(key))
}

export function useListLinkState(path: string) {
  const location = useLocation()
  return location.pathname === path ? { listSearch: location.search } : undefined
}

import { formatBeijingTime } from './display'

type FilterDescription = [label: string, keys: string | string[], reset: () => void, format?: (value: string) => string]

export type AppliedFilter = { id: string; label: string; value: string; clear: () => void }

export function appliedFilters(params: URLSearchParams, remove: (keys: string[]) => void, filters: FilterDescription[]): AppliedFilter[] {
  return filters.flatMap(([label, keys, reset, format]) => {
    const fields = typeof keys === 'string' ? [keys] : keys
    const raw = fields.flatMap((key) => params.get(key) || [])
    if (!raw.length) return []
    const display = format ?? (typeof keys === 'string' ? (value: string) => value : formatBeijingTime)
    return [{
      id: fields[0], label, value: raw.map(display).join(' 至 '),
      clear: () => { reset(); remove(fields) },
    }]
  })
}

export function strongEtag(version: number): string {
  return `"${version}"`
}

export function cursorQuery(after: number, limit = 20): string {
  const bounded = Math.min(Math.max(limit, 1), 100)
  const search = new URLSearchParams({ after: String(Math.max(after, 0)), limit: String(bounded) })
  return `?${search.toString()}`
}

export type QueryScalar = string | number | boolean | undefined

function boundedLimit(limit?: number) {
  return String(Math.min(Math.max(limit ?? 20, 1), 100))
}

function appendKnown(search: URLSearchParams, params: Record<string, QueryScalar>) {
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    if (typeof value === 'boolean') search.set(key, value ? 'true' : 'false')
    else search.set(key, String(value))
  }
}

export function pageQuery(params: Record<string, QueryScalar> = {}): string {
  const { after, limit, ...rest } = params
  const search = new URLSearchParams()
  search.set('after', after === undefined || after === '' ? '0' : String(after))
  search.set('limit', boundedLimit(typeof limit === 'number' ? limit : undefined))
  appendKnown(search, rest)
  return `?${search.toString()}`
}

export type RefundQueueQuery = {
  status?: string
  after?: string | number
  limit?: number
  refundNo?: string
  orderNo?: string
  memberId?: string
  createdFrom?: string
  createdTo?: string
  approvalDeadlineFrom?: string
  approvalDeadlineTo?: string
}

export function refundQueueQuery(query: RefundQueueQuery = {}): string {
  const search = new URLSearchParams()
  search.set('after', query.after === undefined || query.after === '' ? '0' : String(query.after))
  search.set('limit', boundedLimit(query.limit))
  search.set('status', query.status ?? 'PENDING_APPROVAL')
  appendKnown(search, {
    refundNo: query.refundNo,
    orderNo: query.orderNo,
    memberId: query.memberId,
    createdFrom: query.createdFrom,
    createdTo: query.createdTo,
    approvalDeadlineFrom: query.approvalDeadlineFrom,
    approvalDeadlineTo: query.approvalDeadlineTo,
  })
  return `?${search.toString()}`
}

export function nextCursor(id: string | undefined, pageLength: number, limit: number): number | undefined {
  if (!id || pageLength < limit) return undefined
  if (!/^[1-9][0-9]{0,18}$/.test(id)) return undefined
  const value = Number(id)
  if (!Number.isSafeInteger(value)) return undefined
  return value
}

const KEY_PREFIX = 'tc.idem.'

export function rememberIdempotencyKey(action: string, bodyHash: string): string {
  const storeKey = `${KEY_PREFIX}${action}`
  try {
    const raw = sessionStorage.getItem(storeKey)
    if (raw) {
      const parsed = JSON.parse(raw) as { key?: string; bodyHash?: string }
      if (parsed.key && parsed.bodyHash === bodyHash) return parsed.key
    }
    const key = crypto.randomUUID()
    sessionStorage.setItem(storeKey, JSON.stringify({ key, bodyHash }))
    return key
  } catch {
    return crypto.randomUUID()
  }
}

export function forgetIdempotencyKey(action: string) {
  try {
    sessionStorage.removeItem(`${KEY_PREFIX}${action}`)
  } catch {
    /* ignore quota / private mode */
  }
}

export function bodyHash(value: unknown): string {
  return JSON.stringify(value)
}

import { formatBeijingInput, parseBeijingInput } from './businessTime'

export class FormValidationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'FormValidationError'
  }
}

export const CHANNEL_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/

export function parseChannelCode(raw: string): string {
  const value = raw.trim()
  if (!CHANNEL_CODE_PATTERN.test(value)) {
    throw new FormValidationError('channelCode 须为大写字母开头，仅含 A-Z、数字和下划线，最长 64')
  }
  return value
}

export function parseCurrencyCode(raw: string): string {
  const value = raw.trim().toUpperCase()
  if (!/^[A-Z]{3}$/.test(value)) {
    throw new FormValidationError('币种必须是 3 位大写字母')
  }
  return value
}

export function parseHttpsUrl(raw: string, label = 'URL'): string {
  const value = raw.trim()
  if (!value.startsWith('https://')) {
    throw new FormValidationError(`${label} 必须是 HTTPS`)
  }
  try {
    new URL(value)
  } catch {
    throw new FormValidationError(`${label} 不是合法 URI`)
  }
  return value
}

const MEMBER_ID = /^[1-9][0-9]{0,18}$/
const MAX_WINDOW_MS = 90 * 24 * 60 * 60 * 1000

export function parseMemberId(raw: string, label = 'memberId'): string {
  const value = raw.trim()
  if (!MEMBER_ID.test(value)) {
    throw new FormValidationError(`${label} 须为正整数文本`)
  }
  return value
}

export function parseTimeWindow(fromLocal: string, toLocal: string): { createdFrom: string; createdTo: string } | undefined {
  const from = fromLocal.trim()
  const to = toLocal.trim()
  if (!from && !to) return undefined
  if (!from || !to) throw new FormValidationError('时间窗必须同时提供起止时间')
  const fromTimestamp = parseBeijingInput(from)
  const toTimestamp = parseBeijingInput(to)
  if (!Number.isFinite(fromTimestamp) || !Number.isFinite(toTimestamp)) {
    throw new FormValidationError('时间窗格式无效')
  }
  if (fromTimestamp >= toTimestamp) {
    throw new FormValidationError('开始时间必须早于结束时间')
  }
  if (toTimestamp - fromTimestamp > MAX_WINDOW_MS) {
    throw new FormValidationError('时间窗最大 90 天')
  }
  return { createdFrom: new Date(fromTimestamp).toISOString(), createdTo: new Date(toTimestamp).toISOString() }
}

export function toDatetimeLocal(iso: string | null | undefined): string {
  const timestamp = iso ? Date.parse(iso) : Number.NaN
  return Number.isFinite(timestamp) ? formatBeijingInput(timestamp) : ''
}

export function parseReceiptDispositionFilter(abnormalOnly: boolean, disposition: string) {
  if (abnormalOnly && disposition === 'NORMAL') {
    throw new FormValidationError('abnormalOnly 与 disposition=NORMAL 互斥')
  }
  return {
    abnormalOnly: abnormalOnly || undefined,
    disposition: disposition || undefined,
  }
}

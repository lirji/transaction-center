const MAX_SAFE = Number.MAX_SAFE_INTEGER

export class IntegerParseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'IntegerParseError'
  }
}

export function parseInteger(raw: string, label: string, options?: { min?: number; max?: number; allowZero?: boolean }): number {
  const value = raw.trim()
  if (!value) throw new IntegerParseError(`${label}不能为空`)
  if (!/^-?(0|[1-9][0-9]*)$/.test(value)) throw new IntegerParseError(`${label}必须是整数，不能有小数、前导零或科学计数法`)
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed)) throw new IntegerParseError(`${label}超出安全整数范围`)
  const min = options?.min ?? (options?.allowZero ? 0 : Number.NEGATIVE_INFINITY)
  const max = options?.max ?? MAX_SAFE
  if (parsed < min || parsed > max) throw new IntegerParseError(`${label}超出允许范围`)
  return parsed
}

export function parsePositiveId(raw: string, label: string): number {
  return parseInteger(raw, label, { min: 1, max: MAX_SAFE })
}

export function formatMinor(amount: number, currency: string): string {
  if (!Number.isInteger(amount)) return `${currency} ${amount}`
  const negative = amount < 0
  const abs = Math.abs(amount)
  const major = Math.trunc(abs / 100)
  const minor = String(abs % 100).padStart(2, '0')
  return `${negative ? '-' : ''}${currency} ${major}.${minor}`
}

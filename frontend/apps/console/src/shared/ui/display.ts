export function dash(value: string | number | null | undefined) {
  return value == null || value === '' ? '—' : String(value)
}

export function statusTone(status: string): 'good' | 'warn' | 'danger' | 'neutral' | 'info' {
  if (['COMPLETED', 'PAID', 'SUCCEEDED', 'ACTIVE', 'INSPECTED', 'DONE'].includes(status)) return 'good'
  if (['CANCELLED', 'FAILED', 'REJECTED', 'EXPIRED', 'FROZEN'].includes(status)) return 'danger'
  if (['PENDING_PAYMENT', 'CLOSING', 'PENDING', 'PENDING_APPROVAL', 'REVIEW', 'UNKNOWN', 'READY', 'OPEN', 'RUNNING'].includes(status)) return 'warn'
  return 'neutral'
}

export function dash(value: string | number | null | undefined) {
  return value == null || value === '' ? '—' : String(value)
}

export function statusTone(status: string): 'good' | 'warn' | 'danger' | 'neutral' | 'info' {
  if (['COMPLETED', 'PAID', 'SUCCEEDED', 'ACTIVE', 'INSPECTED', 'DONE'].includes(status)) return 'good'
  if (['CANCELLED', 'FAILED', 'REJECTED', 'EXPIRED', 'FROZEN'].includes(status)) return 'danger'
  if (['PENDING_PAYMENT', 'CLOSING', 'PENDING', 'PENDING_APPROVAL', 'REVIEW', 'UNKNOWN', 'READY', 'OPEN', 'RUNNING'].includes(status)) return 'warn'
  return 'neutral'
}

const beijingTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

export function formatBeijingTime(value: string | null | undefined) {
  const timestamp = value ? Date.parse(value) : Number.NaN
  return Number.isNaN(timestamp) ? '—' : beijingTime.format(timestamp)
}

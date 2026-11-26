const zone = 'Asia/Shanghai'
const dateTime = new Intl.DateTimeFormat('en', {
  timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})
const zoneOffset = new Intl.DateTimeFormat('en', { timeZone: zone, timeZoneName: 'longOffset' })
const offsetPart: Intl.DateTimeFormatPartTypes = 'timeZoneName'
const localTimePattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/

function fields(timestamp: number) {
  return Object.fromEntries(dateTime.formatToParts(timestamp).map(({ type, value }) => [type, value]))
}

export function formatBeijingInput(timestamp: number) {
  const { year, month, day, hour, minute, second } = fields(timestamp)
  const milliseconds = new Date(timestamp).getUTCMilliseconds()
  const fraction = milliseconds ? `.${String(milliseconds).padStart(3, '0')}` : ''
  return `${year.padStart(4, '0')}-${month}-${day}T${hour}:${minute}:${second}${fraction}`
}

function offsetAt(timestamp: number) {
  const offset = zoneOffset.formatToParts(timestamp).find(({ type }) => type === offsetPart)?.value
  if (offset === 'GMT') return 0
  const match = /^GMT([+-])(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(offset ?? '')
  if (!match) return Number.NaN
  const [, sign, hours, minutes, seconds = '0'] = match
  return (sign === '-' ? -1 : 1) * (Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds)) * 1000
}

export function parseBeijingInput(value: string) {
  if (!localTimePattern.test(value)) return Number.NaN
  const wallClock = Date.parse(`${value}Z`)
  if (!Number.isFinite(wallClock)) return Number.NaN
  const firstGuess = wallClock - offsetAt(wallClock)
  const timestamp = wallClock - offsetAt(firstGuess)
  if (!Number.isFinite(timestamp)) return Number.NaN
  // 回读以拒绝无效日期以及历史时区跳变中不存在的本地时刻。
  const normalized = formatBeijingInput(timestamp)
  return Date.parse(`${normalized}Z`) === wallClock &&
    normalized.slice(0, 16) === value.slice(0, 16) ? timestamp : Number.NaN
}

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

export const businessRangeOptions = [
  ['today', '今天'], ['yesterday', '昨天'], ['last7', '近 7 天'], ['last30', '近 30 天'], ['month', '本月'],
] as const
export type BusinessRange = (typeof businessRangeOptions)[number][0]

export function beijingRange(range: BusinessRange, now = Date.now()) {
  // UTC 对象仅用于北京日历日期的加减，最终仍由现有北京时间转换器解析。
  const day = formatBeijingInput(now).slice(0, 10)
  const start = new Date(`${day}T00:00:00Z`)
  const end = new Date(start)
  end.setUTCDate(end.getUTCDate() + 1)
  if (range === 'yesterday') {
    start.setUTCDate(start.getUTCDate() - 1)
    end.setUTCDate(end.getUTCDate() - 1)
  } else if (range === 'month') {
    start.setUTCDate(1)
  } else {
    start.setUTCDate(start.getUTCDate() - (range === 'last30' ? 29 : range === 'last7' ? 6 : 0))
  }
  return { from: start.toISOString().slice(0, 16), to: end.toISOString().slice(0, 16) }
}

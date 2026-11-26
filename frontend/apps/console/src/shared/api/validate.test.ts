import { describe, expect, it } from 'vitest'
import { parseChannelCode, parseCurrencyCode, parseHttpsUrl, parseMemberId, parseReceiptDispositionFilter, parseTimeWindow, toDatetimeLocal } from './validate'

describe('parseChannelCode', () => {
  it('accepts OpenAPI channel codes', () => {
    expect(parseChannelCode('WECHAT_PAY')).toBe('WECHAT_PAY')
    expect(parseChannelCode('A')).toBe('A')
  })

  it('rejects lowercase and symbols', () => {
    expect(() => parseChannelCode('wechat')).toThrow(/channelCode/)
    expect(() => parseChannelCode('1ABC')).toThrow(/channelCode/)
  })
})

describe('parseCurrencyCode', () => {
  it('uppercases a valid code', () => {
    expect(parseCurrencyCode('cny')).toBe('CNY')
  })
})

describe('parseHttpsUrl', () => {
  it('requires https', () => {
    expect(parseHttpsUrl('https://pay.example/return')).toBe('https://pay.example/return')
    expect(() => parseHttpsUrl('http://pay.example/return')).toThrow(/HTTPS/)
  })
})

describe('parseMemberId', () => {
  it('accepts a positive integer text', () => {
    expect(parseMemberId('900001')).toBe('900001')
  })

  it('rejects leading zeros', () => {
    expect(() => parseMemberId('01')).toThrow(/正整数/)
  })
})

describe('parseTimeWindow', () => {
  it('requires both ends', () => {
    expect(() => parseTimeWindow('2026-01-01T00:00', '')).toThrow(/同时提供/)
    expect(parseTimeWindow('', '')).toBeUndefined()
  })

  it('round trips Beijing time through the UTC query without using the device time zone', () => {
    const range = parseTimeWindow('2026-11-26T09:15:13.125', '2026-11-26T10:15')
    expect(range).toEqual({ createdFrom: '2026-11-26T01:15:13.125Z', createdTo: '2026-11-26T02:15:00.000Z' })
    expect(toDatetimeLocal(range?.createdFrom)).toBe('2026-11-26T09:15:13.125')
    expect(() => parseTimeWindow('2026-02-30T09:15', '2026-03-01T10:00')).toThrow(/格式/)
  })

  it('rejects a span longer than 90 days', () => {
    expect(() => parseTimeWindow('2026-01-01T00:00:00', '2026-05-01T00:00:00')).toThrow(/90/)
  })
})

describe('parseReceiptDispositionFilter', () => {
  it('rejects abnormalOnly with NORMAL', () => {
    expect(() => parseReceiptDispositionFilter(true, 'NORMAL')).toThrow(/互斥/)
  })
})

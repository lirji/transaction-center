import { describe, expect, it } from 'vitest'
import { formatMinor, parseInteger, parsePositiveId } from './money'

describe('parseInteger', () => {
  it('accepts safe integers', () => {
    expect(parseInteger('0', 'x', { min: 0 })).toBe(0)
    expect(parseInteger('-12', 'x')).toBe(-12)
    expect(parsePositiveId('900001', 'id')).toBe(900001)
  })

  it('rejects floats, leading zeros and blanks', () => {
    expect(() => parseInteger('1.5', '单价')).toThrow(/整数/)
    expect(() => parseInteger('01', '单价')).toThrow(/整数/)
    expect(() => parseInteger(' ', '单价')).toThrow(/不能为空/)
  })
})

describe('formatMinor', () => {
  it('does not change the integer when displaying', () => {
    expect(formatMinor(199, 'CNY')).toBe('CNY 1.99')
    expect(formatMinor(0, 'CNY')).toBe('CNY 0.00')
    expect(formatMinor(-50, 'CNY')).toBe('-CNY 0.50')
  })
})

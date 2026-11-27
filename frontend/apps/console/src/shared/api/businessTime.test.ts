import { describe, expect, it } from 'vitest'
import { beijingRange } from './businessTime'

describe('Beijing date shortcuts', () => {
  it('changes the date at Beijing midnight and keeps the right edge exclusive', () => {
    const now = Date.parse('2026-10-31T16:00:00.000Z')
    expect(beijingRange('today', now)).toEqual({ from: '2026-11-01T00:00', to: '2026-11-02T00:00' })
    expect(beijingRange('yesterday', now)).toEqual({ from: '2026-10-31T00:00', to: '2026-11-01T00:00' })
    expect(beijingRange('last7', now)).toEqual({ from: '2026-10-26T00:00', to: '2026-11-02T00:00' })
    expect(beijingRange('month', now)).toEqual({ from: '2026-11-01T00:00', to: '2026-11-02T00:00' })
  })
})

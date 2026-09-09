import { describe, expect, it } from 'vitest'
import { bodyHash, nextCursor, pageQuery, refundQueueQuery, rememberIdempotencyKey, strongEtag } from './keys'

describe('strongEtag', () => {
  it('quotes the version as a strong validator', () => {
    expect(strongEtag(0)).toBe('"0"')
    expect(strongEtag(12)).toBe('"12"')
  })
})

describe('nextCursor', () => {
  it('uses the last id only when the page is full', () => {
    expect(nextCursor('15', 20, 20)).toBe(15)
    expect(nextCursor('15', 3, 20)).toBeUndefined()
    expect(nextCursor('abc', 20, 20)).toBeUndefined()
  })
})

describe('rememberIdempotencyKey', () => {
  it('reuses the key for the same body and issues a new one after the body changes', () => {
    const first = rememberIdempotencyKey('demo', bodyHash({ a: 1 }))
    const again = rememberIdempotencyKey('demo', bodyHash({ a: 1 }))
    const changed = rememberIdempotencyKey('demo', bodyHash({ a: 2 }))
    expect(again).toBe(first)
    expect(changed).not.toBe(first)
  })
})

describe('pageQuery', () => {
  it('defaults after to 0 and omits empty filters', () => {
    expect(pageQuery({ orderNo: 'ORD-1', memberId: '' })).toBe('?after=0&limit=20&orderNo=ORD-1')
  })

  it('serializes boolean false for leaseExpired', () => {
    expect(pageQuery({ after: '9', leaseExpired: false })).toBe('?after=9&limit=20&leaseExpired=false')
  })
})

describe('refundQueueQuery', () => {
  it('sends status ALL explicitly', () => {
    expect(refundQueueQuery({ status: 'ALL', orderNo: 'ORD-1' })).toContain('status=ALL')
    expect(refundQueueQuery({ status: 'ALL', orderNo: 'ORD-1' })).toContain('orderNo=ORD-1')
  })

  it('defaults status to PENDING_APPROVAL', () => {
    expect(refundQueueQuery({})).toContain('status=PENDING_APPROVAL')
  })
})

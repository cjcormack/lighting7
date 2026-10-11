import { describe, expect, it } from 'vitest'
import { errorCode, venueSyncRefusal } from './venueSyncRefusal'

const refusal = (code: string, error: string) => ({ status: 409, data: { error, code } })

describe('venueSyncRefusal', () => {
  it('names each venue-sync refusal by its code and keeps the desk sentence', () => {
    expect(venueSyncRefusal(refusal('VENUE_MISSING', 'Panto is on a venue this desk does not have'))).toEqual({
      code: 'VENUE_MISSING',
      title: 'Import the venue first',
      message: 'Panto is on a venue this desk does not have',
    })
    expect(venueSyncRefusal(refusal('VENUE_VERSION_TAKEN', 'v3 was published on another desk'))?.title).toBe(
      'That version was published on another desk',
    )
    expect(venueSyncRefusal(refusal('VENUE_PUBLISH_PULL_ONLY', 'This desk only pulls'))?.code).toBe(
      'VENUE_PUBLISH_PULL_ONLY',
    )
  })

  it('answers null for any other error, coded or not', () => {
    expect(venueSyncRefusal(refusal('VENUE_DRAFT_EMPTY', 'Nothing to publish'))).toBeNull()
    expect(venueSyncRefusal({ status: 500, data: { error: 'boom' } })).toBeNull()
    expect(venueSyncRefusal({ status: 'FETCH_ERROR', error: 'TypeError' })).toBeNull()
    expect(venueSyncRefusal(null)).toBeNull()
    // An inherited key is not a refusal.
    expect(venueSyncRefusal(refusal('constructor', 'x'))).toBeNull()
    expect(venueSyncRefusal(refusal('toString', 'x'))).toBeNull()
  })

  it('reads the code off an RTK Query error body only', () => {
    expect(errorCode(refusal('VENUE_MISSING', 'x'))).toBe('VENUE_MISSING')
    expect(errorCode({ data: 'plain text' })).toBeUndefined()
    expect(errorCode({ code: 'VENUE_MISSING' })).toBeUndefined()
  })
})

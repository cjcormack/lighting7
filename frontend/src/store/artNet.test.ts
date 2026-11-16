import { describe, expect, it, vi } from 'vitest'

vi.mock('../api/lightingApi', () => ({ lightingApi: {} }))

import { reachabilityOf } from './artNet'

describe('reachabilityOf', () => {
  const frame = {
    polling: true,
    controllers: [
      { address: '10.0.0.1', state: 'REACHABLE' as const, lastReplyAt: '2026-11-16 12:00:00.000Z' },
      { address: '10.0.0.2', state: 'UNREACHABLE' as const },
    ],
  }

  it('reads a polled address, trimmed', () => {
    expect(reachabilityOf(frame, '10.0.0.1')).toBe('REACHABLE')
    expect(reachabilityOf(frame, ' 10.0.0.2 ')).toBe('UNREACHABLE')
  })

  it('is unknown for no frame, no polling, no address or one not polled', () => {
    expect(reachabilityOf(null, '10.0.0.1')).toBe('UNKNOWN')
    expect(reachabilityOf({ ...frame, polling: false }, '10.0.0.1')).toBe('UNKNOWN')
    expect(reachabilityOf(frame, null)).toBe('UNKNOWN')
    expect(reachabilityOf(frame, '')).toBe('UNKNOWN')
    expect(reachabilityOf(frame, '10.0.0.9')).toBe('UNKNOWN')
  })
})

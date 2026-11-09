import { describe, expect, it } from 'vitest'
import { senderRangeLabel } from './oscSenderRange'

describe('senderRangeLabel', () => {
  it('counts the addresses a range admits', () => {
    expect(senderRangeLabel('192.168.1.0/24')).toBe('range · 256 addresses')
    expect(senderRangeLabel(' 10.0.0.0/16 ')).toBe('range · 65,536 addresses')
    expect(senderRangeLabel('10.0.0.0/8')).toBe('range · 2^24 addresses')
    expect(senderRangeLabel('fd00::/64')).toBe('range · 2^64 addresses')
    expect(senderRangeLabel('::/0')).toBe('range · 2^128 addresses')
    expect(senderRangeLabel('fd00:0:0:0:0:0:0:0/120')).toBe('range · 256 addresses')
    expect(senderRangeLabel('::ffff:192.168.1.0/120')).toBe('range · 256 addresses')
  })

  it('is nothing for a single address, a full-length prefix or text that is not a range', () => {
    expect(senderRangeLabel('192.168.1.20')).toBeNull()
    expect(senderRangeLabel('192.168.1.20/32')).toBeNull()
    expect(senderRangeLabel('::1/128')).toBeNull()
    expect(senderRangeLabel('192.168.1.0/')).toBeNull()
    expect(senderRangeLabel('')).toBeNull()
  })

  it('is nothing for text the desk would refuse as a range', () => {
    expect(senderRangeLabel('desk.local/24')).toBeNull()
    expect(senderRangeLabel('300.1.1.1/8')).toBeNull()
    expect(senderRangeLabel('10.0.0/8')).toBeNull()
    expect(senderRangeLabel('192.168.1.0/33')).toBeNull()
    expect(senderRangeLabel('fd00::/129')).toBeNull()
    expect(senderRangeLabel('fd00:::1/64')).toBeNull()
    expect(senderRangeLabel('fd00::1::2/64')).toBeNull()
    expect(senderRangeLabel('gd00::/64')).toBeNull()
    expect(senderRangeLabel('1:2:3:4:5:6:7/64')).toBeNull()
  })
})

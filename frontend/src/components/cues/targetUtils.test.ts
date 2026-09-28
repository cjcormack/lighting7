import { describe, expect, it } from 'vitest'
import { makeFixture, makePixelBar } from '@/test/fixtureFactories'
import { elementParents } from './targetUtils'

describe('elementParents', () => {
  it('maps every head of a multi-head fixture to its parent, and nothing else', () => {
    const bar = makePixelBar('bar', 3)
    const par = makeFixture('par-1', [])
    const parents = elementParents([bar, par])

    expect(parents.get('bar.pixel-0')).toBe('bar')
    expect(parents.get('bar.pixel-2')).toBe('bar')
    // A whole fixture is no one's head.
    expect(parents.has('bar')).toBe(false)
    expect(parents.has('par-1')).toBe(false)
  })

  it('answers empty before the fixture list has loaded', () => {
    expect(elementParents(undefined).size).toBe(0)
  })
})

// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeFixture, makePixelBar } from '@/test/fixtureFactories'

const bar = { ...makePixelBar('bar', 4), name: 'Pixel Bar' }
const par = { ...makeFixture('par-1', []), name: 'Par One' }

// No placed patches, so the stage draws its fallback row of dots — one per fixture, titled by name.
// The served gel library (`GET /gels`), read from the resource the desk serves it from.
vi.mock('@/hooks/useGelIndex', async () => {
  const { indexGels } = await import('@/lib/gels')
  const gels = (await import('../../../../src/main/resources/gels.json')).default
  return { useGelIndex: () => indexGels(gels) }
})
vi.mock('@/hooks/useProjectedPatches', () => ({
  useProjectedPatches: () => ({ points: [], extraPoints: [] }),
}))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [bar, par],
    fixtureByKey: new Map([[bar.key, bar], [par.key, par]]),
    typeByKey: new Map(),
  }),
}))

import { MiniStage } from './MiniStage'

afterEach(cleanup)

const lit = (name: string) => screen.getByTitle(name).className.includes('bg-foreground/90')

describe('MiniStage', () => {
  it('lights the fixture a cell-target belongs to', () => {
    render(<MiniStage projectId={1} targets={[{ type: 'fixture', key: 'bar.pixel-2' }]} />)
    expect(lit('Pixel Bar')).toBe(true)
    expect(lit('Par One')).toBe(false)
  })
})

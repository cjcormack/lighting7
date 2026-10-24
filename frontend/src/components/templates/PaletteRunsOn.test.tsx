// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { PaletteFx } from '@/api/palettesApi'

/**
 * The effect branch's panel answers two questions a value panel never has to: **how many heads
 * could take this**, and **how fast does it go**.
 *
 * The second is the one with a trap in it. `PaletteFx` carries no `timingSource`, so a
 * `cycle` of 2 is "2 beats" or "2 seconds" depending on the FX library entry — and the two
 * readings are 60× apart, so guessing is worse than saying nothing.
 */
const fixtures = [
  { capabilities: ['dimmer', 'colour'], attributes: [{ type: 'slider', name: 'zoom', category: 'zoom' }] },
  { capabilities: ['dimmer', 'colour'] },
  { capabilities: ['dimmer'] },
  // Infrastructure: in the raw list, never counted — the two hooks disagree about it on purpose.
  { capabilities: ['dimmer', 'colour'], infrastructure: true },
]

vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: fixtures }),
  useVisibleFixtureListQuery: () => ({ data: fixtures.filter((f) => !f.infrastructure) }),
}))
vi.mock('@/store/speedMasters', () => ({
  useMaster1Uuid: () => 'master-1-uuid',
  useSpeedMasterBpm: () => 128,
  // Null is what the hook returns at master 1, which every chip reads as "draw nothing".
  useSpeedMasterDisplay: (uuid: string | null | undefined) =>
    uuid === 'master-2-uuid' ? { index: 2, name: 'Chases' } : null,
}))
vi.mock('@/components/fx/FxColourPalettes', () => ({ useColourPalettes: () => ({ swatchFor: () => null }) }))

const { PaletteRunsOn } = await import('./PaletteRunsOn')

const beatEntry: FxLibraryEntry = {
  name: 'Colour Pulse',
  family: 'colour',
  outputType: 'COLOUR',
  fxMode: 'STANDARD',
  timingSource: 'BEAT',
  parameters: [],
  compatibleAttributes: ['rgbColour'],
}

function effect(over: Partial<PaletteFx> = {}): PaletteFx {
  return {
    fxType: 'Colour Pulse',
    family: 'colour',
    cycle: 2,
    blendMode: 'OVERRIDE',
    order: 'NORMAL',
    parameters: {},
    ...over,
  }
}

afterEach(cleanup)

describe('PaletteRunsOn', () => {
  it('counts the heads of the family, not the whole patch', () => {
    render(<PaletteRunsOn family="COLOUR" effect={effect()} entry={beatEntry} />)
    expect(screen.getByText('2 heads')).toBeTruthy()
    expect(screen.getByText(/any fixture with colour/i)).toBeTruthy()
  })

  it('reads a beat effect’s division in beats, against its master’s live tempo', () => {
    render(
      <PaletteRunsOn
        family="COLOUR"
        effect={effect({ speedMasterUuid: 'master-2-uuid' })}
        entry={beatEntry}
      />,
    )
    expect(screen.getByText('One bar · 2 beats')).toBeTruthy()
    expect(screen.getByText('M2 · Chases · 128 bpm')).toBeTruthy()
  })

  it('names master 1 rather than losing the clause', () => {
    // `useSpeedMasterDisplay` is silent at master 1 because a *chip* should be. This is a caption.
    render(<PaletteRunsOn family="COLOUR" effect={effect()} entry={beatEntry} />)
    expect(screen.getByText('M1 · 128 bpm')).toBeTruthy()
  })

  it('reads the same number as seconds for a wall-clock effect, and drops the beat ruler', () => {
    // The units trap. A wall-clock effect is not driven by ticks at all, so a 4-beat ruler under it
    // would be drawing a beat grid the effect never consults.
    render(
      <PaletteRunsOn
        family="COLOUR"
        effect={effect()}
        entry={{ ...beatEntry, timingSource: 'WALL_CLOCK' }}
      />,
    )
    expect(screen.getByText('One cycle · 2 s')).toBeTruthy()
    expect(screen.getByText('Seconds — no master')).toBeTruthy()
    expect(screen.queryByText('One bar · 2 beats')).toBeNull()
    expect(screen.queryByText('4')).toBeNull()
  })

  it('counts the heads with a beam effect’s one property', () => {
    const zoomWave: FxLibraryEntry = { ...beatEntry, name: 'Zoom Wave', family: 'beam', outputType: 'SLIDER', compatibleAttributes: ['zoom'] }
    render(<PaletteRunsOn family="BEAM" effect={effect({ fxType: 'Zoom Wave', family: 'beam' })} entry={zoomWave} />)
    expect(screen.getByText('1 head')).toBeTruthy()
    expect(screen.getByText(/Any fixture with zoom\./)).toBeTruthy()
  })
})

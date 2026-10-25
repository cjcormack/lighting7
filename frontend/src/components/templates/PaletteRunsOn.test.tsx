// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueTarget } from '@/api/cuesApi'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { PaletteFx } from '@/api/palettesApi'

/**
 * The effect branch's panel answers two questions a value panel never has to: **how many heads
 * could take this**, and **how fast does it go**.
 *
 * The second is the one with a trap in it. `PaletteFx` carries no `timingSource`, so a
 * `cycle` of 2 is "2 beats" or "2 seconds" depending on the FX library entry — and the two
 * readings are 60× apart, so guessing is worse than saying nothing.
 *
 * Under them the graph strip asks the desk to draw the draft (FX editor plan D15): on the window's
 * selection, or on eight notional heads when there is none.
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
vi.mock('@/store/fixtureFx', () => ({ useFxLibraryQuery: () => ({ data: [] }) }))
const view = vi.hoisted(() => ({ selection: [] as CueTarget[], asks: [] as unknown[], palettes: [] as unknown[] }))
vi.mock('@/store/palettes', () => ({ usePaletteListQuery: () => ({ data: view.palettes }) }))
vi.mock('@/components/fx/selectionFxTarget', () => ({ useWindowSelectionTargets: () => view.selection }))
vi.mock('@/components/fxEditor/useFxPreview', () => ({
  useFxPreview: (ask: unknown) => {
    view.asks.push(ask)
    return { answer: null, error: null }
  },
}))

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
beforeEach(() => {
  view.selection = []
  view.asks = []
  view.palettes = []
})

const lastAsk = () => view.asks.at(-1) as Record<string, unknown> | null

describe('PaletteRunsOn', () => {
  it('counts the heads of the family, not the whole patch', () => {
    render(<PaletteRunsOn projectId={1} family="COLOUR" effect={effect()} entry={beatEntry} />)
    expect(screen.getByText('2 heads')).toBeTruthy()
    expect(screen.getByText(/any fixture with colour/i)).toBeTruthy()
  })

  it('reads a beat effect’s division in beats, against its master’s live tempo', () => {
    render(
      <PaletteRunsOn
        projectId={1}
        family="COLOUR"
        effect={effect({ speedMasterUuid: 'master-2-uuid' })}
        entry={beatEntry}
      />,
    )
    expect(screen.getByText('2 beats')).toBeTruthy()
    expect(screen.getByText('M2 · Chases · 128 bpm')).toBeTruthy()
  })

  it('names master 1 rather than losing the clause', () => {
    // `useSpeedMasterDisplay` is silent at master 1 because a *chip* should be. This is a caption.
    render(<PaletteRunsOn projectId={1} family="COLOUR" effect={effect()} entry={beatEntry} />)
    expect(screen.getByText('M1 · 128 bpm')).toBeTruthy()
  })

  it('reads the same number as seconds for a wall-clock effect', () => {
    // The units trap. A wall-clock effect is not driven by ticks at all.
    render(
      <PaletteRunsOn
        projectId={1}
        family="COLOUR"
        effect={effect()}
        entry={{ ...beatEntry, timingSource: 'WALL_CLOCK' }}
      />,
    )
    expect(screen.getByText('2 s')).toBeTruthy()
    expect(screen.getByText('Seconds — no master')).toBeTruthy()
    expect(screen.queryByText('2 beats')).toBeNull()
  })

  it('counts the heads with a beam effect’s one property', () => {
    const zoomWave: FxLibraryEntry = { ...beatEntry, name: 'Zoom Wave', family: 'beam', outputType: 'SLIDER', compatibleAttributes: ['zoom'] }
    render(<PaletteRunsOn projectId={1} family="BEAM" effect={effect({ fxType: 'Zoom Wave', family: 'beam' })} entry={zoomWave} />)
    expect(screen.getByText('1 head')).toBeTruthy()
    expect(screen.getByText(/Any fixture with zoom\./)).toBeTruthy()
  })

  it('draws the draft on eight notional heads with nothing selected: a draft, include ONLY, the FX alone', () => {
    render(<PaletteRunsOn projectId={1} family="COLOUR" effect={effect({ speedMasterUuid: 'master-2-uuid' })} entry={beatEntry} />)
    expect(lastAsk()).toMatchObject({
      projectId: 1,
      targets: [],
      fxIds: [],
      include: 'ONLY',
      notional: 8,
      drafts: [{ fxType: 'Colour Pulse', attribute: 'rgbColour', cycle: 2, order: 'NORMAL', speedMasterUuid: 'master-2-uuid', programmerOwned: true }],
    })
    expect(document.querySelector('[data-fx-graph-strip]')).toHaveAttribute('data-fx-graph-strip', 'this')
    expect(screen.getByRole('button', { name: 'On the selection' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'On eight heads' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('redraws as the draft changes', () => {
    const { rerender } = render(<PaletteRunsOn projectId={1} family="COLOUR" effect={effect()} entry={beatEntry} />)
    rerender(<PaletteRunsOn projectId={1} family="COLOUR" effect={effect({ cycle: 4, spread: 0.5 })} entry={beatEntry} />)
    expect(lastAsk()).toMatchObject({ drafts: [{ cycle: 4, spread: 0.5 }] })
  })

  it('draws on the selection when there is one, over what is underneath, and switches to eight heads and back', () => {
    view.selection = [{ type: 'group', key: 'front' }]
    render(<PaletteRunsOn projectId={1} family="COLOUR" effect={effect()} entry={beatEntry} />)
    expect(lastAsk()).toMatchObject({ targets: [{ type: 'group', key: 'front' }], include: 'ONLY', notional: undefined })
    expect(document.querySelector('[data-fx-graph-strip]')).toHaveAttribute('data-fx-graph-strip', 'result')
    fireEvent.click(screen.getByRole('button', { name: 'On eight heads' }))
    expect(lastAsk()).toMatchObject({ targets: [], notional: 8 })
    expect(document.querySelector('[data-fx-graph-strip]')).toHaveAttribute('data-fx-graph-strip', 'this')
    fireEvent.click(screen.getByRole('button', { name: 'On the selection' }))
    expect(lastAsk()).toMatchObject({ targets: [{ type: 'group', key: 'front' }], notional: undefined })
  })

  it('names the beam attribute the FX declares, as the desk resolves a palette FX that names none', () => {
    const zoomWave: FxLibraryEntry = { ...beatEntry, name: 'Zoom Wave', family: 'beam', outputType: 'SLIDER', compatibleAttributes: ['zoom'] }
    render(<PaletteRunsOn projectId={1} family="BEAM" effect={effect({ fxType: 'Zoom Wave', family: 'beam' })} entry={zoomWave} />)
    expect(lastAsk()).toMatchObject({ drafts: [{ fxType: 'Zoom Wave', attribute: 'zoom' }] })
  })

  it('draws a Palette Steps palette on the attribute its palettes name, as the desk spawns it', () => {
    const step = (name: string, value: string) => ({
      id: 9,
      uuid: `u-${name}`,
      name,
      family: 'INTENSITY',
      isGeneric: true,
      kind: 'value',
      rows: [{ targetType: 'deferred', targetKey: '', attribute: 'strobe', value }],
    })
    view.palettes = [step('Flash', 'pct:60'), step('Fast', 'pct:100')]
    const steps: FxLibraryEntry = { ...beatEntry, name: 'PaletteSteps', family: 'steps', outputType: 'STEPS', compatibleAttributes: ['dimmer', 'strobe', 'rgbColour'] }
    render(
      <PaletteRunsOn
        projectId={1}
        family="INTENSITY"
        effect={effect({ fxType: 'PaletteSteps', family: 'steps', parameters: { palettes: 'pal:u-Flash,pal:u-Fast' } })}
        entry={steps}
      />,
    )
    expect(lastAsk()).toMatchObject({ drafts: [{ fxType: 'PaletteSteps', attribute: 'strobe' }] })
  })
})

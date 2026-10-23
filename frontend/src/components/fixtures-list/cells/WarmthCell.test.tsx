// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { chan, colourProp, whiteMixProp } from '@/test/fixtureFactories'
import { whiteLevelsFor, whiteLevelsForKelvin, whiteMixColour } from '@/lib/whiteMix'
import type { CellResolution } from '../columns'
import type { CellBatch } from '../rowModel'
import { resetEditorSurfaceMedia } from '../../editor/EditorSurface'

/**
 * The Colour column over heads whose colour is a warm/cold white pair (venue-visit plan D8): the
 * cell opens a warmth control rather than the RGB picker, and commits the colour the chosen mix
 * makes. A batch that mixes them with RGB heads keeps the picker.
 */

vi.mock('sonner', () => ({ toast: { error: vi.fn(), info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({ data: [] }),
  useApplyTemplateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
  useToggleTemplateMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
}))
vi.mock('@/store/selection', () => ({ usePressFamilies: (local: unknown) => local }))
vi.mock('@/store/patches', () => ({ usePatchListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: [], fixtureTypes: [], fixtureByKey: new Map(), typeByKey: new Map() }),
}))
vi.mock('@/components/fixtures/fixtureAppearance', () => ({ FixtureAppearanceSource: () => null }))

import { ColourCell } from './ColourCell'

const MIX = { warmKelvin: 3000, coldKelvin: 6500 }
const cell1 = whiteMixProp(chan(5), chan(6))
const cell2 = whiteMixProp(chan(7), chan(8))
const WARM_COLD: NonNullable<CellResolution>[] = [
  { kind: 'white-mix', property: cell1 },
  { kind: 'white-mix', property: cell2 },
]
const blinderBatch: CellBatch = {
  count: 2,
  skipped: 0,
  resolutions: WARM_COLD,
  targets: [
    { key: 'blinder.cell-1', properties: [cell1], fixtureKey: 'blinder', cellIndex: 0 },
    { key: 'blinder.cell-2', properties: [cell2], fixtureKey: 'blinder', cellIndex: 1 },
  ],
}

function stubGlobals() {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
}

function draw(colour: [number, number, number], batch: CellBatch = blinderBatch) {
  const onCommit = vi.fn()
  const [r, g, b] = colour
  render(
    <ColourCell
      value={{ kind: 'colour', isUniform: true, r, g, b, combinedCss: `rgb(${r}, ${g}, ${b})` }}
      resolutions={[...batch.resolutions]}
      label="Colour"
      batch={batch}
      scopeLabel="Local"
      autoOpen
      keyboardSeed=""
      onCommit={onCommit}
      onBeginEdit={() => {}}
    />,
  )
  return onCommit
}

beforeEach(() => {
  stubGlobals()
  resetEditorSurfaceMedia()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the Colour column over warm/cold white pairs', () => {
  it('reads the cells’ mix as a warmth and a level, and opens a warmth control', async () => {
    const levels = whiteLevelsForKelvin(MIX, 4000, 255)
    draw(whiteMixColour(MIX, levels.warm, levels.cold))
    const warmth = (await screen.findByRole('spinbutton', { name: 'Warmth' })) as HTMLInputElement
    expect(Math.abs(Number(warmth.value) - 4000)).toBeLessThanOrEqual(40)
    expect((screen.getByRole('spinbutton', { name: 'Level' }) as HTMLInputElement).value).toBe('100')
  })

  it('commits the colour the chosen mix makes', async () => {
    const onCommit = draw(whiteMixColour(MIX, 255, 0))
    fireEvent.change(await screen.findByRole('spinbutton', { name: 'Warmth' }), { target: { value: '6500' } })
    const commit = onCommit.mock.calls.at(-1)?.[0]
    expect(commit.kind).toBe('colour')
    // The coldest mix, back within the byte rounding a colour round trip costs (`WhiteMixTest`).
    const levels = whiteLevelsFor(MIX, [commit.r, commit.g, commit.b])
    expect(levels.cold).toBe(255)
    expect(levels.warm).toBeLessThanOrEqual(2)
  })

  it('hands Spread the colour the mix makes, and offers Save', async () => {
    const onSpread = vi.fn()
    const colour = whiteMixColour(MIX, 200, 80)
    render(
      <ColourCell
        value={{ kind: 'colour', isUniform: true, r: colour[0], g: colour[1], b: colour[2], combinedCss: 'x' }}
        resolutions={[...blinderBatch.resolutions]}
        batch={blinderBatch}
        projectId={1}
        onSpread={onSpread}
        autoOpen
        keyboardSeed=""
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(await screen.findByRole('button', { name: /save as palette/i })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: /fan/i }))
    expect(onSpread).toHaveBeenCalledWith({ r: colour[0], g: colour[1], b: colour[2] })
  })

  it('reads a white-mix row as a warmth even when its batch takes the RGB picker', () => {
    const rgb = colourProp('rgbColour', chan(1), chan(2), chan(3))
    const colour = whiteMixColour(MIX, 255, 0)
    render(
      <ColourCell
        value={{ kind: 'colour', isUniform: true, r: colour[0], g: colour[1], b: colour[2], combinedCss: 'x' }}
        resolutions={[WARM_COLD[0]]}
        batch={{ ...blinderBatch, resolutions: [WARM_COLD[0], { kind: 'colour', property: rgb }] }}
        onCommit={() => {}}
        onBeginEdit={() => {}}
      />,
    )
    expect(screen.getByText('3000 K · 100%')).toBeInTheDocument()
  })

  it('keeps the RGB picker when the batch mixes them with an RGB head', async () => {
    const rgb = colourProp('rgbColour', chan(1), chan(2), chan(3))
    const mixed: CellBatch = {
      ...blinderBatch,
      count: 3,
      resolutions: [...WARM_COLD, { kind: 'colour', property: rgb }],
    }
    draw([200, 100, 50], mixed)
    await screen.findByRole('spinbutton', { name: 'R' })
    expect(screen.queryByRole('spinbutton', { name: 'Warmth' })).toBeNull()
  })
})

// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render } from '@testing-library/react'
import type { PaletteSummary } from '@/api/palettesApi'
import type { LookSummary } from '@/api/looksApi'
import type { PressPair } from '@/hooks/usePress'

/**
 * ⌘K's two sub-pages on the programmer (programmer-toolbar plan D13): *Add a Look…* lists every
 * Look, lit where a layer of it is on, and presses it whole; *Apply a palette…* lists the palettes
 * that fit the window's selection — those its mask leaves out dimmed with the reason — and presses
 * one on the window's pair, ⌥ as values. Missing with nothing selected.
 */

const wire = vi.hoisted(() => ({
  pair: { targets: [], families: null } as PressPair,
  fit: { families: null, emitters: null } as { families: string[] | null; emitters: string[] | null },
  into: { kind: 'programmer' } as { kind: string; lookName?: string },
  palettes: [] as PaletteSummary[],
  looks: [] as LookSummary[],
  applied: [] as { source: { kind: string; id: number }; targets: unknown[] }[],
  pressedWith: [] as PressPair[],
  press: vi.fn(),
}))
vi.mock('@/hooks/usePress', () => ({
  useWindowPressPair: () => wire.pair,
  useWindowPaletteFit: () => wire.fit,
  usePress: (_projectId: number, pair: PressPair) => {
    wire.pressedWith.push(pair)
    return { press: (...args: unknown[]) => wire.press(pair, ...args), into: wire.into }
  },
}))
vi.mock('@/store/palettes', () => ({ usePaletteListQuery: () => ({ data: wire.palettes }) }))
vi.mock('@/store/looks', () => ({ useLookListQuery: () => ({ data: wire.looks }) }))
vi.mock('@/store/fixtureFx', () => ({ useFxLibraryQuery: () => ({ data: [] }) }))
vi.mock('@/store/programmer', () => ({ useProgrammerAppliedQuery: () => ({ data: wire.applied }) }))
vi.mock('@/components/press/pressPresence', () => ({
  pressPresence: (_into: unknown, _cue: unknown, _applied: unknown, _targets: unknown, _kind: string, id: number) =>
    id === 1 ? 'all' : 'none',
}))

const { ProgrammerLookDoor, ProgrammerPaletteDoor } = await import('./ProgrammerPressDoors')
const { readProgrammerCommands } = await import('./commandDoors')

afterEach(() => {
  cleanup()
  wire.press.mockClear()
  wire.pair = { targets: [], families: null }
  wire.into = { kind: 'programmer' }
})

const command = (id: string) => readProgrammerCommands().find((c) => c.id === id)

function palette(id: number, name: string, family: PaletteSummary['family'], over: Partial<PaletteSummary> = {}): PaletteSummary {
  return { id, uuid: `p-${id}`, name, family, kind: 'value', isGeneric: true, rows: [], requiredEmitters: [], ...over } as PaletteSummary
}

const SIX: PressPair['targets'] = [1, 2, 3, 4, 5, 6].map((n) => ({ type: 'fixture' as const, key: `hex-${n}` }))

describe('Apply a palette…', () => {
  it('lists the palettes that fit, the mask’s outsiders dimmed with the reason, and presses one', () => {
    wire.pair = { targets: SIX, families: ['COLOUR'] }
    wire.fit = { families: ['COLOUR', 'INTENSITY'], emitters: null }
    wire.palettes = [
      palette(1, 'Amber wash', 'COLOUR', { rows: [{ attribute: 'colour', value: '#f59e0b' }] } as Partial<PaletteSummary>),
      palette(2, 'CT blue', 'COLOUR'),
      palette(3, 'Full', 'INTENSITY'),
      palette(4, 'Centre', 'POSITION'),
    ]
    render(<ProgrammerPaletteDoor projectId={3} />)
    const offer = command('selection.applyPalette')!
    expect(offer).toMatchObject({ label: 'Apply a palette…', hint: '6 heads · Colour', canAct: true })
    const page = offer.page!()
    expect(page.sections.map((s) => [s.heading, s.items.map((i) => i.label)])).toEqual([
      ['Fits the selection', ['Amber wash', 'CT blue']],
      ['Outside the Colour mask', ['Full']],
    ])
    expect(page.sections[0].items[0]).toMatchObject({ hint: 'on', swatch: expect.any(String) })
    expect(page.sections[1].items[0].dimmed).toContain('Intensity')

    page.sections[0].items[1].press(false)
    expect(wire.press).toHaveBeenLastCalledWith(wire.pair, { kind: 'palette', palette: wire.palettes[1] }, 'press')
    page.sections[0].items[1].press(true)
    expect(wire.press).toHaveBeenLastCalledWith(wire.pair, { kind: 'palette', palette: wire.palettes[1] }, 'asValues')
  })

  it('cannot act with nothing selected', () => {
    render(<ProgrammerPaletteDoor projectId={3} />)
    expect(command('selection.applyPalette')?.canAct).toBe(false)
  })

  it('names the focused Look a press lands in', () => {
    wire.pair = { targets: SIX, families: null }
    wire.into = { kind: 'look', lookName: 'Warm wash' }
    render(<ProgrammerPaletteDoor projectId={3} />)
    expect(command('selection.applyPalette')?.hint).toBe('6 heads · into Warm wash')
  })
})

describe('Add a Look…', () => {
  it('lists every Look, lit where a layer of it is on, and presses it whole', () => {
    wire.looks = [
      { id: 7, name: 'Front warm', rowCount: 12, fxCount: 0 } as LookSummary,
      { id: 8, name: 'Ballyhoo', rowCount: 0, fxCount: 2 } as LookSummary,
    ]
    wire.applied = [{ source: { kind: 'LOOK', id: 7 }, targets: [] }]
    render(<ProgrammerLookDoor projectId={3} />)
    const offer = command('programmer.addLook')!
    expect(offer).toMatchObject({ group: 'programmer', hint: 'lands whole', canAct: true })
    const [looks] = offer.page!().sections
    expect(looks.items.map((i) => [i.label, i.hint])).toEqual([
      ['Front warm', '12 values · on'],
      ['Ballyhoo', '2 FX'],
    ])
    looks.items[1].press(false)
    expect(wire.press).toHaveBeenLastCalledWith({ targets: [], families: null }, { kind: 'look', look: wire.looks[1] }, 'press')
    looks.items[0].press(true)
    expect(wire.press).toHaveBeenLastCalledWith({ targets: [], families: null }, { kind: 'look', look: wire.looks[0] }, 'asValues')
  })

  it('cannot act with a Look layer focused, where Looks don’t nest', () => {
    wire.into = { kind: 'look', lookName: 'Warm wash' }
    render(<ProgrammerLookDoor projectId={3} />)
    expect(command('programmer.addLook')?.canAct).toBe(false)
  })
})

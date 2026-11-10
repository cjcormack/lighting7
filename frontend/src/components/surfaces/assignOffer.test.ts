import { describe, expect, it } from 'vitest'
import { fitEntries, initialBinding } from './assignOffer'
import { isWhole } from './BindingForm'
import { CATALOGUE } from './surfaceFixtures'
import type { CatalogueEntry } from '@/store/surfaces'

const STACK = { type: 'record', kind: 'STACK', uuid: 's-1' } as const
const ENTRY = { type: 'record', kind: 'ENTRY', uuid: 'e-1' } as const
const CUE = { type: 'record', kind: 'CUE', uuid: 'c-1' } as const
const PALETTE = { type: 'record', kind: 'PALETTE', uuid: 'p-1' } as const
const GROUP = { type: 'group', key: 'front' } as const
const FIXTURE = { type: 'fixture', key: 'hex-1' } as const

const ids = (fits: ReturnType<typeof fitEntries>) => fits.map((fit) => fit.entry.id)

describe('the offer, filtered by record and part', () => {
  it('offers a stack its playback buttons and its FX amount on a fader, never a fixture function', () => {
    const buttons = ids(fitEntries(CATALOGUE, [STACK], 'BUTTON'))
    expect(buttons).toEqual(expect.arrayContaining(['go', 'back', 'pause', 'stop']))
    expect(buttons).not.toContain('flash')
    expect(buttons).not.toContain('goTo')
    expect(ids(fitEntries(CATALOGUE, [STACK], 'CONTINUOUS'))).toEqual(['fxAmount'])
  })

  it('offers a cue opened from its place both the place\'s functions and the cue\'s', () => {
    const fits = fitEntries(CATALOGUE, [ENTRY, CUE], 'BUTTON')
    expect(ids(fits)).toEqual(expect.arrayContaining(['goTo', 'standby', 'cue']))
    expect(fits.find((fit) => fit.entry.id === 'goTo')?.subject).toEqual(ENTRY)
    expect(fits.find((fit) => fit.entry.id === 'cue')?.subject).toEqual(CUE)
    expect(fitEntries(CATALOGUE, [ENTRY, CUE], 'CONTINUOUS')).toEqual([])
  })

  it('offers a palette its press, on the selection, with the palette filled in', () => {
    const [fit, ...rest] = fitEntries(CATALOGUE, [PALETTE], 'BUTTON')
    expect(rest).toEqual([])
    expect(fit.entry.id).toBe('palette')
    const binding = initialBinding(fit)
    expect(binding).toEqual({ does: { type: 'palette', palette: 'p-1' }, on: { type: 'selection' } })
    expect(isWhole(fit.entry, binding)).toBe(true)
  })

  it('narrows a fixture function to the fixture, and keeps Fire for a fixture only', () => {
    const faders = fitEntries(CATALOGUE, [FIXTURE], 'CONTINUOUS')
    expect(ids(faders)).toContain('level')
    for (const fit of faders) expect(fit.entry.subjects).toEqual(['FIXTURE'])
    expect(ids(fitEntries(CATALOGUE, [FIXTURE], 'BUTTON'))).toContain('fire')
    expect(ids(fitEntries(CATALOGUE, [GROUP], 'BUTTON'))).not.toContain('fire')
    expect(ids(fitEntries(CATALOGUE, [GROUP], 'BUTTON'))).toEqual(expect.arrayContaining(['flash', 'select', 'locate', 'release']))
  })

  it('leaves out a function whose record parameter the target cannot fill', () => {
    const press: CatalogueEntry = {
      ...CATALOGUE.find((e) => e.id === 'palette')!,
      id: 'groupPalette',
      subjects: ['GROUP'],
    }
    expect(fitEntries([press], [GROUP], 'BUTTON')).toEqual([])
    const [fit] = fitEntries([press], [GROUP, PALETTE], 'BUTTON')
    expect(fit.subject).toEqual(GROUP)
    expect(fit.entry.params.find((p) => p.key === 'palette')?.default).toBe('p-1')
  })

  it('starts a stack on GO, bound to the stack', () => {
    const [first] = fitEntries(CATALOGUE, [STACK], 'BUTTON')
    expect(initialBinding(first)).toEqual({ does: { type: 'go' }, on: STACK })
  })
})

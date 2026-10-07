// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StageElementDto } from '@/api/stageElementApi'
import type { LiveScenery, ProgrammerScenery, SceneryChange } from '@/api/sceneryApi'
import type { ProgrammerScope } from './ProgrammerScope'

/**
 * The programmer's scenery list — what the rail's Scenery band opens as *All scenery…*
 * (scenery-programmer plan D1, D4): Venue then Set, by name; the three filters, per window; and
 * the scope's three arms — Local writes the programmer, a focused Look layer writes that Look's
 * scenery PUT, Output draws every row disabled with its reason.
 */
function element(over: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...over,
  }
}

const ELEMENTS = [
  element({ id: 1, uuid: 'sofa', name: 'Sofa' }),
  element({ id: 2, uuid: 'moon', name: 'Moon', positionZ: 3, params: { flies: true, states: { trimM: 7 } } }),
  element({ id: 3, uuid: 'tabs', name: 'House tabs', kind: 'DRAPE', layer: 'VENUE', params: { role: 'TABS', operation: 'DRAW' } }),
  element({ id: 4, uuid: 'wall', name: 'Back wall', kind: 'FLAT', layer: 'VENUE' }),
  element({ id: 5, uuid: 'border', name: 'Border 2', kind: 'DRAPE', layer: 'VENUE', positionZ: 6, params: { role: 'BORDER', operation: 'FLY', states: { trimM: 9 } } }),
]

const store = {
  live: { projectId: 6, entries: {} } as LiveScenery,
  programmer: { projectId: 6, elements: [] } as ProgrammerScenery,
  blind: false,
  scope: { kind: 'local' } as ProgrammerScope | null,
  layers: [] as { layerId: number; source: { kind: 'LOOK' | 'TEMPLATE'; id: number; uuid: string; name: string } }[],
  lookScenery: [] as SceneryChange[],
}
const programmerSetScenery = vi.fn()
const programmerClearScenery = vi.fn()
const setLookScenery = vi.fn((_: unknown) => ({ unwrap: () => Promise.resolve([]) }))

vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: ELEMENTS }) }))
vi.mock('@/store/scenery', () => ({
  useLiveScenery: () => store.live,
  useSetLookSceneryMutation: () => [setLookScenery],
}))
vi.mock('@/store/programmer', () => ({
  programmerSetScenery: (...a: unknown[]) => programmerSetScenery(...a),
  programmerClearScenery: (...a: unknown[]) => programmerClearScenery(...a),
  useProgrammerLayersQuery: () => ({ data: store.layers }),
  useProgrammerScenery: () => store.programmer,
}))
vi.mock('@/store/looks', () => ({
  useLookQuery: (_: unknown, { skip }: { skip: boolean }) => ({ currentData: skip ? undefined : { scenery: store.lookScenery } }),
}))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => store.blind }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => store.scope }))

import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import { DEFAULT_SCENERY_FILTERS, ProgrammerSceneryList, SCENERY_FILTERS_KEY, sceneryGroups } from './ProgrammerSceneryList'

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  window.sessionStorage.clear()
  window.localStorage.clear()
  resetProgrammerFadeStore()
  store.live = { projectId: 6, entries: {} }
  store.programmer = { projectId: 6, elements: [] }
  store.blind = false
  store.scope = { kind: 'local' }
  store.layers = []
  store.lookScenery = []
})

const draw = () => render(<ProgrammerSceneryList projectId={6} />)
const row = (uuid: string) => document.querySelector(`[data-scenery-element="${uuid}"]`) as HTMLElement | null
const names = () => [...document.querySelectorAll('[data-scenery-element]')].map((r) => r.getAttribute('data-scenery-element'))
const filter = (name: string) => within(screen.getByRole('group', { name: 'Scenery filters' })).getByRole('button', { name })

describe('grouping and filters', () => {
  it('groups Venue then Set, by name within each', () => {
    draw()
    expect([...document.querySelectorAll('[data-scenery-group]')].map((g) => g.getAttribute('data-scenery-group'))).toEqual(['VENUE', 'SET'])
    expect(names()).toEqual(['wall', 'border', 'tabs', 'moon', 'sofa'])
  })

  it('filters by Set, Venue and Moving only, stored per window', () => {
    draw()
    fireEvent.click(filter('Moving only'))
    expect(names()).toEqual(['border', 'tabs', 'moon'])
    fireEvent.click(filter('Set'))
    expect(names()).toEqual(['border', 'tabs'])
    expect(JSON.parse(window.sessionStorage.getItem(SCENERY_FILTERS_KEY)!)).toEqual({ set: false, venue: true, movingOnly: true })
    // A second mount in this window reads them back.
    cleanup()
    draw()
    expect(names()).toEqual(['border', 'tabs'])
    expect(filter('Set').getAttribute('aria-pressed')).toBe('false')
  })

  it('lists a held piece whatever the filters say, so its release stays in reach', () => {
    expect(sceneryGroups(ELEMENTS, { set: false, venue: false, movingOnly: true }, new Set(['sofa']))).toEqual([
      { layer: 'SET', elements: [ELEMENTS[0]] },
    ])
  })

  it('falls back to the defaults when storage refuses', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    draw()
    expect(names()).toHaveLength(5)
    fireEvent.click(filter('Venue'))
    expect(names()).toEqual(['moon', 'sofa'])
    vi.restoreAllMocks()
    expect(DEFAULT_SCENERY_FILTERS).toEqual({ set: true, venue: true, movingOnly: false })
  })
})

describe('the Local arm', () => {
  it('writes the programmer at its fade, rings what it holds, and releases it', () => {
    setProgrammerFade('2000')
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }] }
    store.live = {
      projectId: 6,
      entries: {
        moon: { elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 7 }, startedAtMs: 0, durationMs: 0, source: { kind: 'programmer' } },
        tabs: { elementUuid: 'tabs', state: { open: 0 }, from: { open: 0 }, startedAtMs: 0, durationMs: 0, source: { kind: 'cue', cueId: 14, label: '14' } },
      },
    }
    draw()
    expect(screen.getByText('1 held · Local')).toBeTruthy()
    const moon = row('moon')!
    expect(moon.getAttribute('data-held')).toBe('true')
    expect(moon.className).toContain('ring-primary')
    expect(within(moon).getByRole('radio', { name: 'In' }).getAttribute('data-state')).toBe('on')
    expect(within(moon).getByText('held by the programmer')).toBeTruthy()
    // A row that only inherits wears the tracked hatch and says where it comes from.
    expect(row('tabs')!.className).toContain('border-dashed')
    expect(within(row('tabs')!).getByText('tracked from Q14')).toBeTruthy()
    expect(within(row('sofa')!).getByText('at its base')).toBeTruthy()
    expect(within(row('tabs')!).queryByRole('button', { name: /Release/ })).toBeNull()

    fireEvent.click(within(row('tabs')!).getByRole('radio', { name: 'Drawn' }))
    expect(programmerSetScenery).toHaveBeenCalledWith('tabs', { open: 1 }, 2000)

    fireEvent.click(within(moon).getByRole('button', { name: 'Release Moon' }))
    expect(programmerClearScenery).toHaveBeenCalledWith('moon', 2000)
    expect(setLookScenery).not.toHaveBeenCalled()
  })

  it('marks a row Blind is staging, and shows the staged state', () => {
    store.blind = true
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'tabs', state: { open: 1 } }] }
    store.live = {
      projectId: 6,
      entries: { tabs: { elementUuid: 'tabs', state: { open: 0 }, from: { open: 0 }, startedAtMs: 0, durationMs: 0, source: { kind: 'base' } } },
      staged: { tabs: { elementUuid: 'tabs', state: { open: 1 }, from: { open: 0 }, startedAtMs: 0, durationMs: 4000 } },
    }
    draw()
    const tabs = row('tabs')!
    expect(tabs.getAttribute('data-staged')).toBe('true')
    expect(tabs.querySelector('[data-busk-blind-dot]')).not.toBeNull()
    expect(within(tabs).getByRole('radio', { name: 'Drawn' }).getAttribute('data-state')).toBe('on')
    expect(within(tabs).getByText('staged · at its base')).toBeTruthy()
    expect(row('moon')!.querySelector('[data-busk-blind-dot]')).toBeNull()
  })
})

describe('the focused Look layer arm', () => {
  it('writes that Look’s whole scenery list through its own PUT, never the programmer', async () => {
    store.scope = { kind: 'layer', layerId: 3 }
    store.layers = [{ layerId: 3, source: { kind: 'LOOK', id: 9, uuid: 'u9', name: 'Night' } }]
    store.lookScenery = [
      { uuid: 'c1', elementUuid: 'sofa', elementName: 'Sofa', elementKind: 'OBJECT', state: { visible: false }, sortOrder: 0 },
    ]
    draw()
    expect(screen.getByText('Night')).toBeTruthy()
    const sofa = row('sofa')!
    expect(sofa.getAttribute('data-held')).toBe('true')
    expect(within(sofa).getByRole('radio', { name: 'Hidden' }).getAttribute('data-state')).toBe('on')
    expect(within(sofa).getByText('in Night')).toBeTruthy()

    fireEvent.click(within(row('tabs')!).getByRole('radio', { name: 'Drawn' }))
    expect(setLookScenery).toHaveBeenCalledWith({
      projectId: 6,
      lookId: 9,
      scenery: [{ elementUuid: 'sofa', state: { visible: false } }, { elementUuid: 'tabs', state: { open: 1 } }],
    })
    expect(programmerSetScenery).not.toHaveBeenCalled()
    await act(async () => {
      await Promise.resolve()
    })

    fireEvent.click(within(sofa).getByRole('button', { name: 'Release Sofa' }))
    expect(setLookScenery).toHaveBeenLastCalledWith({ projectId: 6, lookId: 9, scenery: [{ elementUuid: 'tabs', state: { open: 1 } }] })
    expect(programmerClearScenery).not.toHaveBeenCalled()
  })
})

describe('the read-only arms', () => {
  it('Output draws every row, disabled, with its reason and its source', () => {
    store.scope = { kind: 'output' }
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }] }
    store.live = {
      projectId: 6,
      entries: { moon: { elementUuid: 'moon', state: { trimM: 3 }, from: { trimM: 3 }, startedAtMs: 0, durationMs: 0, source: { kind: 'programmer' } } },
    }
    draw()
    expect(screen.getAllByText('Output is read-only').length).toBeGreaterThan(0)
    expect(names()).toHaveLength(5)
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(within(row('moon')!).getByText('held by the programmer')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Release/ })).toBeNull()
    fireEvent.click(within(row('tabs')!).getByRole('radio', { name: 'Drawn' }))
    expect(programmerSetScenery).not.toHaveBeenCalled()
  })

  it('a focused template layer is read-only: a template carries no scenery', () => {
    store.scope = { kind: 'layer', layerId: 4 }
    store.layers = [{ layerId: 4, source: { kind: 'TEMPLATE', id: 2, uuid: 'u2', name: 'Warm' } }]
    draw()
    expect(screen.getAllByText('A template carries no scenery').length).toBeGreaterThan(0)
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(setLookScenery).not.toHaveBeenCalled()
  })
})

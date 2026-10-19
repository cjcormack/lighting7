// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, renderHook, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StageElementDto } from '@/api/stageElementApi'
import type { LiveScenery, ProgrammerScenery, SceneryChange } from '@/api/sceneryApi'
import type { ProgrammerScope } from './ProgrammerScope'

/**
 * The programmer's scenery scope and its rows (scenery-programmer plan D1, D4), which the Scenery
 * table and the Stage view's popover share: Venue then Set, by name; the three filters, per window;
 * and the scope's three arms — Local writes the programmer, a focused Look layer writes that Look's
 * scenery PUT, Output draws every row disabled with its reason. The table's own chips and columns
 * are `AppliedTables.test.tsx`'s.
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
  lookSceneryById: {} as Record<number, SceneryChange[]>,
  lookLoaded: true,
}
const programmerSetScenery = vi.fn()
const programmerClearScenery = vi.fn()
const setLookScenery = vi.fn((_: unknown): { unwrap: () => Promise<unknown> } => ({ unwrap: () => Promise.resolve([]) }))

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
// One detail object per Look and list, as RTK Query's cache hands back the same `currentData`
// until a refetch replaces it.
const details = new Map<unknown, { scenery: SceneryChange[] }>()
vi.mock('@/store/looks', () => ({
  useLookQuery: ({ lookId }: { lookId: number }, { skip }: { skip: boolean }) => {
    if (skip || !store.lookLoaded) return { currentData: undefined }
    const scenery = store.lookSceneryById[lookId] ?? store.lookScenery
    if (!details.has(scenery)) details.set(scenery, { scenery })
    return { currentData: details.get(scenery) }
  },
}))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => store.blind }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => store.scope }))

import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import {
  DEFAULT_SCENERY_FILTERS,
  SCENERY_FILTERS_KEY,
  SceneryElementRow,
  resetSceneryFiltersStore,
  sceneryGroups,
  useHeldSceneryCount,
  useSceneryFilters,
  useSceneryScope,
} from './ProgrammerSceneryList'

/** Every element's row under one scope reading, Venue then Set, as the Scenery table lists them. */
function SceneryRows() {
  const scope = useSceneryScope(6)
  const [filters] = useSceneryFilters()
  const groups = sceneryGroups(ELEMENTS, filters, new Set(scope.held.keys()))
  return (
    <>
      <span data-testid="subject">{scope.subject}</span>
      {groups.map((group) => (
        <section key={group.layer} data-scenery-group={group.layer}>
          {group.elements.map((el) => (
            <SceneryElementRow key={el.uuid} element={el} scope={scope} />
          ))}
        </section>
      ))}
    </>
  )
}

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
  resetSceneryFiltersStore()
  resetProgrammerFadeStore()
  store.live = { projectId: 6, entries: {} }
  store.programmer = { projectId: 6, elements: [] }
  store.blind = false
  store.scope = { kind: 'local' }
  store.layers = []
  store.lookScenery = []
  store.lookSceneryById = {}
  store.lookLoaded = true
  details.clear()
  setLookScenery.mockImplementation(() => ({ unwrap: () => Promise.resolve([]) }))
})

const draw = () => render(<SceneryRows />)
const row = (uuid: string) => document.querySelector(`[data-scenery-element="${uuid}"]`) as HTMLElement | null
const names = () => [...document.querySelectorAll('[data-scenery-element]')].map((r) => r.getAttribute('data-scenery-element'))

describe('grouping and filters', () => {
  it('groups Venue then Set, by name within each', () => {
    draw()
    expect([...document.querySelectorAll('[data-scenery-group]')].map((g) => g.getAttribute('data-scenery-group'))).toEqual(['VENUE', 'SET'])
    expect(names()).toEqual(['wall', 'border', 'tabs', 'moon', 'sofa'])
  })

  it('filters by Set, Venue and Moving only, stored per window', () => {
    expect(sceneryGroups(ELEMENTS, { set: true, venue: true, movingOnly: true }, new Set()).flatMap((g) => g.elements.map((e) => e.uuid))).toEqual([
      'border',
      'tabs',
      'moon',
    ])
    const { result } = renderHook(() => useSceneryFilters())
    act(() => result.current[1]({ set: false, venue: true, movingOnly: true }))
    expect(JSON.parse(window.sessionStorage.getItem(SCENERY_FILTERS_KEY)!)).toEqual({ set: false, venue: true, movingOnly: true })
    // A second mount in this window reads them back.
    cleanup()
    draw()
    expect(names()).toEqual(['border', 'tabs'])
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
    const { result } = renderHook(() => useSceneryFilters())
    act(() => result.current[1]({ ...DEFAULT_SCENERY_FILTERS, venue: false }))
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
    expect(screen.getByTestId('subject')).toHaveTextContent('1 held · Local')
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
    expect(screen.getByTestId('subject')).toHaveTextContent('Night')
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

describe('a focused Look’s whole-list writes', () => {
  const lookChange = (elementUuid: string, state: SceneryChange['state'], sortOrder = 0): SceneryChange => ({
    uuid: `c-${elementUuid}`, elementUuid, elementName: elementUuid, elementKind: 'OBJECT', state, sortOrder,
  })
  const focusLook = (layerId: number, id: number, name: string) => {
    store.scope = { kind: 'layer', layerId }
    store.layers = [{ layerId, source: { kind: 'LOOK', id, uuid: `u${id}`, name } }]
  }
  /** A save that settles when the test says. */
  const deferredSave = () => {
    let settle: { resolve: () => void; reject: (e: unknown) => void } = { resolve: () => {}, reject: () => {} }
    setLookScenery.mockImplementationOnce(() => ({
      unwrap: () => new Promise((resolve, reject) => { settle = { resolve: () => resolve([]), reject } }),
    }))
    return () => settle
  }
  const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve() })
  const held = () => [...document.querySelectorAll('[data-scenery-element][data-held="true"]')].map((r) => r.getAttribute('data-scenery-element'))

  it('writes nothing to a focused Look until its own list has loaded, which a whole-list PUT would replace', () => {
    focusLook(3, 9, 'Night')
    store.lookLoaded = false
    draw()
    const inRadio = within(row('moon')!).getByRole('radio', { name: 'In' })
    expect(inRadio).toBeDisabled()
    fireEvent.click(inRadio)
    expect(setLookScenery).not.toHaveBeenCalled()
  })

  it('a focus moved to another Look mid-save adopts that Look’s list, and writes to it', () => {
    focusLook(3, 9, 'Night')
    store.lookSceneryById = { 9: [lookChange('sofa', { visible: false })], 10: [lookChange('moon', { trimM: 3 })] }
    deferredSave()
    const { rerender } = draw()
    fireEvent.click(within(row('sofa')!).getByRole('button', { name: 'Release Sofa' }))
    focusLook(4, 10, 'Day')
    rerender(<SceneryRows />)
    expect(held()).toEqual(['moon'])
    expect(within(row('moon')!).getByText('in Day')).toBeTruthy()
    fireEvent.click(within(row('moon')!).getByRole('button', { name: 'Release Moon' }))
    expect(setLookScenery).toHaveBeenLastCalledWith({ projectId: 6, lookId: 10, scenery: [] })
  })

  it('a refused save goes back to the last list the desk accepted, not to the list as the gesture began', async () => {
    focusLook(3, 9, 'Night')
    store.lookScenery = [lookChange('sofa', { visible: false })]
    draw()
    // The first save lands: the sofa is out of the Look.
    fireEvent.click(within(row('sofa')!).getByRole('button', { name: 'Release Sofa' }))
    await flush()
    expect(held()).toEqual([])
    // The second is refused before the first one's refetch arrives.
    const second = deferredSave()
    fireEvent.click(within(row('moon')!).getByRole('radio', { name: 'In' }))
    expect(held()).toEqual(['moon'])
    second().reject({ status: 400, data: { error: 'no' } })
    await flush()
    // Not the sofa back: the desk holds what the first save sent.
    expect(held()).toEqual([])
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
    expect(row('moon')!.title).toBe('Output is read-only')
    expect(names()).toHaveLength(5)
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(within(row('moon')!).getByText('held by the programmer')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Release/ })).toBeNull()
    fireEvent.click(within(row('tabs')!).getByRole('radio', { name: 'Drawn' }))
    expect(programmerSetScenery).not.toHaveBeenCalled()
  })

  it('a focused palette layer is read-only: a palette carries no scenery', () => {
    store.scope = { kind: 'layer', layerId: 4 }
    store.layers = [{ layerId: 4, source: { kind: 'TEMPLATE', id: 2, uuid: 'u2', name: 'Warm' } }]
    draw()
    expect(row('sofa')!.title).toBe('A palette carries no scenery')
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(setLookScenery).not.toHaveBeenCalled()
  })
})

describe('useHeldSceneryCount', () => {
  it('counts the programmer’s held pieces for its project by itself — Clear’s tooltip and the Scenery tab’s count, with no band mounted', () => {
    store.programmer = {
      projectId: 6,
      elements: [
        { elementUuid: 'moon', state: { trimM: 5 } },
        { elementUuid: 'tabs', state: { open: 0 } },
      ],
    }
    const { result, rerender } = renderHook(() => useHeldSceneryCount(6))
    expect(result.current).toBe(2)
    // Another project's frame, a moment before a switch lands, holds nothing here.
    store.programmer = { projectId: 9, elements: [{ elementUuid: 'moon', state: { trimM: 5 } }] }
    rerender()
    expect(result.current).toBe(0)
  })
})

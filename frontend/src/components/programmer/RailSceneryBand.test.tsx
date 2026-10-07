// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StageElementDto } from '@/api/stageElementApi'
import type { LiveScenery, ProgrammerScenery, SceneryChange } from '@/api/sceneryApi'
import type { ProgrammerScope } from './ProgrammerScope'

/**
 * The rail's Scenery band (scenery-programmer plan D1, D4; session 2): the pieces the scope holds,
 * each with its control and a release ×, and *All scenery…*, which opens every element. A band of
 * the body, not a tab, so it is in every arm — what that buys is `ProgrammerRail.test.tsx`'s; this
 * is what the band itself draws, per scope arm.
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
]

const store = {
  elements: ELEMENTS as StageElementDto[],
  live: { projectId: 6, entries: {} } as LiveScenery,
  programmer: { projectId: 6, elements: [] } as ProgrammerScenery,
  scope: { kind: 'local' } as ProgrammerScope | null,
  layers: [] as { layerId: number; source: { kind: 'LOOK' | 'TEMPLATE'; id: number; uuid: string; name: string } }[],
  lookScenery: [] as SceneryChange[],
  lookSceneryById: {} as Record<number, SceneryChange[]>,
  lookLoaded: true,
}
const programmerSetScenery = vi.fn()
const programmerClearScenery = vi.fn()
const setLookScenery = vi.fn((_: unknown): { unwrap: () => Promise<unknown> } => ({ unwrap: () => Promise.resolve([]) }))

vi.mock('@/store/stageElements', () => ({ useStageElementListQuery: () => ({ data: store.elements }) }))
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
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => false }))
vi.mock('./ProgrammerScope', () => ({ useProgrammerScope: () => store.scope }))

import { resetProgrammerFadeStore, setProgrammerFade } from '@/lib/programmerFade'
import { RailSceneryBand } from './RailSceneryBand'

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
  // The fade picker persists; a fade set by one test must not move the next one's piece.
  window.localStorage.clear()
  resetProgrammerFadeStore()
  store.elements = ELEMENTS
  store.live = { projectId: 6, entries: {} }
  store.programmer = { projectId: 6, elements: [] }
  store.scope = { kind: 'local' }
  store.layers = []
  store.lookScenery = []
  store.lookSceneryById = {}
  store.lookLoaded = true
  details.clear()
  setLookScenery.mockImplementation(() => ({ unwrap: () => Promise.resolve([]) }))
})

const draw = () => render(<RailSceneryBand projectId={6} />)
const band = () => screen.getByRole('region', { name: 'Scenery' })
const rows = () => [...band().querySelectorAll('[data-scenery-element]')].map((r) => r.getAttribute('data-scenery-element'))

describe('the Local arm', () => {
  it('lists only what the programmer holds, Venue then Set, and releases it on its ×', () => {
    setProgrammerFade('1000')
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }, { elementUuid: 'tabs', state: { open: 1 } }] }
    draw()
    expect(rows()).toEqual(['tabs', 'moon'])
    expect(within(band()).getByText('2 held · top wins')).toBeTruthy()
    for (const uuid of ['tabs', 'moon']) expect(band().querySelector(`[data-scenery-element="${uuid}"]`)!.className).toContain('ring-primary')
    fireEvent.click(within(band()).getByRole('button', { name: 'Release Moon' }))
    expect(programmerClearScenery).toHaveBeenCalledWith('moon', 1000)
    fireEvent.click(within(band()).getByRole('radio', { name: 'Half' }))
    expect(programmerSetScenery).toHaveBeenCalledWith('tabs', { open: 0.5 }, 1000)
  })

  it('says nothing is held, and *All scenery…* opens every element through EditorSurface', () => {
    draw()
    expect(rows()).toEqual([])
    expect(within(band()).getByText('Nothing held.')).toBeTruthy()
    fireEvent.click(within(band()).getByRole('button', { name: 'All scenery…' }))
    const surface = document.querySelector('[data-cell-editor-surface]') as HTMLElement
    expect(surface.getAttribute('data-cell-editor-surface')).toBe('popover')
    expect([...surface.querySelectorAll('[data-scenery-element]')].map((r) => r.getAttribute('data-scenery-element'))).toEqual(['tabs', 'moon', 'sofa'])
    // A gesture there writes the programmer like the band's own rows.
    fireEvent.click(within(surface).getAllByRole('radio', { name: 'Hidden' })[2])
    expect(programmerSetScenery).toHaveBeenCalledWith('sofa', { visible: false }, 0)
  })

  it('disables *All scenery…* while the stage has no scenery, and says where to place some', () => {
    store.elements = []
    draw()
    const all = within(band()).getByRole('button', { name: 'All scenery…' })
    expect(all).toBeDisabled()
    expect(all.getAttribute('title')).toMatch(/place some from the Stage view/)
  })
})

describe('the scope arms', () => {
  it('a focused Look layer lists that Look’s own scenery and writes its whole list', () => {
    store.scope = { kind: 'layer', layerId: 3 }
    store.layers = [{ layerId: 3, source: { kind: 'LOOK', id: 9, uuid: 'u9', name: 'Night' } }]
    store.lookScenery = [{ uuid: 'c1', elementUuid: 'sofa', elementName: 'Sofa', elementKind: 'OBJECT', state: { visible: false }, sortOrder: 0 }]
    // The programmer's own holds are not this arm's.
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }] }
    draw()
    // The label names the Look, and so does the held row's read-out.
    expect(within(band()).getAllByText('in Night')).toHaveLength(2)
    expect(rows()).toEqual(['sofa'])
    fireEvent.click(within(band()).getByRole('button', { name: 'Release Sofa' }))
    expect(setLookScenery).toHaveBeenCalledWith({ projectId: 6, lookId: 9, scenery: [] })
    expect(programmerClearScenery).not.toHaveBeenCalled()
  })

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

  it('the band and *All scenery…* edit one Look draft, so neither PUT drops the other’s change', () => {
    focusLook(3, 9, 'Night')
    store.lookScenery = [lookChange('sofa', { visible: false })]
    draw()
    fireEvent.click(within(band()).getByRole('button', { name: 'All scenery…' }))
    const surface = document.querySelector('[data-cell-editor-surface]') as HTMLElement
    fireEvent.click(within(surface.querySelector('[data-scenery-element="moon"]') as HTMLElement).getByRole('radio', { name: 'In' }))
    expect(setLookScenery).toHaveBeenLastCalledWith({
      projectId: 6, lookId: 9, scenery: [{ elementUuid: 'sofa', state: { visible: false } }, { elementUuid: 'moon', state: { trimM: 3 } }],
    })
    fireEvent.click(within(band()).getByRole('button', { name: 'Release Sofa' }))
    expect(setLookScenery).toHaveBeenLastCalledWith({ projectId: 6, lookId: 9, scenery: [{ elementUuid: 'moon', state: { trimM: 3 } }] })
  })

  it('writes nothing to a focused Look until its own list has loaded, which a whole-list PUT would replace', () => {
    focusLook(3, 9, 'Night')
    store.lookLoaded = false
    draw()
    fireEvent.click(within(band()).getByRole('button', { name: 'All scenery…' }))
    const surface = document.querySelector('[data-cell-editor-surface]') as HTMLElement
    const moon = within(surface.querySelector('[data-scenery-element="moon"]') as HTMLElement)
    expect(moon.getByRole('radio', { name: 'In' })).toBeDisabled()
    fireEvent.click(moon.getByRole('radio', { name: 'In' }))
    expect(setLookScenery).not.toHaveBeenCalled()
  })

  it('a focus moved to another Look mid-save adopts that Look’s list, and writes to it', () => {
    focusLook(3, 9, 'Night')
    store.lookSceneryById = { 9: [lookChange('sofa', { visible: false })], 10: [lookChange('moon', { trimM: 3 })] }
    deferredSave()
    const { rerender } = draw()
    fireEvent.click(within(band()).getByRole('button', { name: 'Release Sofa' }))
    focusLook(4, 10, 'Day')
    // A new ref re-renders the memoised band without remounting it, as a store update would.
    rerender(<RailSceneryBand projectId={6} ref={() => {}} />)
    expect(rows()).toEqual(['moon'])
    expect(within(band()).getAllByText('in Day').length).toBeGreaterThan(0)
    fireEvent.click(within(band()).getByRole('button', { name: 'Release Moon' }))
    expect(setLookScenery).toHaveBeenLastCalledWith({ projectId: 6, lookId: 10, scenery: [] })
  })

  it('a refused save goes back to the last list the desk accepted, not to the list as the gesture began', async () => {
    focusLook(3, 9, 'Night')
    store.lookScenery = [lookChange('sofa', { visible: false })]
    draw()
    // The first save lands: the sofa is out of the Look.
    fireEvent.click(within(band()).getByRole('button', { name: 'Release Sofa' }))
    await flush()
    expect(rows()).toEqual([])
    // The second is refused before the first one's refetch arrives.
    const second = deferredSave()
    fireEvent.click(within(band()).getByRole('button', { name: 'All scenery…' }))
    const surface = document.querySelector('[data-cell-editor-surface]') as HTMLElement
    fireEvent.click(within(surface.querySelector('[data-scenery-element="moon"]') as HTMLElement).getByRole('radio', { name: 'In' }))
    expect(rows()).toEqual(['moon'])
    second().reject({ status: 400, data: { error: 'no' } })
    await flush()
    // Not the sofa back: the desk holds what the first save sent.
    expect(rows()).toEqual([])
  })

  it('Output lists what the programmer holds, disabled, with the reason', () => {
    store.scope = { kind: 'output' }
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'moon', state: { trimM: 3 } }] }
    draw()
    expect(rows()).toEqual(['moon'])
    expect(within(band()).getByText('Output is read-only')).toBeTruthy()
    for (const radio of within(band()).getAllByRole('radio')) expect(radio).toBeDisabled()
    expect(within(band()).queryByRole('button', { name: /Release/ })).toBeNull()
  })

  it('a focused template layer is read-only: a template carries no scenery', () => {
    store.scope = { kind: 'layer', layerId: 4 }
    store.layers = [{ layerId: 4, source: { kind: 'TEMPLATE', id: 2, uuid: 'u2', name: 'Warm' } }]
    store.programmer = { projectId: 6, elements: [{ elementUuid: 'tabs', state: { open: 1 } }] }
    draw()
    expect(within(band()).getByText('A template carries no scenery')).toBeTruthy()
    for (const radio of within(band()).getAllByRole('radio')) expect(radio).toBeDisabled()
  })
})

// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enablePatches, produceWithPatches } from 'immer'
import type { FixturePatch } from '@/api/patchApi'
import libraryJson from '../../../../src/main/resources/lanterns/library.json'
import { indexLanterns, type Lantern } from '@/lib/lanterns'
import type { Fixture, FixtureTypeInfo } from '@/store/fixtures'
import { revolutionShutterProps } from '@/test/fixtureFactories'

/**
 * The Stage view's Focus tab (stage-view plan session 7): a change lands in the patch list's cache
 * at once — so the stage redraws the cut as the blade moves — and is saved with one PUT after a
 * pause, carrying the fixture's own focus or its placements, whichever moved; a pending save still
 * lands when the panel goes. A DMX fixture has no focus data and says which of its channels drive
 * its optics instead.
 */
const updatePatch = vi.fn((_body: Record<string, unknown>) => ({ unwrap: (): Promise<void> => Promise.resolve() }))
const dispatch = vi.fn()
type Recipe = (list: FixturePatch[]) => void
vi.mock('@/store/patches', () => ({
  useUpdatePatchMutation: () => [updatePatch],
  patchesApi: {
    util: {
      updateQueryData: (endpoint: string, arg: unknown, recipe: Recipe) => ({ type: 'patch/update', endpoint, arg, recipe }),
    },
  },
}))
vi.mock('react-redux', () => ({ useDispatch: () => dispatch }))
const focusHere = vi.fn((_body: Record<string, unknown>) => ({
  unwrap: (): Promise<unknown> =>
    Promise.resolve({ written: [{ target: { type: 'fixture', key: 'rev-1' }, value: '246', distanceM: 24.0 }] }),
}))
vi.mock('@/store/programmerOps', () => ({ useFocusHereMutation: () => [focusHere, { isLoading: false }] }))
// The served gel library (`GET /gels`), read from the resource the desk serves it from.
vi.mock('@/hooks/useGelIndex', async () => {
  const { indexGels } = await import('@/lib/gels')
  const gels = (await import('../../../../src/main/resources/gels.json')).default
  return { useGelIndex: () => indexGels(gels) }
})

import { StageFocusPanel } from './StageFocusPanel'
import { forgetLanding, recordLanding } from './landedPoints'

/** The Stage canvas's beam director, as `landedPoints.ts` keys its reports. */
const REPORTER = {}

const lanterns = indexLanterns(libraryJson as Lantern[])
const DIMMER_TYPE = { typeKey: 'generic-dimmer', kind: 'GENERIC', acceptsLantern: true } as unknown as FixtureTypeInfo

function patch(over: Partial<FixturePatch> = {}): FixturePatch {
  return {
    id: 7,
    key: 'fos-1',
    displayName: 'FOS 1',
    fixtureTypeKey: 'generic-dimmer',
    startChannel: 1,
    channelCount: 1,
    manufacturer: 'Generic',
    model: 'Dimmer',
    modeName: null,
    universe: 1,
    subnet: 0,
    sortOrder: 1,
    groups: [],
    stageX: null,
    stageY: null,
    stageZ: null,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: 'PROFILE',
    stageHidden: false,
    lanternType: 's4-19',
    ...over,
  } as FixturePatch
}

/** Apply every cache recipe dispatched so far to a copy of [list]. */
function cacheAfter(list: FixturePatch[]): FixturePatch[] {
  const copy = structuredClone(list)
  for (const [action] of dispatch.mock.calls) {
    if ((action as { type: string }).type === 'patch/update') (action as { recipe: Recipe }).recipe(copy)
  }
  return copy
}

beforeEach(() => {
  vi.useFakeTimers()
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
  vi.useRealTimers()
  vi.unstubAllGlobals()
  updatePatch.mockClear()
  dispatch.mockClear()
  focusHere.mockClear()
  forgetLanding(REPORTER, 'rev-1')
})

describe('StageFocusPanel', () => {
  it('lands a blade in the cache at once and saves only what moved, after a pause', () => {
    const p = patch()
    render(<StageFocusPanel projectId={1} patch={p} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />)
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Top shutters depth' }), { key: 'ArrowRight' })
    expect(cacheAfter([p])[0].shutters?.[0]).toEqual({ depth: 0.01, angleDeg: 0 })
    expect(updatePatch).not.toHaveBeenCalled()
    act(() => {
      vi.advanceTimersByTime(400)
    })
    expect(updatePatch).toHaveBeenCalledTimes(1)
    const body = updatePatch.mock.calls[0][0]
    expect(body).toEqual({
      projectId: 1,
      patchId: 7,
      shutters: [
        { depth: 0.01, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
        { depth: 0, angleDeg: 0 },
      ],
    })
    // Not the lantern, which the tab did not move — a lantern id this desk's library lacks would
    // otherwise be refused on every edit.
    expect(body).not.toHaveProperty('lanternType')
    expect(body).not.toHaveProperty('extraPlacements')
  })

  it('paints nothing over a cache that already holds the draft, cloned — no paint loop', () => {
    // RTK lands a cache update by applying Immer patches, which clones the shutters array into the
    // cache. A paint that assigned the draft's own array would then change the cache every time,
    // and the effect that repaints on every new `patch` would loop until React gave up.
    enablePatches()
    const p = patch()
    render(<StageFocusPanel projectId={1} patch={p} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />)
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Right shutters depth' }), { key: 'ArrowRight' })
    const painted = cacheAfter([p])
    const recipes = dispatch.mock.calls
      .map(([action]) => action as { type: string; recipe?: Recipe })
      .filter((a) => a.type === 'patch/update')
    expect(recipes.length).toBeGreaterThan(0)
    const [, patches] = produceWithPatches(structuredClone(painted), (draft) => {
      recipes.at(-1)!.recipe!(draft as FixturePatch[])
    })
    expect(patches).toEqual([])
  })

  it('keeps an edit the desk has not confirmed when a read of the patch list lands first', async () => {
    const p = patch()
    const { rerender } = render(
      <StageFocusPanel projectId={1} patch={p} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />,
    )
    const slider = () => screen.getByRole('slider', { name: 'Iris' })
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' })
    act(() => {
      vi.advanceTimersByTime(400)
    })
    await act(async () => {
      await Promise.resolve()
    })
    // The first save landed (iris 0.99). A second press is waiting when the list is re-read — the
    // read the save's invalidation asked for, which knows only the first.
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' })
    rerender(
      <StageFocusPanel projectId={1} patch={{ ...p, iris: 0.99 }} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />,
    )
    expect(slider()).toHaveAttribute('aria-valuenow', '0.98')
    // A third press steps from what the card shows, and the save sends it.
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' })
    act(() => {
      vi.advanceTimersByTime(400)
    })
    expect(updatePatch).toHaveBeenLastCalledWith({ projectId: 1, patchId: 7, iris: 0.97 })
  })

  it('focuses a placement on its own and saves the whole placement list, focus included', () => {
    const p = patch({
      extraPlacements: [
        { uuid: 'pl-1', label: 'SR', stageX: 1, stageY: 0, stageZ: 5, lanternType: 'par64-cp62', lampRotationDeg: 10 },
      ] as FixturePatch['extraPlacements'],
    })
    const { rerender } = render(
      <StageFocusPanel projectId={1} patch={p} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />,
    )
    fireEvent.click(screen.getByRole('tab', { name: 'Lantern 2 · SR' }))
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Lamp rotation' }), { key: 'ArrowRight' })
    const cached = cacheAfter([p])[0]
    expect(cached.lampRotationDeg ?? null).toBeNull()
    expect(cached.extraPlacements?.[0].lampRotationDeg).toBe(11)
    // The route re-renders from the cache, as the patch list query would.
    rerender(<StageFocusPanel projectId={1} patch={cached} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />)
    act(() => {
      vi.advanceTimersByTime(400)
    })
    expect(updatePatch).toHaveBeenCalledTimes(1)
    const body = updatePatch.mock.calls[0][0]
    expect(body).not.toHaveProperty('lanternType')
    expect(body.extraPlacements).toEqual([
      expect.objectContaining({ uuid: 'pl-1', lanternType: 'par64-cp62', lampRotationDeg: 11 }),
    ])
  })

  it('still saves a pending edit when the panel goes', () => {
    const p = patch()
    const { unmount } = render(
      <StageFocusPanel projectId={1} patch={p} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />,
    )
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Iris' }), { key: 'ArrowLeft' })
    unmount()
    expect(updatePatch).toHaveBeenCalledTimes(1)
  })

  it('re-reads the patch list when the desk refuses the save', async () => {
    updatePatch.mockImplementationOnce(() => ({ unwrap: () => Promise.reject(new Error('no')) }))
    render(<StageFocusPanel projectId={1} patch={patch()} fixture={undefined} fixtureType={DIMMER_TYPE} lanterns={lanterns} />)
    fireEvent.keyDown(screen.getByRole('slider', { name: 'Iris' }), { key: 'ArrowLeft' })
    await act(async () => {
      vi.advanceTimersByTime(400)
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({ payload: ['Patch'] }))
  })

  it('names a DMX fixture’s optics channels rather than offering a spanner', () => {
    const fixture = {
      name: 'Spot 1',
      properties: [
        { name: 'zoom', type: 'slider', category: 'zoom' },
        { name: 'iris', type: 'slider', category: 'iris' },
      ],
    } as unknown as Fixture
    render(
      <StageFocusPanel
        projectId={1}
        patch={patch({ fixtureTypeKey: 'mac-250', lanternType: null })}
        fixture={fixture}
        fixtureType={{ typeKey: 'mac-250', kind: 'MOVING_HEAD', acceptsLantern: false } as unknown as FixtureTypeInfo}
        lanterns={lanterns}
      />,
    )
    expect(screen.queryByRole('slider')).toBeNull()
    expect(screen.getByText(/Spot 1/)).toBeInTheDocument()
  })

  it('names framing shutters among the optics a DMX fixture drives from its channels', () => {
    const fixture = {
      name: 'Rev 1',
      properties: [{ name: 'zoom', type: 'slider', category: 'zoom' }, ...revolutionShutterProps()],
    } as unknown as Fixture
    render(
      <StageFocusPanel
        projectId={1}
        patch={patch({ key: 'rev-1', fixtureTypeKey: 'etc-source4-revolution-base-frame', lanternType: null })}
        fixture={fixture}
        fixtureType={{ typeKey: 'etc-source4-revolution-base-frame', kind: 'MOVING_HEAD', acceptsLantern: false } as unknown as FixtureTypeInfo}
        lanterns={lanterns}
      />,
    )
    expect(screen.getByText(/Rev 1 drives its zoom and framing shutters from its channels/)).toBeInTheDocument()
    expect(screen.queryByRole('slider')).toBeNull()
  })

  it("lists each unit's fitted media for a type with loadable settings, and none for one without", () => {
    const scroller = {
      type: 'setting', name: 'gelScroller', displayName: 'Gel scroller', category: 'colour', media: 'GEL',
      channel: { universe: 1, channelNo: 13 },
      options: [
        { name: 'OPEN_LEADER', level: 0, displayName: 'Open Leader', colourPreview: '#FFFFFF', loadable: true },
        { name: 'R02_BASTARD_AMBER', level: 18, displayName: 'R02', colourPreview: '#fbcc9a', loadable: true },
        { name: 'L201_FULL_CT_BLUE', level: 165, displayName: 'L201', colourPreview: '#9bbede', loadable: true },
      ],
    }
    const fixture = { name: 'Rev 1', properties: [scroller] } as unknown as Fixture
    const revType = { typeKey: 'etc-source4-revolution-base-frame', kind: 'MOVING_HEAD', acceptsLantern: false } as unknown as FixtureTypeInfo
    render(
      <StageFocusPanel
        projectId={1}
        patch={patch({
          key: 'rev-1', fixtureTypeKey: revType.typeKey, lanternType: null,
          media: { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } } } },
          extraPlacements: [
            {
              uuid: 'sl', label: 'SL', riggingUuid: null, stageX: 4, stageY: -16, stageZ: 2.8, baseYawDeg: null, basePitchDeg: 180,
              media: { slots: { gelScroller: { R02_BASTARD_AMBER: {} } } },
            },
          ],
        })}
        fixture={fixture}
        fixtureType={revType}
        lanterns={lanterns}
      />,
    )
    const list = screen.getByRole('region', { name: 'Fitted media' })
    const own = list.querySelector('[data-media-unit="fixture"]') as HTMLElement
    expect(own).toHaveTextContent('Unit 1')
    expect(own).toHaveTextContent('Gel scroller · frame 2R26 Light Red')
    const sl = list.querySelector('[data-media-unit="sl"]') as HTMLElement
    expect(sl).toHaveTextContent('Unit 2 · SL')
    // The placement's own over the patch's: its emptied frame 1, and frame 2's R26 from unit 1.
    expect(sl).toHaveTextContent('frame 1empty')
    expect(sl).toHaveTextContent('frame 2R26 Light Red')

    cleanup()
    render(
      <StageFocusPanel
        projectId={1}
        patch={patch({ key: 'rev-2', fixtureTypeKey: revType.typeKey, lanternType: null })}
        fixture={fixture}
        fixtureType={revType}
        lanterns={lanterns}
      />,
    )
    expect(screen.getByRole('region', { name: 'Fitted media' })).toHaveTextContent('Stock in every slot.')
  })

  it('tells a DMX fixture with no optics channels that its optics are fixed — not that its channels set them', () => {
    const fixture = { name: 'Par 1', properties: [{ name: 'dimmer', type: 'slider', category: 'dimmer' }] } as unknown as Fixture
    render(
      <StageFocusPanel
        projectId={1}
        patch={patch({ key: 'par-1', fixtureTypeKey: 'hex', lanternType: null })}
        fixture={fixture}
        fixtureType={{ typeKey: 'hex', kind: 'PAR', acceptsLantern: false } as unknown as FixtureTypeInfo}
        lanterns={lanterns}
        canFocus
      />,
    )
    expect(screen.getByText(/its optics are fixed/)).toBeInTheDocument()
    expect(screen.queryByText(/from its channels/)).toBeNull()
    expect(screen.queryByRole('button', { name: /Focus here/ })).toBeNull()
  })

  describe('Focus here', () => {
    const REV_FOCUS = {
      name: 'focus', type: 'slider', category: 'focus', channel: { universe: 1, channelNo: 7 }, min: 0, max: 255,
      focusNearM: 2, focusFarM: 40,
    }
    const revolution = { name: 'Rev 1', properties: [REV_FOCUS] } as unknown as Fixture
    const REV_TYPE = { typeKey: 'etc-source4-revolution-base-frame', kind: 'PROFILE', acceptsLantern: false } as unknown as FixtureTypeInfo
    const panel = (over: { fixture?: Fixture; canFocus?: boolean } = {}) =>
      render(
        <StageFocusPanel
          projectId={15}
          patch={patch({ key: 'rev-1', fixtureTypeKey: REV_TYPE.typeKey, lanternType: null })}
          fixture={over.fixture ?? revolution}
          fixtureType={REV_TYPE}
          lanterns={lanterns}
          canFocus={over.canFocus ?? true}
        />,
      )

    it('sends where the beam lands in the view, and says what the desk focused', async () => {
      recordLanding(REPORTER, 'rev-1', { x: 0, y: 6.7, z: 2.8 })
      panel()
      expect(screen.getByText(/drives its focus from its channels/)).toBeInTheDocument()
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /Focus here/ }))
      })
      expect(focusHere).toHaveBeenCalledWith({
        projectId: 15,
        targets: [{ type: 'fixture', key: 'rev-1' }],
        point: { x: 0, y: 6.7, z: 2.8 },
      })
      expect(screen.getByRole('status')).toHaveTextContent('Focused at 24.0 m (DMX 246).')
    })

    it('sends nothing while the beam lands on nothing, and says so', async () => {
      recordLanding(REPORTER, 'rev-1', null)
      panel()
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /Focus here/ }))
      })
      expect(focusHere).not.toHaveBeenCalled()
      expect(screen.getByRole('status')).toHaveTextContent(/lands on nothing/)
    })

    it('is offered only on the live project, and only for a focus that declares its range', () => {
      panel({ canFocus: false })
      expect(screen.queryByRole('button', { name: /Focus here/ })).toBeNull()
      cleanup()
      const unranged = { name: 'Rev 1', properties: [{ ...REV_FOCUS, focusNearM: undefined, focusFarM: undefined }] } as unknown as Fixture
      panel({ fixture: unranged })
      expect(screen.queryByRole('button', { name: /Focus here/ })).toBeNull()
    })
  })
})

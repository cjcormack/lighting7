// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enablePatches, produceWithPatches } from 'immer'
import type { FixturePatch } from '@/api/patchApi'
import libraryJson from '../../../../src/main/resources/lanterns/library.json'
import { indexLanterns, type Lantern } from '@/lib/lanterns'
import type { Fixture, FixtureTypeInfo } from '@/store/fixtures'

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

import { StageFocusPanel } from './StageFocusPanel'

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
})

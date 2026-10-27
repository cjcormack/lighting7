// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { chan, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { ActiveFx } from '@/store/fixtureFx'
import type { BuskingTarget } from './buskingTypes'

/**
 * The busk Effects tab (fixture-fx-sheets plan D20, apply-model plan §4.5): what runs on the
 * selection — a pad's effect and the operator's own, editable, and a cue's, read-only — each
 * editable row opening the live editor that follows the busk selection (the split), a pad's row
 * marked *edited* while its running instance differs from the template's effect; *+ FX…* opens
 * the one picker's effects arm on the busk pair.
 */

const SPOT_1 = makeFixture('spot-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Spot 1', groups: ['Spots'] })
const SPOT_2 = makeFixture('spot-2', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Spot 2', groups: ['Spots'] })
const WASH = makeFixture('wash-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Wash 1' })

const state = vi.hoisted(() => ({ effects: [] as unknown[] }))
const splitFx = vi.hoisted(() => vi.fn((_id: number, _update: Record<string, unknown>) => Promise.resolve(null)))
vi.mock('@/store/fixtureFx', () => ({
  useActiveFxQuery: () => ({ data: state.effects }),
  useFxLibraryQuery: () => ({ data: [] }),
  usePauseFxMutation: () => [vi.fn()],
  useResumeFxMutation: () => [vi.fn()],
  useRemoveFxMutation: () => [vi.fn()],
  useSplitFx: () => ({ splitFx, splitting: false }),
  isFxSplit: (answer: object) => 'splitFxId' in answer,
}))
vi.mock('@/components/fx/useFxControls', () => ({ useStopFx: () => vi.fn() }))
vi.mock('@/hooks/usePress', () => ({ useWindowPressPair: () => ({ targets: [], families: null }) }))
const picker = vi.hoisted(() => ({ props: null as Record<string, unknown> | null }))
vi.mock('@/components/press/ApplyPicker', () => ({
  ApplyPicker: (props: Record<string, unknown>) => {
    picker.props = props
    return props.open ? <div data-testid="apply-picker" /> : null
  },
}))
vi.mock('@/store/groups', () => ({
  usePauseGroupFxMutation: () => [vi.fn()],
  useResumeGroupFxMutation: () => [vi.fn()],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/store/speedMasters', () => ({ useMaster1Uuid: () => 'master-1' }))
vi.mock('@/store/palettes', () => ({
  usePaletteListQuery: () => ({
    data: [
      {
        id: 7,
        name: 'Big circle',
        kind: 'fx',
        fx: {
          fxType: 'Circle',
          family: 'position',
          cycle: 0.5,
          blendMode: 'ADDITIVE',
          order: 'NORMAL',
          phaseOffset: 0,
          // Stored on Fixtures, as a new palette is; the spots' position is their own, so the
          // running instance reports no Over, and that is no edit.
          elementMode: 'WHOLE',
          parameters: { panCentre: '128', tiltCentre: '128', panRadius: '60', tiltRadius: '20' },
          speedMasterUuid: null,
          rateSpeedMasterUuid: null,
        },
      },
    ],
  }),
}))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [SPOT_1, SPOT_2, WASH],
    fixtureByKey: new Map([SPOT_1, SPOT_2, WASH].map((f) => [f.key, f])),
  }),
}))
vi.mock('@/components/fixtureSheet/fxLabels', () => ({ useFxDetail: () => () => '½ · M1' }))
/** The strip's ask, read rather than sent: the desk's preview is `useFxPreview.test.tsx`'s. */
const previews = vi.hoisted(() => ({ asks: [] as unknown[] }))
vi.mock('@/components/fxEditor/useFxPreview', () => ({
  useFxPreview: (ask: unknown) => {
    previews.asks.push(ask)
    return { answer: null, error: null }
  },
}))
/** The editor stand-in: one move, held where the split editor holds it. */
vi.mock('@/components/fx/FxEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/fx/FxEditor')>()),
  FxEditor: ({ effect, hold, alsoIds }: { effect: ActiveFx; hold?: (request: unknown) => void; alsoIds?: readonly number[] }) => (
    <div data-testid="fx-editor" data-effect={effect.id} data-also={(alsoIds ?? []).join()}>
      <button type="button" onClick={() => hold?.({ parameters: { max: '200' } })}>
        move
      </button>
    </div>
  ),
}))

import { FxSheet, fxOnSelection } from './FxSheet'

/** Spot 1's pad Circle, as the template spawned it — or edited to a 80-byte orbit. */
const padCircle = (panRadius = '60') =>
  makeActiveFx({
    id: 21,
    fxType: 'Circle',
    targetKey: 'Spots',
    isGroupTarget: true,
    attribute: 'position',
    cycle: 0.5,
    blendMode: 'ADDITIVE',
    parameters: { panCentre: '128', tiltCentre: '128', panRadius, tiltRadius: '20' },
    paletteId: 7,
    programmerLayerId: 3,
    sourceName: 'Big circle',
  })
const ownPulse = makeActiveFx({ id: 22, fxType: 'Pulse', targetKey: 'spot-1', attribute: 'dimmer' })
const cuePulse = makeActiveFx({ id: 23, fxType: 'Pulse', targetKey: 'spot-1', programmerOwned: false, cueId: 12 })
const washChase = makeActiveFx({ id: 24, fxType: 'Chase', targetKey: 'wash-1' })

const spot1: BuskingTarget = { type: 'fixture', key: 'spot-1', fixture: SPOT_1 }

afterEach(() => cleanup())

describe('fxOnSelection', () => {
  it('lists the effects reaching the selection — a group a selected head is in, its own, a cue’s — and not a script’s', () => {
    const scriptPulse = makeActiveFx({ id: 25, fxType: 'Pulse', targetKey: 'spot-1', programmerOwned: false })
    // A cue's instance fading out after a GO is nobody's, and is not listed.
    const leavingCue = makeActiveFx({ id: 26, fxType: 'Chase', targetKey: 'spot-1', programmerOwned: false, cueId: 11, leaving: true })
    const all = [padCircle(), ownPulse, cuePulse, washChase, scriptPulse, leavingCue]
    expect(fxOnSelection(all, [spot1], [SPOT_1, SPOT_2, WASH]).map((e) => e.id)).toEqual([21, 22, 23])
    expect(fxOnSelection(all, [], [SPOT_1, SPOT_2, WASH])).toEqual([])
    // A selected group reaches its members' own effects too.
    const spots: BuskingTarget = { type: 'group', name: 'Spots', group: { name: 'Spots', memberCount: 2 } } as BuskingTarget
    expect(fxOnSelection(all, [spots], [SPOT_1, SPOT_2, WASH]).map((e) => e.id)).toEqual([21, 22, 23])
  })
})

describe('FxSheet', () => {
  it('marks a pad’s instance edited while it differs from its palette, and only then', () => {
    state.effects = [padCircle('80'), ownPulse]
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    expect(screen.getByText('Running on 1 head · 2 FX')).toBeTruthy()
    const pad = document.querySelector('[data-effect-row="21"]') as HTMLElement
    expect(within(pad).getByText('edited')).toBeTruthy()
    expect(within(pad).getByText(/pad · Big circle/)).toBeTruthy()
    // A pad's effect is stopped by its pad, not here; the operator's own keeps its stop.
    expect(within(pad).queryByRole('button', { name: 'Stop Circle' })).toBeNull()
    const own = document.querySelector('[data-effect-row="22"]') as HTMLElement
    expect(within(own).queryByText('edited')).toBeNull()
    expect(within(own).getByRole('button', { name: 'Stop Pulse' })).toBeTruthy()

    cleanup()
    state.effects = [padCircle('60')]
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    expect(screen.queryByText('edited')).toBeNull()
  })

  it('asks before stopping a group’s effect reached through one member — it stops on every member', () => {
    const groupPulse = makeActiveFx({ id: 30, fxType: 'Pulse', targetKey: 'Spots', isGroupTarget: true })
    state.effects = [groupPulse]
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    try {
      render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
      const row = document.querySelector('[data-effect-row="30"]') as HTMLElement
      expect(within(row).getByText(/via Spots/)).toBeTruthy()
      fireEvent.click(within(row).getByRole('button', { name: 'Stop Pulse' }))
      expect(confirm).toHaveBeenCalledWith('Stop Pulse on every fixture in Spots?')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('names a selection’s items, never its key, when it reaches past the selected heads', () => {
    const targets = [
      { type: 'fixture' as const, key: 'spot-1' },
      { type: 'fixture' as const, key: 'wash-1' },
    ]
    state.effects = [
      makeActiveFx({
        id: 31,
        fxType: 'Pulse',
        targetKey: JSON.stringify(targets.map((t) => ({ key: t.key, type: t.type }))),
        targetType: 'selection',
        targets,
      }),
    ]
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    const row = document.querySelector('[data-effect-row="31"]') as HTMLElement
    expect(within(row).getByText(/via Spot 1, Wash 1/)).toBeTruthy()
    expect(within(row).queryByText(/\[\{/)).toBeNull()
  })

  it('opens the live editor under a row', () => {
    state.effects = [padCircle('80')]
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-effect', '21')
  })

  it('draws the graph strip under the open row only: its FX on its own heads, over what is underneath', () => {
    state.effects = [padCircle(), ownPulse]
    previews.asks = []
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    expect(document.querySelector('[data-fx-graph-strip]')).toBeNull()
    expect(previews.asks).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    const strips = [...document.querySelectorAll('[data-fx-graph-strip]')]
    expect(strips).toHaveLength(1)
    expect(strips[0].closest('[data-effects-editor]')).not.toBeNull()
    expect(strips[0]).toHaveAttribute('data-fx-graph-strip', 'result')
    // It folds every FX on its heads, so a change to any running FX asks again.
    expect(previews.asks.at(-1)).toMatchObject({ projectId: 1, targets: [], fxIds: [22], listed: [21, 22], ownHeads: true })
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(document.querySelectorAll('[data-fx-graph-strip]')).toHaveLength(1)
    expect(previews.asks.at(-1)).toMatchObject({ fxIds: [21] })
  })

  it('says what to select, and what to press, when there is nothing to list', () => {
    state.effects = [washChase]
    render(<FxSheet projectId={1} selectedTargets={new Map()} families={null} />)
    expect(screen.getByText(/Select heads on the rig/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /FX…/ })).toBeDisabled()
    cleanup()
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    expect(screen.getByText(/Nothing runs on the selection/)).toBeTruthy()
  })

  it('draws a cue’s effect read-only: no editor, no pause, no stop', () => {
    state.effects = [cuePulse]
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    const row = document.querySelector('[data-effect-row="23"]') as HTMLElement
    expect(within(row).getByText(/→ dimmer · Spot 1 · cue/)).toBeTruthy()
    expect(within(row).queryByRole('button')).toBeNull()
  })

  it('opens + FX… as the one picker’s effects arm on the busk pair, writing Local', () => {
    state.effects = []
    const spots: BuskingTarget = { type: 'group', name: 'Spots', group: { name: 'Spots', memberCount: 2 } } as BuskingTarget
    render(
      <FxSheet
        projectId={1}
        selectedTargets={new Map<string, BuskingTarget>([['group:Spots', spots], ['fixture:wash-1', { type: 'fixture', key: 'wash-1', fixture: WASH }]])}
        families={['INTENSITY']}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /FX…/ }))
    expect(screen.getByTestId('apply-picker')).toBeTruthy()
    expect(picker.props).toMatchObject({
      library: 'fx',
      scope: 'programmer',
      family: 'INTENSITY',
      // A group stays one group effect, its distribution intact.
      pair: {
        targets: [
          { type: 'group', key: 'Spots' },
          { type: 'fixture', key: 'wash-1' },
        ],
        families: ['INTENSITY'],
      },
    })
  })

  it('splits a Local effect on part of its heads: the held edit goes out once with those heads as targets', async () => {
    splitFx.mockClear()
    const spotsPulse = makeActiveFx({ id: 40, fxType: 'Pulse', targetKey: 'Spots', isGroupTarget: true, targetType: 'group' })
    state.effects = [spotsPulse]
    render(<FxSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    const split = document.querySelector('[data-fx-split]') as HTMLElement
    expect(split.textContent).toContain('Spot 1')
    const apply = within(split).getByRole('button', { name: /Apply to/ })
    expect(apply).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(apply)
    await waitFor(() => expect(splitFx).toHaveBeenCalledTimes(1))
    expect(splitFx).toHaveBeenCalledWith(40, { parameters: { max: '200' }, targets: [{ type: 'fixture', key: 'spot-1' }] })
  })

  it('follows every edited copy after a split, though the editor moves to the first copy’s row', async () => {
    const spotsPulse = makeActiveFx({ id: 40, fxType: 'Pulse', targetKey: 'Spots', isGroupTarget: true, targetType: 'group' })
    const copies = [41, 42].map((id) => makeActiveFx({ id, fxType: 'Pulse', targetKey: 'spot-1' }))
    state.effects = [spotsPulse]
    // The desk's answer, put into the running list as `useSplitFx` puts it.
    splitFx.mockImplementationOnce(() => {
      state.effects = copies
      return Promise.resolve({ splitFxId: 40, edited: copies, rest: [] } as never)
    })
    const both = new Map<string, BuskingTarget>([
      ['fixture:spot-1', spot1],
      ['fixture:wash-1', { type: 'fixture', key: 'wash-1', fixture: WASH }],
    ])
    render(<FxSheet projectId={1} selectedTargets={both} families={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(within(document.querySelector('[data-fx-split]') as HTMLElement).getByRole('button', { name: /Apply to/ }))
    await waitFor(() => expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-effect', '41'))
    expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-also', '42')
    expect(document.querySelector('[data-effect-row="41"] + [data-effects-editor]')).not.toBeNull()
  })
})

// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { chan, makeActiveEffect, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { ActiveEffect } from '@/store/fixtureFx'
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
  useActiveEffectsQuery: () => ({ data: state.effects }),
  useEffectLibraryQuery: () => ({ data: [] }),
  usePauseFxMutation: () => [vi.fn()],
  useResumeFxMutation: () => [vi.fn()],
  useRemoveFxMutation: () => [vi.fn()],
  useSplitFx: () => ({ splitFx, splitting: false }),
  isEffectSplit: (answer: object) => 'splitEffectId' in answer,
}))
vi.mock('@/components/fx/useEffectControls', () => ({ useStopEffect: () => vi.fn() }))
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
vi.mock('@/store/templates', () => ({
  useTemplateListQuery: () => ({
    data: [
      {
        id: 7,
        name: 'Big circle',
        kind: 'effect',
        effect: {
          effectType: 'Circle',
          category: 'position',
          beatDivision: 0.5,
          blendMode: 'ADDITIVE',
          distribution: 'LINEAR',
          phaseOffset: 0,
          parameters: { panCenter: '128', tiltCenter: '128', panRadius: '60', tiltRadius: '20' },
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
vi.mock('@/components/fixtureSheet/effectLabels', () => ({ useEffectDetail: () => () => '½ · M1' }))
/** The editor stand-in: one move, held where the split editor holds it. */
vi.mock('@/components/fx/FxEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/fx/FxEditor')>()),
  FxEditor: ({ effect, hold, alsoIds }: { effect: ActiveEffect; hold?: (request: unknown) => void; alsoIds?: readonly number[] }) => (
    <div data-testid="fx-editor" data-effect={effect.id} data-also={(alsoIds ?? []).join()}>
      <button type="button" onClick={() => hold?.({ parameters: { max: '200' } })}>
        move
      </button>
    </div>
  ),
}))

import { EffectsSheet, effectsOnSelection } from './EffectsSheet'

/** Spot 1's pad Circle, as the template spawned it — or edited to a 80-byte orbit. */
const padCircle = (panRadius = '60') =>
  makeActiveEffect({
    id: 21,
    effectType: 'Circle',
    targetKey: 'Spots',
    isGroupTarget: true,
    propertyName: 'position',
    beatDivision: 0.5,
    blendMode: 'ADDITIVE',
    parameters: { panCenter: '128', tiltCenter: '128', panRadius, tiltRadius: '20' },
    templateId: 7,
    programmerLayerId: 3,
    sourceName: 'Big circle',
  })
const ownPulse = makeActiveEffect({ id: 22, effectType: 'Pulse', targetKey: 'spot-1', propertyName: 'dimmer' })
const cuePulse = makeActiveEffect({ id: 23, effectType: 'Pulse', targetKey: 'spot-1', programmerOwned: false, cueId: 12 })
const washChase = makeActiveEffect({ id: 24, effectType: 'Chase', targetKey: 'wash-1' })

const spot1: BuskingTarget = { type: 'fixture', key: 'spot-1', fixture: SPOT_1 }

afterEach(() => cleanup())

describe('effectsOnSelection', () => {
  it('lists the effects reaching the selection — a group a selected head is in, its own, a cue’s — and not a script’s', () => {
    const scriptPulse = makeActiveEffect({ id: 25, effectType: 'Pulse', targetKey: 'spot-1', programmerOwned: false })
    // A cue's instance fading out after a GO is nobody's, and is not listed.
    const leavingCue = makeActiveEffect({ id: 26, effectType: 'Chase', targetKey: 'spot-1', programmerOwned: false, cueId: 11, leaving: true })
    const all = [padCircle(), ownPulse, cuePulse, washChase, scriptPulse, leavingCue]
    expect(effectsOnSelection(all, [spot1], [SPOT_1, SPOT_2, WASH]).map((e) => e.id)).toEqual([21, 22, 23])
    expect(effectsOnSelection(all, [], [SPOT_1, SPOT_2, WASH])).toEqual([])
    // A selected group reaches its members' own effects too.
    const spots: BuskingTarget = { type: 'group', name: 'Spots', group: { name: 'Spots', memberCount: 2 } } as BuskingTarget
    expect(effectsOnSelection(all, [spots], [SPOT_1, SPOT_2, WASH]).map((e) => e.id)).toEqual([21, 22, 23])
  })
})

describe('EffectsSheet', () => {
  it('marks a pad’s instance edited while it differs from its palette, and only then', () => {
    state.effects = [padCircle('80'), ownPulse]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
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
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    expect(screen.queryByText('edited')).toBeNull()
  })

  it('asks before stopping a group’s effect reached through one member — it stops on every member', () => {
    const groupPulse = makeActiveEffect({ id: 30, effectType: 'Pulse', targetKey: 'Spots', isGroupTarget: true })
    state.effects = [groupPulse]
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    try {
      render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
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
      makeActiveEffect({
        id: 31,
        effectType: 'Pulse',
        targetKey: JSON.stringify(targets.map((t) => ({ key: t.key, type: t.type }))),
        targetType: 'selection',
        targets,
      }),
    ]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    const row = document.querySelector('[data-effect-row="31"]') as HTMLElement
    expect(within(row).getByText(/via Spot 1, Wash 1/)).toBeTruthy()
    expect(within(row).queryByText(/\[\{/)).toBeNull()
  })

  it('opens the live editor under a row', () => {
    state.effects = [padCircle('80')]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-effect', '21')
  })

  it('says what to select, and what to press, when there is nothing to list', () => {
    state.effects = [washChase]
    render(<EffectsSheet projectId={1} selectedTargets={new Map()} families={null} />)
    expect(screen.getByText(/Select heads on the rig/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /FX…/ })).toBeDisabled()
    cleanup()
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    expect(screen.getByText(/Nothing runs on the selection/)).toBeTruthy()
  })

  it('draws a cue’s effect read-only: no editor, no pause, no stop', () => {
    state.effects = [cuePulse]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
    const row = document.querySelector('[data-effect-row="23"]') as HTMLElement
    expect(within(row).getByText(/→ dimmer · Spot 1 · cue/)).toBeTruthy()
    expect(within(row).queryByRole('button')).toBeNull()
  })

  it('opens + FX… as the one picker’s effects arm on the busk pair, writing Local', () => {
    state.effects = []
    const spots: BuskingTarget = { type: 'group', name: 'Spots', group: { name: 'Spots', memberCount: 2 } } as BuskingTarget
    render(
      <EffectsSheet
        projectId={1}
        selectedTargets={new Map<string, BuskingTarget>([['group:Spots', spots], ['fixture:wash-1', { type: 'fixture', key: 'wash-1', fixture: WASH }]])}
        families={['INTENSITY']}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /FX…/ }))
    expect(screen.getByTestId('apply-picker')).toBeTruthy()
    expect(picker.props).toMatchObject({
      library: 'effects',
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
    const spotsPulse = makeActiveEffect({ id: 40, effectType: 'Pulse', targetKey: 'Spots', isGroupTarget: true, targetType: 'group' })
    state.effects = [spotsPulse]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} families={null} />)
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
    const spotsPulse = makeActiveEffect({ id: 40, effectType: 'Pulse', targetKey: 'Spots', isGroupTarget: true, targetType: 'group' })
    const copies = [41, 42].map((id) => makeActiveEffect({ id, effectType: 'Pulse', targetKey: 'spot-1' }))
    state.effects = [spotsPulse]
    // The desk's answer, put into the running list as `useSplitFx` puts it.
    splitFx.mockImplementationOnce(() => {
      state.effects = copies
      return Promise.resolve({ splitEffectId: 40, edited: copies, rest: [] } as never)
    })
    const both = new Map<string, BuskingTarget>([
      ['fixture:spot-1', spot1],
      ['fixture:wash-1', { type: 'fixture', key: 'wash-1', fixture: WASH }],
    ])
    render(<EffectsSheet projectId={1} selectedTargets={both} families={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    fireEvent.click(screen.getByRole('button', { name: 'move' }))
    fireEvent.click(within(document.querySelector('[data-fx-split]') as HTMLElement).getByRole('button', { name: /Apply to/ }))
    await waitFor(() => expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-effect', '41'))
    expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-also', '42')
    expect(document.querySelector('[data-effect-row="41"] + [data-effects-editor]')).not.toBeNull()
  })
})

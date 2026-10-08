// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { chan, makeActiveEffect, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { BuskingTarget } from './buskingTypes'

/**
 * The busk Effects tab (fixture-fx-sheets plan D20): what the programmer runs on the selection —
 * a pad's effect and the operator's own, never a cue's — each row opening the live editor, a pad's
 * row marked *edited* while its running instance differs from the template's effect.
 */

const SPOT_1 = makeFixture('spot-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Spot 1', groups: ['Spots'] })
const SPOT_2 = makeFixture('spot-2', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Spot 2', groups: ['Spots'] })
const WASH = makeFixture('wash-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Wash 1' })

const state = vi.hoisted(() => ({ effects: [] as unknown[] }))
vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: state.effects }),
  useEffectLibraryQuery: () => ({ data: [] }),
  usePauseFxMutation: () => [vi.fn()],
  useResumeFxMutation: () => [vi.fn()],
  useRemoveFxMutation: () => [vi.fn()],
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
vi.mock('@/components/fx/FxEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/fx/FxEditor')>()),
  FxEditor: ({ effect }: { effect: ActiveEffect }) => <div data-testid="fx-editor" data-effect={effect.id} />,
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
  it('lists the programmer effects reaching the selection — a group a selected head is in, its own — and not a cue’s', () => {
    const all = [padCircle(), ownPulse, cuePulse, washChase]
    expect(effectsOnSelection(all, [spot1], [SPOT_1, SPOT_2, WASH]).map((e) => e.id)).toEqual([21, 22])
    expect(effectsOnSelection(all, [], [SPOT_1, SPOT_2, WASH])).toEqual([])
    // A selected group reaches its members' own effects too.
    const spots: BuskingTarget = { type: 'group', name: 'Spots', group: { name: 'Spots', memberCount: 2 } } as BuskingTarget
    expect(effectsOnSelection(all, [spots], [SPOT_1, SPOT_2, WASH]).map((e) => e.id)).toEqual([21, 22])
  })
})

describe('EffectsSheet', () => {
  it('marks a pad’s instance edited while it differs from its template, and only then', () => {
    state.effects = [padCircle('80'), ownPulse]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} />)
    expect(screen.getByText('Running on 1 head · 2 effects')).toBeTruthy()
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
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} />)
    expect(screen.queryByText('edited')).toBeNull()
  })

  it('asks before stopping a group’s effect reached through one member — it stops on every member', () => {
    const groupPulse = makeActiveEffect({ id: 30, effectType: 'Pulse', targetKey: 'Spots', isGroupTarget: true })
    state.effects = [groupPulse]
    const confirm = vi.fn(() => false)
    vi.stubGlobal('confirm', confirm)
    try {
      render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} />)
      const row = document.querySelector('[data-effect-row="30"]') as HTMLElement
      expect(within(row).getByText(/via Spots/)).toBeTruthy()
      fireEvent.click(within(row).getByRole('button', { name: 'Stop Pulse' }))
      expect(confirm).toHaveBeenCalledWith('Stop Pulse on every fixture in Spots?')
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('opens the live editor under a row', () => {
    state.effects = [padCircle('80')]
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(screen.getByTestId('fx-editor')).toHaveAttribute('data-effect', '21')
  })

  it('says what to select, and what to press, when there is nothing to list', () => {
    state.effects = [cuePulse]
    render(<EffectsSheet projectId={1} selectedTargets={new Map()} />)
    expect(screen.getByText(/Select heads on the rig/)).toBeTruthy()
    cleanup()
    render(<EffectsSheet projectId={1} selectedTargets={new Map([['fixture:spot-1', spot1]])} />)
    expect(screen.getByText(/Nothing runs on the selection/)).toBeTruthy()
  })
})

// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActiveEffect } from '@/store/fixtureFx'

/**
 * The running-effects band's row. What is pinned is the *content* the two-line layout exists to
 * keep on screen — the name, the target, the home, and the row's one control — because the bug
 * this replaced was a single line that squeezed the name to nothing and pushed the menu off the
 * rail, and jsdom cannot see either. What it can see is that every one of those is rendered for
 * every row, and that two rows on two heads can be told apart.
 */
const mocks = vi.hoisted(() => ({
  effects: [] as ActiveEffect[],
  removeFx: vi.fn(),
  removeGroupFx: vi.fn(),
}))

vi.mock('@/store/fixtureFx', () => ({
  useActiveEffectsQuery: () => ({ data: mocks.effects }),
  useEffectLibraryQuery: () => ({ data: [] }),
  useRemoveFxMutation: () => [mocks.removeFx],
}))
vi.mock('@/store/groups', () => ({ useRemoveGroupFxMutation: () => [mocks.removeGroupFx] }))
vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({
    data: [{ layerId: 4, source: { kind: 'LOOK', id: 1, name: 'Warm Wash' } }],
  }),
}))
// Reads a speed-master bank the test has no store for; the chip is the bank's concern, not this row's.
vi.mock('@/components/fx/SpeedMasterChip', () => ({ SpeedMasterChip: () => null }))
vi.mock('./ProgrammerAddEffect', () => ({ ProgrammerAddEffect: () => <button>+ Effect</button> }))
vi.mock('./NewTemplateFromEffectSheet', () => ({ NewTemplateFromEffectSheet: () => null }))
vi.mock('../busking/ActiveEffectSheet', () => ({ ActiveEffectSheet: () => null }))

import { ProgrammerFxList } from './ProgrammerFxList'

function effect(over: Partial<ActiveEffect>): ActiveEffect {
  return {
    id: 1,
    effectType: 'sinewave',
    targetKey: 'hex-1',
    propertyName: 'dimmer',
    beatDivision: 1,
    blendMode: 'OVERRIDE',
    isRunning: true,
    phaseOffset: 0,
    currentPhase: 0,
    parameters: {},
    isGroupTarget: false,
    distributionStrategy: null,
    elementMode: null,
    elementFilter: null,
    stepTiming: false,
    lookId: null,
    templateId: null,
    sourceName: null,
    programmerLayerId: null,
    cueId: null,
    timingSource: 'BEAT',
    programmerOwned: true,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  } as ActiveEffect
}

beforeEach(() => {
  mocks.effects = []
})
afterEach(cleanup)

describe('ProgrammerFxList', () => {
  it('draws the name, the property, the target, the home and the menu on every row', () => {
    mocks.effects = [
      effect({ id: 1, targetKey: 'hex-1' }),
      effect({ id: 2, targetKey: 'hex-2' }),
    ]
    render(<ProgrammerFxList />)

    // Two identical effects on two heads read as two rows because the target is on the row.
    expect(screen.getAllByText('sinewave')).toHaveLength(2)
    expect(screen.getByText('hex-1')).toBeInTheDocument()
    expect(screen.getByText('hex-2')).toBeInTheDocument()
    expect(screen.getAllByText('→ dimmer')).toHaveLength(2)
    expect(screen.getAllByText('programmer band')).toHaveLength(2)
    // The explanation rides the badge's title rather than the row, which has no room for it.
    expect(screen.getAllByTitle('programmer band · yours until recorded')).toHaveLength(2)
    expect(screen.queryByText('yours until recorded')).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Actions for sinewave' })).toHaveLength(2)
  })

  it('names the layer an effect lives in, from the same broadcast the stack rail draws', () => {
    mocks.effects = [
      effect({ programmerLayerId: 4, lookId: 1, sourceName: 'Warm Wash', programmerOwned: false }),
    ]
    render(<ProgrammerFxList />)
    expect(screen.getByText('in Warm Wash')).toBeInTheDocument()
    expect(screen.getByText('layer 1')).toBeInTheDocument()
  })

  it('marks a group target as one', () => {
    mocks.effects = [effect({ targetKey: 'Front wash', isGroupTarget: true })]
    render(<ProgrammerFxList />)
    expect(screen.getByTitle('Group Front wash')).toBeInTheDocument()
  })

  it('says when a row is paused', () => {
    mocks.effects = [effect({ isRunning: false })]
    render(<ProgrammerFxList />)
    expect(screen.getByTitle('Paused')).toBeInTheDocument()
  })
})

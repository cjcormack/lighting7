// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Cue } from '@/api/cuesApi'
import type { CueStack, StackMoveInDarkPlan } from '@/api/cueStacksApi'
import type { MoveInDarkHead } from '@/api/moveInDarkApi'
import { cueRow } from '@/test/cueEntries'

/**
 * The Cue tab's *Move in dark* section (move-in-dark plan session 4, design §5.3): the segment and
 * its first option, the delay and move time over the stack's, the head list from the plan merged
 * with what the stack is doing now, the per-head *In dark · Live*, and locked.
 */
const patchCue = vi.fn((_arg: Record<string, unknown>) => ({ unwrap: () => Promise.resolve() }))
vi.mock('@/store/cues', () => ({ usePatchProjectCueMutation: () => [patchCue] }))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))
const world: { stacks: CueStack[]; plan: StackMoveInDarkPlan | undefined; heads: MoveInDarkHead[] } = { stacks: [], plan: undefined, heads: [] }
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: world.stacks }),
  useCueStackMoveInDarkQuery: (_arg: unknown, opts?: { skip?: boolean }) => ({ data: opts?.skip ? undefined : world.plan }),
}))
vi.mock('@/store/moveInDark', () => ({ useMoveInDarkHeads: () => world.heads }))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [
      { key: 'spot-1', name: 'Spot 1' },
      { key: 'spot-2', name: 'Spot 2' },
      { key: 'bar', name: 'Bar', elements: [{ key: 'bar.head-1', displayName: 'Head 1' }, { key: 'bar.head-2', displayName: 'Head 2' }] },
    ],
  }),
}))

import { CueMoveInDarkSection } from './CueMoveInDarkSection'

const STACK: CueStack = {
  id: 7,
  name: 'Main',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cueRow(10, { entryId: 110, cueNumber: '10' }), cueRow(11, { entryId: 111, cueNumber: '11' }), cueRow(12, { entryId: 112, cueNumber: '12' })],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
  moveInDark: { on: true, when: 'EARLY', delayMs: 1000, timeMs: 2000, families: ['POSITION', 'COLOUR', 'BEAM'] },
}

function cue(over: Partial<Cue> = {}): Cue {
  return {
    id: 12,
    name: 'Singer special',
    layers: [],
    adHocFx: [],
    attributeAssignments: [],
    triggers: [],
    cueStackId: 7,
    cueStackName: 'Main',
    entryId: 112,
    sortOrder: 2,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: 3000,
    fadeCurve: 'SINE_IN_OUT',
    cueNumber: '12',
    cueNumberAuto: false,
    notes: null,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
    moveInDark: { mode: 'STACK', delayMs: null, timeMs: null, live: [] },
    ...over,
  }
}

function draw(c: Cue = cue(), locked = false) {
  return render(<CueMoveInDarkSection cue={c} projectId={1} locked={locked} idBase="t" />)
}

const headRow = (key: string) => document.querySelector(`[data-head="${key}"]`) as HTMLElement

beforeEach(() => {
  patchCue.mockClear()
  world.stacks = [STACK]
  world.heads = []
  world.plan = {
    stackId: 7,
    rigLoaded: true,
    summary: { heads: 2, cues: 1, live: 1, off: 0 },
    entries: [
      {
        entryId: 112,
        cueId: 12,
        heads: [
          { target: 'spot-1', fromEntryId: 110, families: ['POSITION'] },
          { target: 'bar.head-1', fromEntryId: 111, families: ['POSITION', 'COLOUR'] },
        ],
        live: [{ target: 'spot-2', reason: 'LIT_BEFORE', families: ['POSITION'] }],
      },
    ],
  }
})
afterEach(cleanup)

describe('CueMoveInDarkSection', () => {
  it('the segment’s first option reads the stack’s setting, and a pick writes the mode alone', () => {
    draw()
    expect(screen.getByRole('radio', { name: 'Stack · Early' })).toHaveAttribute('data-state', 'on')
    fireEvent.click(screen.getByRole('radio', { name: 'Off' }))
    expect(patchCue).toHaveBeenCalledWith({ projectId: 1, cueId: 12, moveInDark: { mode: 'OFF' } })
  })

  it('reads Stack · Off on a stack that is off', () => {
    world.stacks = [{ ...STACK, moveInDark: { ...STACK.moveInDark!, on: false } }]
    draw()
    expect(screen.getByRole('radio', { name: 'Stack · Off' })).toBeInTheDocument()
  })

  it('shows the stack’s times until typed over, and blank gives them back', () => {
    draw(cue({ moveInDark: { mode: 'STACK', delayMs: 1250, timeMs: null, live: [] } }))
    const delay = screen.getByLabelText('Delay')
    const time = screen.getByLabelText('Move time')
    expect(time).toHaveValue('')
    expect(time).toHaveAttribute('placeholder', 'Stack · 2s')
    // A blur with nothing typed writes nothing, though the field shows 1.25 s in tenths.
    fireEvent.blur(delay)
    expect(patchCue).not.toHaveBeenCalled()
    time.focus()
    fireEvent.change(time, { target: { value: '4' } })
    fireEvent.keyDown(time, { key: 'Enter' })
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 12, moveInDark: { timeMs: 4000 } })
    // Enter commits by leaving the field; back in and out again before the cue reads back sends nothing more.
    expect(patchCue).toHaveBeenCalledTimes(1)
    time.focus()
    fireEvent.blur(time)
    expect(patchCue).toHaveBeenCalledTimes(1)
    fireEvent.change(delay, { target: { value: '' } })
    fireEvent.blur(delay)
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 12, moveInDark: { delayMs: null } })
    fireEvent.change(delay, { target: { value: '99' } })
    fireEvent.blur(delay)
    expect(screen.getByText(/A delay is 0 to 30 seconds/)).toBeInTheDocument()
    expect(patchCue).toHaveBeenCalledTimes(2)
  })

  it('lists each head it lights: where it moves from, what moves, and how it stands now', () => {
    world.heads = [
      { target: 'spot-1', entryId: 112, cueId: 12, label: 'Q12', state: 'SET', endsAtMs: null, families: ['POSITION'], position: null },
      { target: 'bar.head-1', entryId: 112, cueId: 12, label: 'Q12', state: 'MOVING', endsAtMs: Date.now() + 1000, families: ['POSITION'], position: null },
      // Moving for another cue: not this cue's.
      { target: 'spot-2', entryId: 111, cueId: 11, label: 'Q11', state: 'WAITING', endsAtMs: null, families: ['POSITION'], position: null },
    ]
    draw()
    expect(within(headRow('spot-1')).getByText('Spot 1')).toBeInTheDocument()
    expect(within(headRow('spot-1')).getByText('Q10')).toBeInTheDocument()
    expect(within(headRow('spot-1')).getByText('Set')).toBeInTheDocument()
    expect(within(headRow('bar.head-1')).getByText('Bar Head 1')).toBeInTheDocument()
    expect(within(headRow('bar.head-1')).getByText('Q11')).toBeInTheDocument()
    expect(within(headRow('bar.head-1')).getByText('Position · Colour')).toBeInTheDocument()
    expect(within(headRow('bar.head-1')).getByRole('progressbar')).toBeInTheDocument()
    // Lit before is lit in the entry played before this one, by its number; and it says what moves.
    expect(within(headRow('spot-2')).getByText('Lit in Q11')).toBeInTheDocument()
    expect(within(headRow('spot-2')).getByText('Position')).toBeInTheDocument()
    expect(within(headRow('spot-2')).queryByText('Waiting')).toBeNull()
    expect(screen.getByText('Heads this cue lights')).toBeInTheDocument()
    expect(screen.getByText(/^From: the cue the move happens in\./)).toBeInTheDocument()
  })

  it('Live keeps one head lit; In dark again takes it back, and a head live with its fixture leaves the rest live', () => {
    const { rerender } = draw()
    fireEvent.click(within(headRow('spot-1')).getByRole('radio', { name: 'Live' }))
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 12, moveInDark: { live: ['spot-1'] } })
    // A second toggle before the cue reads back builds on the first.
    fireEvent.click(within(headRow('bar.head-1')).getByRole('radio', { name: 'Live' }))
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 12, moveInDark: { live: ['spot-1', 'bar.head-1'] } })
    rerender(<CueMoveInDarkSection cue={cue({ moveInDark: { mode: 'STACK', live: ['spot-1', 'bar.head-1'] } })} projectId={1} locked={false} idBase="t" />)
    world.plan!.entries[0].live.push({ target: 'bar.head-1', reason: 'KEPT_LIVE' })
    world.plan!.entries[0].heads = world.plan!.entries[0].heads.filter((h) => h.target !== 'bar.head-1')
    rerender(<CueMoveInDarkSection cue={cue({ moveInDark: { mode: 'STACK', live: ['bar'] } })} projectId={1} locked={false} idBase="t" />)
    const bar = within(headRow('bar.head-1'))
    expect(bar.getByRole('radio', { name: 'Live' })).toHaveAttribute('data-state', 'on')
    fireEvent.click(bar.getByRole('radio', { name: 'In dark' }))
    expect(patchCue).toHaveBeenLastCalledWith({ projectId: 1, cueId: 12, moveInDark: { live: ['bar.head-2'] } })
  })

  it('locked, the controls go and the list stays', () => {
    draw(cue(), true)
    expect(screen.queryByRole('radio', { name: 'Off' })).toBeNull()
    expect(screen.queryByLabelText('Delay')).toBeNull()
    expect(screen.getByText(/Stack · Early · delay Stack · 1s/)).toBeInTheDocument()
    expect(headRow('spot-1')).not.toBeNull()
    expect(within(headRow('spot-1')).queryByRole('radio')).toBeNull()
  })

  it('a loose cue says nothing moves its heads in the dark', () => {
    draw(cue({ cueStackId: null, entryId: null, entries: [] }))
    expect(screen.getByText(/in no stack/)).toBeInTheDocument()
  })

  it('a cue opened from no place reads its first stack', () => {
    draw(cue({ cueStackId: null, entryId: null, entries: [{ entryId: 112, stackId: 7, stackName: 'Main', sortOrder: 2, cueNumber: '12', cueNumberAuto: false, autoAdvance: false, autoAdvanceDelayMs: null }] }))
    expect(headRow('spot-1')).not.toBeNull()
  })
})

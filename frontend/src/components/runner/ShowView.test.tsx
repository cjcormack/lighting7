// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import type { CueStack } from '@/api/cueStacksApi'

/**
 * Characterisation suite written before session 2b (`desk-simplification-plan.md` §Session 2b,
 * phase 0). `ShowView` is the switch between the stack list and one stack's cues, and 2b changes
 * both sides of it — so the two rules it enforces today need pinning first.
 */

vi.mock('@/store/cues', () => ({
  useProjectCueListQuery: () => ({ data: [] }),
  useCreateProjectCueMutation: () => [vi.fn()],
  useDeleteProjectCueMutation: () => [vi.fn()],
  usePatchProjectCueMutation: () => [vi.fn()],
}))

const detail: { props?: Record<string, unknown> } = {}
vi.mock('./StackDetail', () => ({
  StackDetail: (p: Record<string, unknown>) => {
    detail.props = p
    return <div data-testid="stack-detail" />
  },
}))
vi.mock('./ShowOverview', () => ({
  ShowOverview: () => <div data-testid="show-overview" />,
}))

import { ShowView } from './ShowView'

const mkStack = (over: Partial<CueStack> = {}): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [],
  activeCueId: null,
  nextCueId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

const STACKS = [mkStack(), mkStack({ id: 11, name: 'Act 2', sortOrder: 1 })]

function draw(over: Partial<React.ComponentProps<typeof ShowView>> = {}) {
  return render(
    <ShowView
      projectId={1}
      stacks={STACKS}
      drillStackId={null}
      onDrillStack={vi.fn()}
      activeStackId={null}
      activeCueId={null}
      isExpanded={() => false}
      onToggleExpanded={vi.fn()}
      {...over}
    />,
  )
}

afterEach(() => {
  cleanup()
  detail.props = undefined
  vi.clearAllMocks()
})

describe('ShowView', () => {
  it('shows the stack list when nothing is drilled', () => {
    draw()
    expect(screen.getByTestId('show-overview')).toBeTruthy()
    expect(screen.queryByTestId('stack-detail')).toBeNull()
  })

  it('shows one stack when drilled in', () => {
    draw({ drillStackId: 11 })
    expect(screen.getByTestId('stack-detail')).toBeTruthy()
    expect((detail.props!.stack as CueStack).id).toBe(11)
  })

  it('falls back to the stack list when the drilled stack is unknown', () => {
    draw({ drillStackId: 404 })
    expect(screen.getByTestId('show-overview')).toBeTruthy()
  })

  it('passes the active cue only while drilled into the live stack', () => {
    // The green marker belongs to the stack that is actually running. Showing it in a stack the
    // operator is merely reading would claim a cue is on stage when it is not.
    draw({ drillStackId: 10, activeStackId: 10, activeCueId: 5 })
    expect(detail.props!.activeCueId).toBe(5)
  })

  it('nulls the active cue when drilled into a stack that is not live', () => {
    draw({ drillStackId: 11, activeStackId: 10, activeCueId: 5 })
    expect(detail.props!.activeCueId).toBeNull()
  })

  it('passes the armed cue through in the live stack', () => {
    // Was "supplies no standby cue at all" — the gap phase 3 closed. `StackDetail` had accepted
    // this prop and `CueCardEditor` had drawn a blue "next" accent from it since 2a, with nothing
    // supplying it, so the affordance was unreachable in Show while Run had it.
    draw({ drillStackId: 10, activeStackId: 10, activeCueId: 5, standbyCueId: 6 })
    expect(detail.props!.standbyCueId).toBe(6)
  })

  it('withholds the arming callback in a stack that is not live', () => {
    // Show-critical. `transport.setStandby` acts on the *playhead*, so handing a browsed stack's row
    // an arming callback would arm a cue id that is not in the live stack — the next GO fires the
    // wrong thing, or the POST 4xxs. Exactly the stray click the browse/arm split exists to stop,
    // and it must be gated like the three sibling props beside it.
    draw({ drillStackId: 11, activeStackId: 10, onSetStandby: vi.fn() })
    expect(detail.props!.onSetStandby).toBeUndefined()
  })

  it('passes the arming callback through in the live stack', () => {
    const onSetStandby = vi.fn()
    draw({ drillStackId: 10, activeStackId: 10, onSetStandby })
    expect(detail.props!.onSetStandby).toBe(onSetStandby)
  })

  it('withholds the armed cue in a stack that is not live', () => {
    // Same reasoning as the active cue: "next GO" is a fact about the running stack, and drawing
    // it on a stack being read would claim a cue was queued when it was not.
    draw({ drillStackId: 11, activeStackId: 10, activeCueId: 5, standbyCueId: 6 })
    expect(detail.props!.standbyCueId).toBeNull()
  })
})

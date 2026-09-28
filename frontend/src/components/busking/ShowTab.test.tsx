// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { CueStack } from '@/api/cueStacksApi'

/**
 * The side sheet's Show tab (busk-chrome plan D1, D2, D5; `ShowTab.dc.html`'s six rules): the
 * phone runner over the route's transport, the programmer chip in its strip, cards collapsed by
 * default, requeue through the transport and inert off the playhead, the picker browsing with the
 * off-playhead banner saying so, and no transport keys.
 */

const stacks: CueStack[] = [
  {
    id: 1,
    name: 'Main Show',
    type: 'STACK',
    loop: false,
    activeCueId: 12,
    cues: [
      { id: 12, cueNumber: '12', name: 'Verse 2', cueType: 'STANDARD' },
      { id: 13, cueNumber: '13', name: 'Chorus', cueType: 'STANDARD' },
    ],
  },
  { id: 2, name: 'Encore', type: 'STACK', loop: false, activeCueId: null, cues: [{ id: 21, cueNumber: '1', name: 'Bow', cueType: 'STANDARD' }] },
] as unknown as CueStack[]

const programState = { data: { activeStackId: 1 as number | null } }
const goToStack = vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))
vi.mock('@/store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: stacks }),
  useProjectProgramStateQuery: () => programState,
  useGoToStackMutation: () => [goToStack],
  useDeactivateCueStackMutation: () => [vi.fn()],
}))
vi.mock('@/store/promptBooks', () => ({
  useProjectCueLocationsQuery: () => ({ data: [{ cueId: 12, page: 9, y: 0.1 }] }), // page 9 zero-based reads "p. 10"
  useProjectPromptBookQuery: () => ({ data: { coverPages: 0 } }),
}))
vi.mock('react-redux', () => ({ useDispatch: () => vi.fn() }))
vi.mock('@/components/ProgrammerIndicator', () => ({
  ProgrammerIndicator: () => <span data-testid="programmer-chip" />,
}))
const transportKeys = vi.fn()
vi.mock('@/hooks/useTransportKeys', () => ({ useTransportKeys: (o: unknown) => transportKeys(o) }))
vi.mock('@/components/runner/OffPlayheadBanner', () => ({
  OffPlayheadBanner: ({ selectedStackName, onJumpToLive, onMakeLive }: { selectedStackName: string; onJumpToLive: () => void; onMakeLive: () => void }) => (
    <div data-testid="off-playhead">
      {selectedStackName}
      <button type="button" onClick={onJumpToLive}>Jump to live</button>
      <button type="button" onClick={onMakeLive}>Make live</button>
    </div>
  ),
}))
// The runner as a probe: its props are what the tab is responsible for.
const runnerProps = vi.fn()
vi.mock('@/components/runner/mobile/RunMobile', () => ({
  RunMobile: (props: Record<string, unknown> & { strip?: React.ReactNode; onGo: () => void; onSelectStack: (s: CueStack) => void; onRequeueCue: (id: number) => void }) => {
    runnerProps(props)
    return (
      <div data-testid="runner">
        <div data-testid="strip">{props.strip}</div>
        <button type="button" onClick={props.onGo}>GO</button>
        <button type="button" onClick={() => props.onSelectStack(stacks[1])}>Browse Encore</button>
        <button type="button" onClick={() => props.onRequeueCue(13)}>Requeue 13</button>
      </div>
    )
  },
}))

import { ShowTab, type ShowTabSource } from './ShowTab'

function source(): ShowTabSource {
  return {
    transport: {
      activeStack: stacks[0],
      serverActiveCueId: 12,
      activeCueId: 12,
      standbyCueId: 13,
      completedCueIds: [],
      go: vi.fn(),
      back: vi.fn(),
      setStandby: vi.fn(),
      cancelAnimations: vi.fn(),
    },
    showBarProps: { dbo: true, onDbo: vi.fn() },
    activeCue: stacks[0].cues[0],
    standbyCue: stacks[0].cues[1],
    nextStack: null,
  } as unknown as ShowTabSource
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  programState.data = { activeStackId: 1 }
})

describe('ShowTab', () => {
  it('mounts the phone runner over the route’s transport, the programmer chip in its strip, cards collapsed (D1, D2)', () => {
    const show = source()
    render(<ShowTab projectId={1} show={show} />)
    expect(screen.getByTestId('strip').querySelector('[data-testid="programmer-chip"]')).not.toBeNull()
    const props = runnerProps.mock.calls.at(-1)![0]
    expect(props.defaultExpansion).toBeNull()
    expect(props.stack).toBe(stacks[0])
    expect(props.selectedStackId).toBe(1)
    expect(props.multiStack).toBe(true)
    expect(props.dbo).toBe(true)
    expect(props.onDbo).toBe(show.showBarProps.onDbo)
    expect(props.onBack).toBe(show.transport.back)
    expect(props.fadeStackId).toBe(1)
    expect(props.activeLocation).toBe('top of p. 10')
    expect(props.standbyLocation).toBeNull()
    expect(props.display).toEqual(expect.objectContaining({ activeCueId: 12, standbyCueId: 13, activeCue: stacks[0].cues[0] }))
    // GO is the runner's footer, wired to the one transport.
    fireEvent.click(screen.getByRole('button', { name: 'GO' }))
    expect(show.transport.go).toHaveBeenCalledTimes(1)
    // A tap in the cue list arms through the transport.
    fireEvent.click(screen.getByRole('button', { name: 'Requeue 13' }))
    expect(show.transport.setStandby).toHaveBeenCalledWith(13)
    expect(screen.queryByTestId('off-playhead')).toBeNull()
  })

  it('binds no transport keys (D5)', () => {
    render(<ShowTab projectId={1} show={source()} />)
    expect(transportKeys).not.toHaveBeenCalled()
  })

  it('browses another stack from the picker, says so, makes requeue inert there, and comes back on Jump to live', () => {
    const show = source()
    render(<ShowTab projectId={1} show={show} />)
    fireEvent.click(screen.getByRole('button', { name: 'Browse Encore' }))
    expect(screen.getByTestId('off-playhead')).toHaveTextContent('Encore')
    expect(runnerProps.mock.calls.at(-1)![0].stack).toBe(stacks[1])
    fireEvent.click(screen.getByRole('button', { name: 'Requeue 13' }))
    expect(show.transport.setStandby).not.toHaveBeenCalled()
    // Make live is the same go-to sequence Show runs.
    fireEvent.click(screen.getByRole('button', { name: 'Make live' }))
    expect(show.transport.cancelAnimations).toHaveBeenCalledTimes(1)
    expect(goToStack).toHaveBeenCalledWith({ projectId: 1, stackId: 2 })
    fireEvent.click(screen.getByRole('button', { name: 'Jump to live' }))
    expect(screen.queryByTestId('off-playhead')).toBeNull()
    expect(runnerProps.mock.calls.at(-1)![0].stack).toBe(stacks[0])
  })

  it('follows a browsed stack again once it becomes the live one', () => {
    const show = source()
    const { rerender } = render(<ShowTab projectId={1} show={show} />)
    fireEvent.click(screen.getByRole('button', { name: 'Browse Encore' }))
    expect(screen.getByTestId('off-playhead')).toBeInTheDocument()
    programState.data = { activeStackId: 2 }
    const live = { ...show, transport: { ...show.transport, activeStack: stacks[1] } } as ShowTabSource
    rerender(<ShowTab projectId={1} show={live} />)
    expect(screen.queryByTestId('off-playhead')).toBeNull()
    expect(runnerProps.mock.calls.at(-1)![0].stack).toBe(stacks[1])
  })
})

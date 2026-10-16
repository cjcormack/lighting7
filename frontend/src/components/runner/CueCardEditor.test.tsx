// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { CueStackCueRow } from '@/api/cueStacksApi'

/**
 * The cue row serves a show being *run* and a show being *edited*, and which of those it is doing
 * is the lock. These pin the part that is easy to get wrong in exactly one direction: making the
 * row safe while locked and, in doing so, making it unusable.
 */

vi.mock('@dnd-kit/sortable', () => ({
  useSortable: (args: { disabled?: boolean }) => {
    sortableArgs.disabled = args.disabled
    return {
      attributes: {},
      listeners: { onPointerDown: vi.fn() },
      setNodeRef: vi.fn(),
      transform: null,
      transition: undefined,
      isDragging: false,
    }
  },
}))
const sortableArgs: { disabled?: boolean } = {}
vi.mock('@dnd-kit/utilities', () => ({ CSS: { Transform: { toString: () => undefined } } }))
const patchCue = vi.fn()
const patchEntry = vi.fn()
/** What the card hands its action set — the actions themselves are `CueActions.test.tsx`'s. */
const actionProps: { last?: Record<string, unknown> } = {}
vi.mock('./CueActions', () => ({
  CueActions: (props: Record<string, unknown>) => {
    actionProps.last = props
    return <div data-testid="cue-actions" />
  },
}))
/** A mutation trigger's result: `unwrap` resolves, as a landed request does. */
const landed = (value: unknown = undefined) => ({ unwrap: () => Promise.resolve(value) })
vi.mock('@/store/cues', () => ({
  usePatchProjectCueMutation: () => [patchCue],
}))
vi.mock('@/store/cueStacks', () => ({
  usePatchCueStackEntryMutation: () => [patchEntry],
}))
vi.mock('@/components/cues/CueRowParts', () => ({
  CueStatePip: () => <div data-testid="pip" />,
  CueTargetChip: () => <div />,
  useExpandedCue: () => ({
    cueData: { id: 1, name: 'Q1', adHocEffects: [], triggers: [], layers: [] },
    targets: [],
    isFetching: false,
  }),
}))
vi.mock('@/hooks/useCueFade', () => ({
  useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }),
}))
vi.mock('@/components/cues/CueDetailContent', () => ({
  CueDetailContent: () => <div data-testid="detail" />,
}))
vi.mock('@/components/cues/CuePropertiesSheet', () => ({ CuePropertiesSheet: () => null }))
// The hand's place target under the read surface — store-touching, like `CueDetailContent` above.
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandCueLayerStrip: () => null }))
vi.mock('@/components/SaveStatusIndicator', () => ({ SaveStatusIndicator: () => null }))

import { CueCardEditor } from './CueCardEditor'

const cue: CueStackCueRow = {
  id: 1,
  entryId: 101,
  name: 'Q1',
  sortOrder: 1,
  layerCount: 0,
  adHocEffectCount: 0,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
  fadeDurationMs: 2000,
  fadeCurve: 'LINEAR',
  cueNumber: '1',
  cueNumberAuto: false,
  notes: null,
  cueType: 'STANDARD',
}

function draw(over: Partial<React.ComponentProps<typeof CueCardEditor>> = {}) {
  const onToggleExpanded = vi.fn()
  const onSetStandby = vi.fn()
  const utils = render(
    <CueCardEditor
      cue={cue}
      stackId={7}
      projectId={1}
      expanded={false}
      onToggleExpanded={onToggleExpanded}
      onSetStandby={onSetStandby}
      {...over}
    />,
  )
  return { ...utils, onToggleExpanded, onSetStandby }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('CueCardEditor', () => {
  it('expands from the chevron while locked', () => {
    // The regression this suite exists for. Locked, the row body arms the cue rather than expanding
    // it — so if the chevron is not its own target there is no way to open a card during a show.
    const { onToggleExpanded, onSetStandby } = draw({ locked: true })
    fireEvent.click(screen.getByLabelText('Expand cue'))

    expect(onToggleExpanded).toHaveBeenCalledTimes(1)
    expect(onSetStandby).not.toHaveBeenCalled()
  })

  it('expands from the chevron while unlocked', () => {
    const { onToggleExpanded } = draw({ locked: false })
    fireEvent.click(screen.getByLabelText('Expand cue'))
    expect(onToggleExpanded).toHaveBeenCalledTimes(1)
  })

  it('arms the cue from the row body while locked', () => {
    // Locked is the running state, where reaching for a cue means "go there next".
    const { onToggleExpanded, onSetStandby } = draw({ locked: true })
    fireEvent.click(screen.getByTestId('pip'))

    expect(onSetStandby).toHaveBeenCalledTimes(1)
    expect(onToggleExpanded).not.toHaveBeenCalled()
  })

  it('expands from the row body while unlocked', () => {
    // Unlocked means editing, where reaching for a cue means "let me look at it". Arming is
    // deliberately unavailable: it changes what GO fires, which is the show.
    const { onToggleExpanded, onSetStandby } = draw({ locked: false })
    fireEvent.click(screen.getByTestId('pip'))

    expect(onToggleExpanded).toHaveBeenCalledTimes(1)
    expect(onSetStandby).not.toHaveBeenCalled()
  })

  it('expands the row body of the live cue rather than re-arming it', () => {
    const { onToggleExpanded, onSetStandby } = draw({ locked: true, isActive: true })
    fireEvent.click(screen.getByTestId('pip'))

    expect(onToggleExpanded).toHaveBeenCalledTimes(1)
    expect(onSetStandby).not.toHaveBeenCalled()
  })

  it('reports the open state to assistive tech', () => {
    draw({ expanded: true })
    expect(screen.getByLabelText('Collapse cue').getAttribute('aria-expanded')).toBe('true')
  })

  it('makes dragging impossible while locked', () => {
    // Through dnd-kit's own `disabled`, not by unmounting the context — the row needs its
    // `SortableContext` ancestor either way.
    draw({ locked: true })
    expect(sortableArgs.disabled).toBe(true)
    expect(screen.queryByLabelText(/drag/i)).toBeNull()
  })

  it('allows dragging while unlocked', () => {
    draw({ locked: false })
    expect(sortableArgs.disabled).toBe(false)
  })

  it('draws the whole action set under the row while locked, and only reading below it', () => {
    // D8: locked, the strip is drawn whole — Blind edit works, the rest ask to unlock — and the
    // card's editing form (*Cue properties…*) stays away.
    const unlock = vi.fn()
    draw({ locked: true, expanded: true, onRequestUnlock: unlock })
    expect(screen.getByTestId('detail')).toBeTruthy()
    expect(screen.getByTestId('cue-actions')).toBeTruthy()
    expect(actionProps.last).toMatchObject({
      host: 'strip',
      locked: true,
      onRequestUnlock: unlock,
      rows: [{ cueId: 1, name: 'Q1', cueNumber: '1', entryId: 101, stackId: 7 }],
    })
    expect(screen.queryByText('Cue properties…')).toBeNull()
  })

  it('draws the action set and the properties door while unlocked', () => {
    draw({ locked: false, expanded: true })
    expect(screen.getByTestId('cue-actions')).toBeTruthy()
    expect(actionProps.last).toMatchObject({ locked: false })
    expect(screen.getByText('Cue properties…')).toBeTruthy()
  })

  it('draws no actions on a closed card', () => {
    draw({ expanded: false })
    expect(screen.queryByTestId('cue-actions')).toBeNull()
  })

  it('writes a number edit to the entry, not the cue', () => {
    patchEntry.mockReturnValue(landed())
    draw()
    fireEvent.click(screen.getByRole('button', { name: /^cue number:/ }))
    const input = screen.getByRole('textbox', { name: 'cue number' })
    fireEvent.change(input, { target: { value: '14A' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(patchEntry).toHaveBeenCalledWith({ projectId: 1, stackId: 7, entryId: 101, cueId: 1, cueNumber: '14A' })
    expect(patchCue).not.toHaveBeenCalled()
  })

  it('marks a cue in two stacks and names the other place', () => {
    draw({
      places: [
        { stackId: 7, stackName: 'Act 1', entryId: 101, cueNumber: '1' },
        { stackId: 8, stackName: 'Act 2', entryId: 205, cueNumber: '14' },
      ],
    })
    const chip = screen.getByRole('button', { name: /in 2 stacks/ })
    expect(chip.getAttribute('title')).toBe('Also Act 2 · Q14')
  })

  it('draws no mark for a cue in one place', () => {
    draw({ places: [{ stackId: 7, stackName: 'Act 1', entryId: 101, cueNumber: '1' }] })
    expect(document.querySelector('[data-shared-cue]')).toBeNull()
  })
})

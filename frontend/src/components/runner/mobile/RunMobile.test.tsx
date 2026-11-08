// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { CueStack, CueStackCueRow } from '@/api/cueStacksApi'

/**
 * The phone runner's cards since venue-visit plan S3 took the cards' 2D `MiniStage` away: each
 * card's toggle is Details alone, and both cards open collapsed — on the phone as in the busk
 * view's Show tab, which mounts this same component.
 */

vi.mock('@/store/cueHolds', () => ({ useCueEdited: () => false }))
vi.mock('@/store/cues', () => ({
  useProjectCueQuery: () => ({ data: { id: 1 } }),
}))
vi.mock('@/components/cues/CueDetailContent', () => ({
  CueDetailContent: () => <div data-testid="details" />,
}))
// The Up next card's Move in dark chip reads the store; its words are `StackMovingChip.test.tsx`'s.
vi.mock('@/components/cues/StackMovingChip', () => ({
  StackMovingChip: ({ stackId }: { stackId: number }) => <span data-testid="moving-chip" data-stack={stackId} />,
}))
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }) }))
vi.mock('../../SpeedMasters', () => ({ SpeedMastersChip: () => null }))
vi.mock('../StackPickerSheet', () => ({ StackPickerSheet: () => null }))
vi.mock('../MobileCueListSheet', () => ({ MobileCueListSheet: () => null }))
vi.mock('../StackFxAmountControl', () => ({ MobileStackFxAmount: () => null }))

import { RunMobile, type RunnerDisplayState } from './RunMobile'

function entry(id: number, cueNumber: string): CueStackCueRow {
  return {
    id,
    entryId: id + 100,
    name: `Q${cueNumber}`,
    sortOrder: id,
    layerCount: 0,
    adHocFxCount: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: 2000,
    fadeCurve: 'LINEAR',
    cueNumber,
    cueNumberAuto: true,
    notes: null,
    cueType: 'STANDARD',
  }
}

const cues = [entry(1, '1'), entry(2, '2')]
const stack = {
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues,
  activeCueId: 1,
  nextCueId: 2,
  canEdit: true,
  canDelete: true,
} as CueStack

function draw(display: Partial<RunnerDisplayState> = {}) {
  return render(
    <RunMobile
      stacks={[stack]}
      selectedStackId={10}
      stack={stack}
      multiStack={false}
      display={{ activeCue: cues[0], standbyCue: cues[1], nextStack: null, nothingOnDeck: false, goReleases: false, ...display } as unknown as RunnerDisplayState}
      onGo={vi.fn()}
      onBack={vi.fn()}
      onSelectStack={vi.fn()}
      onRequeueCue={vi.fn()}
      projectId={1}
      fadeStackId={10}
      activeLocation={null}
      standbyLocation={null}
    />,
  )
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('RunMobile cards (venue-visit S3)', () => {
  it('opens both cards collapsed, each with a Details toggle and no Stage', () => {
    draw()
    expect(screen.getAllByRole('button', { name: 'Details' })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Stage' })).toBeNull()
    expect(screen.queryByTestId('details')).toBeNull()
  })

  it('opens one card’s details at a time', () => {
    draw()
    const [current, next] = screen.getAllByRole('button', { name: 'Details' })
    fireEvent.click(current)
    expect(screen.getAllByTestId('details')).toHaveLength(1)
    fireEvent.click(next)
    expect(screen.getAllByTestId('details')).toHaveLength(1)
    fireEvent.click(next)
    expect(screen.queryByTestId('details')).toBeNull()
  })
})

describe('RunMobile GO / END (busk-stack plan §3 "Release")', () => {
  const goButton = () => screen.getByRole('button', { name: /^(GO|END)$/ })

  it('reads GO with a cue on deck', () => {
    draw()
    expect(goButton()).toHaveTextContent('GO')
    expect(goButton()).toBeEnabled()
  })

  it('reads GO on a Release stack’s last cue, since that GO releases it', () => {
    draw({ activeCue: cues[1], standbyCue: null, nothingOnDeck: true, goReleases: true })
    expect(goButton()).toHaveTextContent('GO')
    expect(goButton()).toBeEnabled()
  })

  it('reads END once the last stack has released, though the desk names its first cue as next', () => {
    draw({ activeCue: null, standbyCue: cues[0], nothingOnDeck: true, goReleases: false })
    expect(goButton()).toHaveTextContent('END')
    expect(goButton()).toBeDisabled()
  })

  it('reads GO after a release when another stack follows in the running order', () => {
    draw({ activeCue: null, standbyCue: cues[0], nothingOnDeck: true, nextStack: { ...stack, id: 11, name: 'Act 2' } })
    expect(goButton()).toHaveTextContent('GO')
  })
})

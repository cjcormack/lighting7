// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import type { CueStackCueEntry } from '@/api/cueStacksApi'

vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }) }))
// The open card's body is the runner's; here it only has to place the two slots the card fills.
vi.mock('@/components/runner/mobile/CueCardBody', () => ({
  CueCardBody: ({ afterIdentity, footer }: { afterIdentity?: ReactNode; footer?: ReactNode }) => (
    <section>
      {afterIdentity}
      {footer}
    </section>
  ),
}))
vi.mock('@/components/scenery/CueSceneryEditor', () => ({
  CueSceneryEditor: ({ cue }: { cue: { id: number } | null }) =>
    cue == null ? null : <div data-testid="scenery-editor">editing {cue.id}</div>,
}))

import { PromptBookCueCard } from './PromptBookCueCard'

afterEach(cleanup)

const noop = () => {}
const entry: CueStackCueEntry = {
  id: 2, name: 'Moon rise', sortOrder: 2, layerCount: 0, adHocEffectCount: 0, autoAdvance: false,
  autoAdvanceDelayMs: null, fadeDurationMs: 3000, fadeCurve: 'LINEAR', cueNumber: '2', cueNumberAuto: false,
  notes: null, cueType: 'STANDARD',
}
const props = (over: Partial<ComponentProps<typeof PromptBookCueCard>> = {}): ComponentProps<typeof PromptBookCueCard> => ({
  cue: { cueId: 2, label: 'Q2', name: 'Moon rise', fadeMs: 3000, fadeCurve: 'LINEAR', stackId: 1, stackName: 'Act 1' },
  status: 'next', anchor: undefined, anchorHint: null, cueEntry: entry, projectId: 1, warnings: [], locked: false,
  placing: false, expanded: true, mode: null, onModeChange: noop, fadeStackId: null, canSetNext: false, coverPages: 0,
  onCueClick: noop, onToggleExpanded: noop, onSetStandby: noop, onRemoveAnchor: noop, onEditCue: noop,
  onRenameCue: noop, onRenumberCue: noop, onRenoteCue: noop,
  sceneryLines: ['Moon → in · 4 s'],
  ...over,
})

/** A rail card's scenery (scenery-programmer plan D14): its lines on both faces, and *Scenery…* unlocked. */
describe('PromptBookCueCard · scenery', () => {
  it('lists the cue\'s changes on the open card and on the collapsed row', () => {
    const { rerender } = render(<PromptBookCueCard {...props()} />)
    expect(document.querySelector('[data-card-scenery="2"]')).toHaveTextContent('Moon → in · 4 s')
    rerender(<PromptBookCueCard {...props({ expanded: false })} />)
    expect(document.querySelector('[data-card-scenery="2"]')).toHaveTextContent('Moon → in · 4 s')
  })

  it('offers Scenery… only while unlocked', () => {
    const { rerender } = render(<PromptBookCueCard {...props()} />)
    expect(screen.getByRole('button', { name: 'Scenery…' })).toBeInTheDocument()
    rerender(<PromptBookCueCard {...props({ locked: true })} />)
    expect(screen.queryByRole('button', { name: 'Scenery…' })).toBeNull()
  })

  it('an editor open when the card collapses does not come back with the next expand', () => {
    const { rerender } = render(<PromptBookCueCard {...props()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Scenery…' }))
    expect(screen.getByTestId('scenery-editor')).toHaveTextContent('editing 2')
    rerender(<PromptBookCueCard {...props({ expanded: false })} />)
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
    rerender(<PromptBookCueCard {...props({ expanded: true })} />)
    expect(screen.queryByTestId('scenery-editor')).toBeNull()
  })
})

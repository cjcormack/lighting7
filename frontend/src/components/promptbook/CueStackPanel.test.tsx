// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'

vi.mock('@/components/SpeedMasters', () => ({ SpeedMastersChip: () => null }))
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }) }))

import { CueStackPanel } from './CueStackPanel'

afterEach(cleanup)

const noop = () => {}
const base: ComponentProps<typeof CueStackPanel> = {
  rows: [],
  anchorByEntry: new Map(),
  anchorHintByEntry: new Map(),
  followByEntry: new Map(),
  entryRowByEntry: new Map(),
  statusOf: () => 'standby',
  warningsByEntry: new Map(),
  warnings: [],
  showWarnings: false,
  locked: true,
  placingEntryId: null,
  isExpanded: () => false,
  onToggleExpanded: noop,
  modeOf: () => null,
  onCueModeChange: noop,
  activeStackId: null,
  onCueClick: noop,
  onRemoveAnchor: noop,
  onWarningClick: noop,
  onSetStandby: noop,
  onEditCue: noop,
  onRenameCue: noop,
  onRenumberCue: noop,
  onRenoteCue: noop,
  goDisabled: false,
  showActive: true,
  stackName: 'Act 1',
  projectId: 1,
  coverPages: 0,
}

/** The rail's *On GO* line (scenery-programmer plan D14): what the next GO moves, at the top. */
describe('CueStackPanel · On GO', () => {
  it('names the next GO and its moves at the top of the rail', () => {
    render(<CueStackPanel {...base} onGo={{ cue: 'Q2', moves: 'Moon in 4 s · House tabs close' }} />)
    const line = document.querySelector('[data-on-go]')!
    expect(line).toHaveTextContent('On GO · Q2')
    expect(line).toHaveTextContent('Moon in 4 s · House tabs close')
  })

  it('is not drawn when nothing moves', () => {
    render(<CueStackPanel {...base} onGo={{ cue: 'Q2', moves: '' }} />)
    expect(document.querySelector('[data-on-go]')).toBeNull()
    cleanup()
    render(<CueStackPanel {...base} onGo={null} />)
    expect(document.querySelector('[data-on-go]')).toBeNull()
  })

  it('heads the drawer too, under its own header', () => {
    render(<CueStackPanel {...base} inDrawer onClose={noop} onGo={{ cue: 'Q2', moves: 'Moon in' }} />)
    expect(screen.getByText('Moon in')).toBeInTheDocument()
  })
})

describe('CueStackPanel · follow sequences', () => {
  const cue = (entryId: number, label: string) => ({
    type: 'cue' as const,
    cue: { entryId, cueId: entryId, label, name: label, fadeMs: null, fadeCurve: 'LINEAR', stackId: 1, stackName: 'Act 1' },
  })

  it('hangs a follower off the rail, in order, and leaves its head on the rail edge', () => {
    render(
      <CueStackPanel
        {...base}
        rows={[cue(1, 'Q1'), cue(2, 'Q2')]}
        followByEntry={new Map([[2, { headEntryId: 1, afterLabel: 'Q1', delayMs: 2000 }]])}
      />,
    )
    const entries = [...document.querySelectorAll('[data-rail-entry]')].map((e) => e.getAttribute('data-rail-entry'))
    expect(entries).toEqual(['1', '2'])
    const tail = document.querySelector('[data-follow-tail]')!
    expect(tail.querySelector('[data-rail-entry="2"]')).not.toBeNull()
    expect(tail.querySelector('[data-rail-entry="1"]')).toBeNull()
  })
})

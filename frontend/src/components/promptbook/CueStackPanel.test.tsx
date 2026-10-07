// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'

vi.mock('@/components/SpeedMasters', () => ({ SpeedMastersChip: () => null }))

import { CueStackPanel } from './CueStackPanel'

afterEach(cleanup)

const noop = () => {}
const base: ComponentProps<typeof CueStackPanel> = {
  rows: [],
  anchorByCue: new Map(),
  anchorHintByCue: new Map(),
  cueEntryByCue: new Map(),
  statusOf: () => 'standby',
  warningsByCue: new Map(),
  warnings: [],
  showWarnings: false,
  locked: true,
  placingCueId: null,
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
  dbo: false,
  onDbo: noop,
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

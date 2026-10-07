// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PromptBookToolbar } from './PromptBookToolbar'

const base = {
  scriptFileName: 'script.pdf',
  canUndo: false,
  onUndo: () => {},
  coverPages: 0,
  pageCount: 10,
  onCoverPagesChange: () => {},
  activeLabel: null,
  onJumpToLive: () => {},
  warningCount: 0,
  onToggleWarnings: () => {},
}

/**
 * This bar used to own the lock chrome: the toggle, the pulsing EDITING badge and an amber wash for
 * "you unlocked a running show". All of it moved in session 2b — the control and badge into
 * `ShowLockControl`, the wash into `ShowHeader`'s `unlockedWarning` — so the signal sits in the same
 * place on Show as on the Prompt Book instead of in this page's own chrome. What is left here is the
 * document chrome, plus the affordances the bar genuinely owns.
 */
describe('PromptBookToolbar', () => {
  it('draws no lock control, in any state', () => {
    render(<PromptBookToolbar {...base} locked />)
    expect(screen.queryByRole('button', { name: /lock/i })).toBeNull()

    render(<PromptBookToolbar {...base} locked={false} />)
    expect(screen.queryByRole('button', { name: /lock/i })).toBeNull()
  })

  it('washes amber when told the show is unlocked, and not on its own account', () => {
    // The flag comes from the page, not from `locked`: a *stopped* show is unlocked too and
    // warrants no warning. Every bar in the band takes the same flag, or the result is a stripe of
    // amber, a stripe of standard, a stripe of amber.
    const unlockedStopped = render(<PromptBookToolbar {...base} locked={false} />)
    expect(unlockedStopped.container.firstElementChild!.className).not.toContain('amber')
    unlockedStopped.unmount()

    const running = render(<PromptBookToolbar {...base} locked={false} unlockedWarning />)
    expect(running.container.firstElementChild!.className).toContain('bg-amber-400/15')
  })

  it('offers the cover-page control only while unlocked', () => {
    // One of the affordances this bar does own — it shifts every cue's page label, so it is an edit.
    const { unmount } = render(<PromptBookToolbar {...base} locked />)
    expect(screen.queryByLabelText('More front-matter pages')).toBeNull()
    unmount()

    render(<PromptBookToolbar {...base} locked={false} />)
    expect(screen.getByLabelText('More front-matter pages')).toBeTruthy()
  })

  it('offers Undo only while unlocked and there is something to undo', () => {
    const { unmount } = render(<PromptBookToolbar {...base} locked canUndo />)
    expect(screen.queryByText(/Undo/)).toBeNull()
    unmount()

    render(<PromptBookToolbar {...base} locked={false} canUndo />)
    expect(screen.getByText(/Undo/)).toBeTruthy()
  })

  it('in drawer mode, the Cues toggle carries a scenery dot while the next GO moves something', () => {
    // Below 1040px the rail — and its On GO line — is a drawer, shut most of the time; the dot is
    // the line's stand-in on the toggle (scenery-programmer plan D14).
    const moving = render(<PromptBookToolbar {...base} locked onOpenCues={() => {}} onGoMoves="House tabs close 4 s" />)
    const toggle = screen.getByRole('button', { name: /Cues/ })
    expect(toggle.querySelector('[data-on-go-dot]')).not.toBeNull()
    expect(toggle.title).toBe('On GO: House tabs close 4 s')
    moving.unmount()

    const still = render(<PromptBookToolbar {...base} locked onOpenCues={() => {}} onGoMoves={null} />)
    expect(screen.getByRole('button', { name: /Cues/ }).querySelector('[data-on-go-dot]')).toBeNull()
    still.unmount()

    // Wide, the rail is docked and shows the line itself: no toggle, so no dot.
    render(<PromptBookToolbar {...base} locked onGoMoves="House tabs close 4 s" />)
    expect(screen.queryByRole('button', { name: /Cues/ })).toBeNull()
    expect(document.querySelector('[data-on-go-dot]')).toBeNull()
  })
})

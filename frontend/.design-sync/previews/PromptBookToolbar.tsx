import { useState } from 'react'
import { PromptBookToolbar } from 'lighting-desk-ui'

const noop = () => {}

// Locked while the show runs: the live-cue chip and the desync warning count
// are the only controls.
export const LockedRunning = () => (
  <PromptBookToolbar
    scriptFileName="Hamlet — Act 1 (rehearsal draft).pdf"
    locked
    canUndo={false}
    onUndo={noop}
    coverPages={2}
    pageCount={48}
    onCoverPagesChange={noop}
    activeLabel="Q12"
    onJumpToLive={noop}
    warningCount={2}
    onToggleWarnings={noop}
  />
)

// Unlocked on a stopped show: ordinary chrome, front-matter stepper and Undo.
export const UnlockedStopped = () => {
  const [cover, setCover] = useState(2)
  return (
    <PromptBookToolbar
      scriptFileName="Hamlet — Act 1 (rehearsal draft).pdf"
      locked={false}
      canUndo
      onUndo={noop}
      coverPages={cover}
      pageCount={48}
      onCoverPagesChange={setCover}
      activeLabel={null as unknown as string}
      onJumpToLive={noop}
      warningCount={0}
      onToggleWarnings={noop}
    />
  )
}

// Unlocked mid-show: the amber wash shared with the header and show bar.
export const UnlockedRunningWarning = () => (
  <PromptBookToolbar
    scriptFileName="Hamlet — Act 1 (rehearsal draft).pdf"
    locked={false}
    unlockedWarning
    canUndo
    onUndo={noop}
    coverPages={0}
    pageCount={48}
    onCoverPagesChange={noop}
    activeLabel="Q7"
    onJumpToLive={noop}
    warningCount={1}
    onToggleWarnings={noop}
  />
)

// Narrow layout: the cue rail is a drawer, opened from a Cues button.
export const NarrowWithCuesButton = () => (
  <PromptBookToolbar
    scriptFileName="Act 2.pdf"
    locked
    canUndo={false}
    onUndo={noop}
    coverPages={1}
    pageCount={30}
    onCoverPagesChange={noop}
    activeLabel="Q3"
    onJumpToLive={noop}
    warningCount={0}
    onToggleWarnings={noop}
    onOpenCues={noop}
  />
)

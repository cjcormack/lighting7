import { ShowLockControl } from 'lighting-desk-ui'

const noop = () => {}

/** The quiet default while a show runs: transport works, edits are refused. */
export const Locked = () => (
  <div className="flex items-center gap-3">
    <ShowLockControl locked onToggle={noop} countdownSecondsLeft={null} onStayUnlocked={noop} />
    <span className="text-xs text-muted-foreground">Act 1 — Opening · cue 12 on stage</span>
  </div>
)

/** Unlocked mid-show — the amber pulse is the one state worth shouting about. */
export const Editing = () => (
  <div className="flex items-center gap-3">
    <ShowLockControl
      locked={false}
      onToggle={noop}
      countdownSecondsLeft={null}
      onStayUnlocked={noop}
    />
    <span className="text-xs text-muted-foreground">A stray click can change the show</span>
  </div>
)

/** Ten seconds' warning before the desk re-locks itself, with a way to refuse. */
export const Countdown = () => (
  <ShowLockControl
    locked={false}
    onToggle={noop}
    countdownSecondsLeft={7}
    onStayUnlocked={noop}
  />
)

/** The backend will not accept an edit at all — shown but inert, so it can say why. */
export const Disabled = () => (
  <div className="flex items-center gap-3">
    <ShowLockControl
      locked
      onToggle={noop}
      countdownSecondsLeft={null}
      onStayUnlocked={noop}
      disabled
    />
    <span className="text-xs text-muted-foreground">Not the current project</span>
  </div>
)

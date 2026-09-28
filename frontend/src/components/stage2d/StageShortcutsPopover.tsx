import { Keyboard } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'

interface Shortcut {
  keys: string
  what: string
}

const SHORTCUTS: Shortcut[] = [
  { keys: '↑ ↓ ← →', what: 'Nudge selection by the grid step' },
  { keys: '⇧ + arrows', what: 'Nudge ten steps' },
  { keys: '⇧ / ⌘ + click', what: 'Add to / toggle selection' },
  { keys: '⇧ / ⌘ + drag', what: 'Marquee-select fixtures' },
  { keys: '⌫', what: 'Remove selection from stage (keeps the patch)' },
  { keys: '⌘ D', what: 'Duplicate the selected region or rigging' },
  { keys: '⇧ (held)', what: 'Place off-grid — suspends snapping' },
  { keys: '⌥ (held)', what: 'Flip the 3D gizmo between move and rotate' },
  { keys: 'Esc', what: 'Cancel placement' },
]

/**
 * Discoverability for the editor's keyboard shortcuts.
 *
 * Worth its own control because none of these were findable before: ⌘D to
 * duplicate has existed for a while with no tooltip, no menu and no
 * documentation, so in practice it may as well not have existed. A shortcut
 * without an affordance isn't a feature.
 */
export function StageShortcutsPopover() {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button size="icon" variant="outline" className="size-8" aria-label="Keyboard shortcuts">
          <Keyboard className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80">
        <h3 className="mb-2 text-sm font-semibold">Keyboard shortcuts</h3>
        <dl className="space-y-1.5">
          {SHORTCUTS.map((s) => (
            <div key={s.keys} className="flex gap-3 text-xs">
              <dt className="w-24 shrink-0 font-mono text-muted-foreground">{s.keys}</dt>
              <dd>{s.what}</dd>
            </div>
          ))}
        </dl>
      </PopoverContent>
    </Popover>
  )
}

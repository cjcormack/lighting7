import { useRef, useState } from 'react'
import { Download, Layers, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ApplyPicker } from '@/components/press/ApplyPicker'
import type { PressPair } from '@/hooks/usePress'
import type { PaletteFit } from '@/lib/paletteOffer'
import { useCommandDoor } from './commandDoors'

/** A Look lands whole: its press names no targets and no mask (absolute-looks plan D1). */
const WHOLE: PressPair = { targets: [], families: null }
const EVERY_FIT: PaletteFit = { families: null, emitters: null }

/**
 * Row A's **+ Look** and **Include…** (programmer-toolbar plan D11, D12), on every tab. *+ Look* is
 * the one picker on its Looks (`ApplyPicker`, `library="looks"`), pressed whole: the lit ring, a tap
 * a layer, ⌥-click its values, and it stays open while pressed. Below 600px of row A the two are one
 * **+**, whose picker has *Include a cue or Look…* at its top, so the verbs keep their phone width.
 * Include is ⌘K's *Include a cue or Look…* too (D13); *Add a Look…* is `ProgrammerLookDoor`'s.
 */
export function AddLookAndInclude({
  projectId,
  onInclude,
  lookWordClass,
  includeWordClass,
}: {
  projectId: number
  onInclude: () => void
  /** When + Look shows its word. */
  lookWordClass: string
  /** When Include shows its word. */
  includeWordClass: string
}) {
  const [opened, setOpened] = useState<'look' | 'plus' | null>(null)
  const [mounted, setMounted] = useState(false)
  // The pair's box, drawn at every width, so a row crossing 600 under an open picker keeps its anchor.
  const anchor = useRef<HTMLSpanElement>(null)
  useCommandDoor('include', () => [
    {
      id: 'programmer.include',
      group: 'programmer',
      label: 'Include a cue or Look…',
      hint: 'replaces the source',
      keywords: ['include', 'load', 'cue', 'look'],
      canAct: true,
      run: onInclude,
    },
  ])
  const toggle = (door: 'look' | 'plus') => () => {
    setMounted(true)
    setOpened((was) => (was === door ? null : door))
  }
  return (
    <span ref={anchor} className="inline-flex shrink-0 items-center gap-2">
      <Button
        variant="outline"
        size="sm"
        aria-label="Add a Look"
        aria-expanded={opened === 'look'}
        title="A Look, landed whole — no selection, no mask"
        onClick={toggle('look')}
        className="hidden @[600px]:inline-flex"
      >
        <Layers className="size-3.5" />
        <span className={lookWordClass}>Look</span>
      </Button>
      <Button
        variant="outline"
        size="sm"
        onClick={onInclude}
        title="Load"
        aria-label="Include…"
        className="hidden @[600px]:inline-flex"
      >
        <Download className="size-3.5" />
        <span className={includeWordClass}>Include…</span>
      </Button>
      <Button
        variant="outline"
        size="sm"
        aria-label="Include, or add a Look"
        aria-expanded={opened === 'plus'}
        title="Include a cue or Look, or add a Look"
        onClick={toggle('plus')}
        className="@[600px]:hidden"
      >
        <Plus className="size-3.5" />
      </Button>
      {mounted && (
        <ApplyPicker
          projectId={projectId}
          open={opened != null}
          onOpenChange={(next) => !next && setOpened(null)}
          anchorRef={anchor}
          library="looks"
          pair={WHOLE}
          fit={EVERY_FIT}
          lead={
            opened === 'plus' ? (
              <button
                type="button"
                data-include-from-look-picker
                onClick={() => {
                  setOpened(null)
                  onInclude()
                }}
                className="flex min-h-11 flex-none items-center gap-2 border-b px-4 text-left hover:bg-accent"
              >
                <Download className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1 truncate text-sm font-medium">Include a cue or Look…</span>
                <span className="shrink-0 text-[11px] text-muted-foreground">replaces the source</span>
              </button>
            ) : null
          }
        />
      )}
    </span>
  )
}

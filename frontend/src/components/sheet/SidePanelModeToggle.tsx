import { Columns2, SquareStack } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { toggleSidePanelMode, useSidePanelMode } from '@/lib/sidePanelMode'
import { SIDE_PANEL_HEADER_BUTTON_CLASS } from './sidePanel'

/**
 * **Beside the content, or over it** — the one control for `lib/sidePanelMode.ts`, mounted in the
 * header of both docked side panels (the programmer's rail and the busk view's side sheet).
 *
 * One component rather than one per surface, because the two panels are deliberately one
 * instrument and this is the setting most likely to drift into two near-identical buttons that
 * disagree about their glyph, their wording or which way round the modes read.
 *
 * `Columns2` for push and `SquareStack` for overlay: the glyph shows **what the panel is now**,
 * and the title says what pressing it will do. A glyph showing the *destination* is the other
 * convention and either is defensible, but this one matches the rail's own chevrons, which point
 * where the panel will go while naming the state they leave.
 *
 * It sits only in the header, never on the folded strip: the mode is about how the panel occupies
 * the page, which is not a question worth answering while looking at a 40px column of glyphs, and
 * the strip's four cells are already the two counts, the chevron and the add menu.
 */
export function SidePanelModeToggle({ className }: { className?: string }) {
  const mode = useSidePanelMode()
  const overlay = mode === 'overlay'
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggleSidePanelMode}
      aria-label={overlay ? 'Show the panel beside the content' : 'Show the panel over the content'}
      aria-pressed={overlay}
      title={
        overlay
          ? 'Over the content — press to put it beside the content instead'
          : 'Beside the content — press to float it over the content instead'
      }
      className={cn(SIDE_PANEL_HEADER_BUTTON_CLASS, className)}
    >
      {overlay ? <SquareStack className="size-3.5" /> : <Columns2 className="size-3.5" />}
    </Button>
  )
}

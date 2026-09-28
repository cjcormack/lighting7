import { cn } from '@/lib/utils'
import { useImmersive } from '@/lib/immersive'
import { CHROME_ROW_CLASS } from './sheet/sheetFrame'
import { ImmersiveToggle } from './ImmersiveToggle'

/**
 * The way back for a live route that is showing no `ShowHeader` (busk-chrome plan D11).
 *
 * `Layout` hides the app on `immersive && isLiveViewPath(pathname)` — the path, because that is
 * the one boolean D7 asks for and it must hold before the route has anything to draw. But all
 * four live routes return early on their loading and not-found arms with no header, and an
 * immersive window on `/projects/999/busk` would then show an error card with **no control at
 * all**: no sidebar, no app header, no hamburger, no glyph. ⌘K still worked, which is why it
 * survived a desk with a keyboard; on a touch-only screen — the one immersive is aimed at — there
 * was no way out, and the fact is per-tab and not in the URL, so a reload did not clear it.
 *
 * So the arms draw the header's own glyph. Nothing while the app is drawn — the arms are as they
 * were — and while immersive either a 40px chrome row holding the glyph, right-aligned, or with
 * `bare` just the glyph for a host that already has a chrome row (`SheetPage.Header`). Not a
 * floating button: it is the header's row, minus the header (D11 holds).
 */
export function ImmersiveEscape({ bare = false }: { bare?: boolean }) {
  const immersive = useImmersive()
  if (!immersive) return null
  if (bare) {
    return (
      <span className="ml-auto" data-immersive-escape="">
        <ImmersiveToggle immersive />
      </span>
    )
  }
  return (
    <div className={cn(CHROME_ROW_CLASS, 'justify-end border-transparent')} data-immersive-escape="">
      <ImmersiveToggle immersive />
    </div>
  )
}

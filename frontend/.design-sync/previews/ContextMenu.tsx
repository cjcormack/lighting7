import {
  ContextMenu,
  ContextMenuCheckboxItem,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuTrigger,
} from 'lighting-desk-ui'
import { useEffect, useRef } from 'react'

// A context menu is the right-click menu on a cue slot. Radix has no
// `defaultOpen` for it — the menu opens from a `contextmenu` event on the
// trigger — so the cell dispatches one at a point inside the trigger region
// after mount, and the menu renders anchored to that point. Non-modal, so the
// rest of the card keeps its pointer events.
export const CueSlot = () => {
  const triggerRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = triggerRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    const evt = new MouseEvent('contextmenu', {
      bubbles: true,
      cancelable: true,
      button: 2,
      // Lower-right of the card, so the menu drops below it and the trigger
      // region stays legible beside the menu it opened.
      clientX: rect.right - 160,
      clientY: rect.bottom - 8,
    })
    // Dispatch after paint so Radix's listeners are attached.
    const id = window.setTimeout(() => el.dispatchEvent(evt), 50)
    return () => window.clearTimeout(id)
  }, [])

  return (
    <div className="h-[440px] w-full p-6">
      <ContextMenu modal={false}>
        <ContextMenuTrigger asChild>
          <div
            ref={triggerRef}
            className="bg-card text-card-foreground flex h-32 w-full max-w-md flex-col justify-between rounded-lg border p-4 shadow-sm"
          >
            <div className="flex items-baseline justify-between">
              <span className="text-muted-foreground text-xs font-medium tracking-wide uppercase">Cue 12</span>
              <span className="text-xs text-green-600">Live</span>
            </div>
            <div>
              <div className="text-sm font-medium">Band walk-on</div>
              <div className="text-muted-foreground text-xs">Fade 3 s · Act 1 — Opening · right-click for actions</div>
            </div>
          </div>
        </ContextMenuTrigger>
        <ContextMenuContent className="w-60">
          <ContextMenuLabel>Cue 12 · Band walk-on</ContextMenuLabel>
          <ContextMenuSeparator />
          <ContextMenuItem>
            Go to cue
            <ContextMenuShortcut>G</ContextMenuShortcut>
          </ContextMenuItem>
          <ContextMenuItem>
            Edit in Programmer
            <ContextMenuShortcut>E</ContextMenuShortcut>
          </ContextMenuItem>
          <ContextMenuItem>Cue properties…</ContextMenuItem>
          <ContextMenuSeparator />
          <ContextMenuCheckboxItem checked>Pinned to Busk</ContextMenuCheckboxItem>
          <ContextMenuCheckboxItem>Marker</ContextMenuCheckboxItem>
          <ContextMenuSeparator />
          <ContextMenuItem variant="destructive">
            Delete cue
            <ContextMenuShortcut>⌫</ContextMenuShortcut>
          </ContextMenuItem>
        </ContextMenuContent>
      </ContextMenu>
    </div>
  )
}

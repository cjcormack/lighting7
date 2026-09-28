import { useState } from 'react'
import { ChevronDown, ChevronUp, MapPin } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { FixturePatch } from '../../api/patchApi'

interface UnplacedTrayProps {
  unplaced: FixturePatch[]
  /** Patch keys currently armed for placement. */
  armedKeys: ReadonlySet<string>
  onToggle: (patch: FixturePatch, extend: boolean) => void
  onSelectAll: () => void
  onHangAll: () => void
  /** Whether a rigging exists to hang on. */
  canHang: boolean
}

/**
 * The fixtures that aren't on the plot yet.
 *
 * These are invisible on every stage surface — `worldPositionLighting` returns
 * null without both X and Y — so before this existed the only route onto the stage
 * was typing coordinates into a form, once per fixture. That is the bulk of what
 * made setting up a rig cumbersome.
 *
 * Sits along the **bottom** of the canvas rather than in the right-hand rail,
 * deliberately: the rail is already contested by the edit form, the picker and the
 * bulk panel, and the tray needs to be visible *at the same time* as the form for
 * click-to-place to make sense.
 *
 * Placement is click-to-arm then click-on-canvas, not HTML5 drag-and-drop, which
 * is unreliable as a drop target over SVG.
 */
export function UnplacedTray({
  unplaced,
  armedKeys,
  onToggle,
  onSelectAll,
  onHangAll,
  canHang,
}: UnplacedTrayProps) {
  const [collapsed, setCollapsed] = useState(false)

  if (unplaced.length === 0) return null

  return (
    <div className="border-t bg-background">
      <div className="flex items-center gap-2 px-4 py-2">
        <MapPin className="size-3.5 text-amber-500" />
        <span className="text-sm font-medium">Unplaced</span>
        <span className="rounded-full bg-amber-500/15 px-2 py-0.5 font-mono text-[11px] text-amber-600 dark:text-amber-400">
          {unplaced.length}
        </span>
        {armedKeys.size > 0 && (
          <span className="text-xs text-muted-foreground">
            {armedKeys.size} armed — click the stage to place
          </span>
        )}
        <div className="flex-1" />
        <Button size="sm" variant="ghost" className="h-7 px-2 text-xs" onClick={onSelectAll}>
          Select all
        </Button>
        {canHang && (
          <Button
            size="sm"
            variant="outline"
            className="h-7 px-2 text-xs"
            onClick={onHangAll}
            disabled={unplaced.length === 0}
          >
            Hang all on truss…
          </Button>
        )}
        <Button
          size="icon"
          variant="ghost"
          className="size-7"
          onClick={() => setCollapsed((v) => !v)}
          aria-label={collapsed ? 'Expand unplaced fixtures' : 'Collapse unplaced fixtures'}
        >
          {collapsed ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
        </Button>
      </div>

      {!collapsed && (
        <div className="flex max-h-24 flex-wrap gap-1.5 overflow-y-auto px-4 pb-3">
          {unplaced.map((patch) => {
            const armed = armedKeys.has(patch.key)
            return (
              <button
                key={patch.id}
                type="button"
                onClick={(e) => onToggle(patch, e.shiftKey || e.metaKey || e.ctrlKey)}
                title={`${patch.displayName || patch.key} · DMX ${patch.startChannel} on U${patch.universe}`}
                className={cn(
                  'rounded-full border px-2.5 py-1 text-xs transition-colors',
                  armed
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-border bg-muted/40 hover:bg-muted',
                )}
              >
                {patch.displayName || patch.key}
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

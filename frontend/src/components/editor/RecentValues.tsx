import { computeCombinedCss } from '@/lib/colourMath'
import { cn } from '@/lib/utils'
import { useRecentValues } from '@/store/recentValues'
import { EditorLabel } from './EditorLabel'
import { parseRecentColour, RECENT_COLOUR, recentValueLabel } from './recentValueSpelling'

/** The most a band draws: one row, never wrapping. */
export const RECENT_BAND_MAX = 8

/**
 * The editor kit's **Recent** band (programmer-fixes plan D18): the live project's last values for
 * one list, newest first, kept by the desk for every window. Swatches for a colour (the hex on the
 * hover), chips for the rest. A tap hands the value to the host, which sets it as if it had been
 * typed. It draws nothing while its list is empty, the desk sends none, or [projectId] is not the
 * live project.
 *
 * Its store hook runs here, so a list's change re-renders the band alone — mount it in an editor's
 * popover, never in a row.
 */
export function RecentValues({
  listKey,
  projectId,
  onPick,
  readOnly = false,
  className,
}: {
  listKey: string
  /** The project the editor edits; absent, the live one. */
  projectId?: number
  /** A tap: the value in its recorded spelling. */
  onPick: (value: string) => void
  /** Drawn, but out of reach: a tap is a write. */
  readOnly?: boolean
  className?: string
}) {
  const values = useRecentValues(listKey, projectId)
  const shown = values.flatMap((value) => {
    const label = recentValueLabel(listKey, value)
    return label == null ? [] : [{ value, label }]
  }).slice(0, RECENT_BAND_MAX)
  if (shown.length === 0) return null
  return (
    <div
      data-recent-values={listKey}
      inert={readOnly || undefined}
      className={cn('flex min-w-0 items-center gap-2', readOnly && 'opacity-50', className)}
    >
      <EditorLabel className="shrink-0">Recent</EditorLabel>
      <div className="flex min-w-0 flex-nowrap items-center gap-1.5 overflow-hidden">
        {shown.map(({ value, label }) =>
          listKey === RECENT_COLOUR ? (
            <button
              key={value}
              type="button"
              aria-label={`Recent ${label}`}
              title={label}
              onClick={() => onPick(value)}
              className="size-5 shrink-0 rounded-full border border-border focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              style={{ background: swatchCss(value) }}
            />
          ) : (
            <button
              key={value}
              type="button"
              aria-label={`Recent ${label}`}
              onClick={() => onPick(value)}
              className="h-6 shrink-0 rounded-md border border-border px-1.5 text-[11px] whitespace-nowrap tabular-nums hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              {label}
            </button>
          ),
        )}
      </div>
    </div>
  )
}

function swatchCss(value: string): string {
  const colour = parseRecentColour(value)
  if (colour == null) return 'transparent'
  return computeCombinedCss(colour.r, colour.g, colour.b, colour.w ?? 0, colour.a ?? 0, colour.uv ?? 0)
}

import type { ReactNode } from 'react'
import { EditorLabel } from '@/components/editor/EditorLabel'
import type { VenueShow } from '@/api/venueApi'
import type { PinChip } from '@/lib/projectSwitch'
import { cn } from '@/lib/utils'

/** A show as the venue's home draws it: whether it is on the rig, and how its pin stands. */
export interface VenueShowListRow extends VenueShow {
  isCurrent?: boolean
  /** The pin chip's status — `held`, `v8 published`, `not on this desk` — or null when up to date. */
  status?: string | null
  tone?: PinChip['tone']
}

/** A status in the behind banner's colours: held and missing red, behind on output amber, drawing only neutral. */
const STATUS_CLASS: Record<PinChip['tone'], string> = {
  held: 'border-red-600/40 bg-red-500/10 text-red-700 dark:text-red-300',
  missing: 'border-red-600/40 bg-red-500/10 text-red-700 dark:text-red-300',
  output: 'border-amber-500/50 bg-amber-500/10 text-amber-700 dark:text-amber-300',
  drawing: 'border-border bg-muted text-muted-foreground',
  none: '',
}

/**
 * The shows on a venue, each with the version it is pinned to (venue plan session 3) — what History
 * and the Publish sheet name as the shows a publish reaches, and what the venue's home lists
 * (navigation plan §5.2). A publish changes none of them: a show moves to a new version only through
 * Update (session 6), so [next] marks the ones that will be behind once it lands.
 *
 * On the home a row also says whether the show is on the rig and how far behind it is, and carries
 * its [actions] — *Open* and *Make current*, the show's own from `projectActions`.
 */
export function VenueShowsList<Row extends VenueShowListRow>({
  shows,
  next,
  actions,
  loading = false,
  className,
}: {
  shows: readonly Row[]
  /** The version about to be published, on the Publish sheet: each show reads as one behind it. */
  next?: number
  /** A row's buttons, on the home. */
  actions?: (show: Row) => ReactNode
  /** The list is still being read: say so rather than that the venue has no shows. */
  loading?: boolean
  className?: string
}) {
  return (
    <section className={cn('space-y-1.5', className)} aria-label="Shows on this venue" data-venue-shows="">
      <div className="flex items-center gap-1.5">
        <EditorLabel>Shows on this venue</EditorLabel>
        {!loading && (
          <span className="rounded bg-muted px-1.5 text-[10px] font-semibold tabular-nums">{shows.length}</span>
        )}
      </div>
      {loading ? (
        <p className="text-sm text-muted-foreground">Reading…</p>
      ) : shows.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {next != null ? 'No show is on this venue yet, so publishing changes no show.' : 'No show is on this venue yet.'}
        </p>
      ) : (
        <>
          <ul className="space-y-1">
            {shows.map((show) => (
              <li
                key={show.id}
                data-venue-show={show.id}
                className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm"
              >
                <span className="flex min-w-0 flex-[1_1_10rem] items-center gap-2">
                  {show.isCurrent && (
                    <span aria-hidden className="size-2 shrink-0 rounded-full bg-green-500" title="On the rig" />
                  )}
                  <span className="min-w-0 truncate font-medium">{show.name}</span>
                  {show.isCurrent && <span className="sr-only">(on the rig)</span>}
                  <span className="shrink-0 font-mono text-xs tabular-nums text-muted-foreground">on v{show.version}</span>
                  {show.status && show.tone && show.tone !== 'none' && (
                    <span
                      data-venue-show-status={show.tone}
                      className={cn('shrink-0 rounded border px-1.5 text-[11px] font-medium', STATUS_CLASS[show.tone])}
                    >
                      {show.status}
                    </span>
                  )}
                </span>
                {actions && <span className="flex shrink-0 items-center gap-1.5">{actions(show)}</span>}
              </li>
            ))}
          </ul>
          {next != null && (
            <p className="text-xs text-muted-foreground">
              Publishing v{next} changes none of them: each stays on its version until it is updated.
            </p>
          )}
        </>
      )}
    </section>
  )
}

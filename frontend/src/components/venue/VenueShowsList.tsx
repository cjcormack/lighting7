import { EditorLabel } from '@/components/editor/EditorLabel'
import type { VenueShow } from '@/api/venueApi'

/**
 * The shows on a venue, each with the version it is pinned to (venue plan session 3) — what History
 * and the Publish sheet name as the shows a publish reaches. A publish changes none of them: a show
 * moves to a new version only through Update (session 6), so [installed] marks the ones that will be
 * behind once it lands.
 */
export function VenueShowsList({
  shows,
  next,
}: {
  shows: readonly VenueShow[]
  /** The version about to be published, on the Publish sheet: each show reads as one behind it. */
  next?: number
}) {
  return (
    <section className="space-y-1.5" aria-label="Shows on this venue">
      <div className="flex items-center gap-1.5">
        <EditorLabel>Shows on this venue</EditorLabel>
        <span className="rounded bg-muted px-1.5 text-[10px] font-semibold tabular-nums">{shows.length}</span>
      </div>
      {shows.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          {next != null ? 'No show is on this venue yet, so publishing changes no show.' : 'No show is on this venue yet.'}
        </p>
      ) : (
        <>
          <ul className="space-y-1">
            {shows.map((show) => (
              <li key={show.id} className="flex items-center gap-2 text-sm">
                <span className="min-w-0 flex-1 truncate font-medium">{show.name}</span>
                <span className="font-mono text-xs tabular-nums text-muted-foreground">on v{show.version}</span>
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

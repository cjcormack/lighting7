import { Crosshair, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useFixtureTypeListQuery } from '@/store/fixtures'
import { isVenueOwned, type FixturePatch } from '@/api/patchApi'
import { useStoredLocateActions } from '@/hooks/useStoredLocateActions'
import { locateBytesLabel, locateUnitLabel, locateValueLabel } from '@/lib/storedLocate'

interface LocateBoxProps {
  patch: FixturePatch
  /** The project the patch sheet is open on — the gestures act only when it is the current one. */
  projectId: number
}

/**
 * The patch sheet's **Locate** box (venue-visit plan D6, D17): the pan and tilt Locate points each
 * of the unit's moving parts at — the fixture's own, each head's — on the centre-relative scale, with
 * *Set from current* and *Clear*. Unlike the boxes around it, nothing here waits for Save: setting a
 * locate reads what the desk is sending now, so each button is one call to the desk, which writes the
 * patch (or, for a venue's fixture in a show, the show's Focus) and answers. Drawn only for a type
 * that pans and tilts, or a patch that still holds a locate.
 */
export function LocateBox({ patch, projectId }: LocateBoxProps) {
  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const actions = useStoredLocateActions()
  const entries = patch.locate ?? []
  const type = fixtureTypes?.find((t) => t.typeKey === patch.fixtureTypeKey)
  const moves = type?.capabilities.includes('position') ?? false
  if (!moves && entries.length === 0) return null

  const live = actions.projectId === projectId
  const reason = live ? undefined : 'Open this project first — Set from current reads what the desk is sending'
  const target = [{ type: 'fixture' as const, key: patch.key }]
  const venueInShow = isVenueOwned(patch)

  return (
    <div className="space-y-2.5 rounded-md border border-border p-3" aria-label="Locate">
      <p className="text-xs font-medium text-muted-foreground">Locate</p>
      {entries.length === 0 ? (
        <p className="text-sm text-muted-foreground">Centre of travel — every unit locates to its middle.</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {entries.map((entry) => (
            <li key={entry.head ?? 'fixture'} className="flex items-baseline gap-2" title={locateBytesLabel(entry)}>
              <span className="w-16 shrink-0 text-muted-foreground">{locateUnitLabel(entry)}</span>
              <span className="tabular-nums">{locateValueLabel(entry)}</span>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={!live || actions.busy}
          title={reason ?? 'Store the pan and tilt the desk is sending this fixture now as where Locate points it'}
          onClick={() => actions.storeFromCurrent(target)}
        >
          <Crosshair className="mr-1.5 size-3.5" />
          Set from current
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!live || actions.busy || entries.length === 0}
          title={
            reason ??
            (entries.length === 0
              ? 'Nothing stored — Locate already centres it'
              : venueInShow
                ? 'Clear this show’s locate — the venue’s stays as it is'
                : 'Locate centres it again')
          }
          onClick={() => actions.clearLocate(target)}
        >
          <X className="mr-1.5 size-3.5" />
          Clear
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        Pan and tilt only: Locate still opens the beam white at full.
        {venueInShow && ' A locate set here is this show’s own Focus; the venue’s is unchanged.'}
      </p>
    </div>
  )
}

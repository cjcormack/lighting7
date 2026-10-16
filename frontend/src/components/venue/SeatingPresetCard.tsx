import { useState } from 'react'
import { toast } from 'sonner'
import { XCircle } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { formatError } from '@/lib/formatError'
import { errorCode } from '@/lib/venueSyncRefusal'
import { useReplaceSeatingFromPresetMutation, useSeatingPresetsQuery } from '@/store/venues'
import type { ProjectVenue } from '@/api/projectApi'

/** The desk's code for seat views a replace would leave without a seat. */
const VIEWS_STRANDED = 'STAGE_ELEMENT_IN_USE'

/**
 * A show's stalls (venue plan D6, session 7; `Projects.dc.html`'s callout 4): the seating it owns —
 * a copy of one of its venue's Preset blocks — and *Replace from preset…*, which swaps it for a fresh
 * copy of any preset at the show's pinned version. The venue's Fixed blocks (the balcony) come with
 * the venue and are not listed. Seat views in the stalls move to the new copy; one whose seat the new
 * preset lacks is named, and the replace goes ahead only when asked again. Both roles: it changes only
 * the show. Drawn on Project Settings' Regions tab, for a show on a venue only.
 */
export function SeatingPresetCard({ projectId, venue }: { projectId: number; venue: ProjectVenue }) {
  const { data, isLoading: loading, error: readError } = useSeatingPresetsQuery(projectId)
  const [replace, { isLoading, error, reset }] = useReplaceSeatingFromPresetMutation()
  const presets = data?.presets ?? []
  const current = data?.current ?? []
  const [picked, setChoice] = useState('')
  const [confirming, setConfirming] = useState(false)
  const stranded = errorCode(error) === VIEWS_STRANDED

  // The operator's pick while it is still offered; else the first preset the show is not already
  // seated from, so the select never opens on the stalls it has.
  const held = new Set(current.filter((c) => c.fromPreset).map((c) => c.uuid))
  const choice = presets.some((p) => p.uuid === picked)
    ? picked
    : (presets.find((p) => !held.has(p.uuid))?.uuid ?? presets[0]?.uuid ?? '')
  const chosen = presets.find((p) => p.uuid === choice)
  const close = (next: boolean) => {
    if (!next) reset()
    setConfirming(next)
  }
  const run = async (force: boolean) => {
    if (!chosen) return
    try {
      const result = await replace({ projectId, elementUuid: chosen.uuid, force }).unwrap()
      const lost = result.viewsWithoutSeat ?? []
      toast.success(
        `Seating is ${result.seating.name}` + (lost.length > 0 ? ` — ${lost.join(', ')} ha${lost.length === 1 ? 's' : 've'} no seat in it` : ''),
      )
      close(false)
    } catch {
      // A stranded seat view asks again below; anything else is drawn there too.
    }
  }

  return (
    <Card className="space-y-4 p-4">
      <div>
        <h2 className="text-sm font-semibold">Seating</h2>
        <p className="text-xs text-muted-foreground">
          The stalls are this show&apos;s own copy of a {venue.name ?? 'venue'} preset: moving chairs never changes the venue or
          another show. Fixed seating (a balcony) comes with the venue.
        </p>
      </div>
      {readError ? (
        // A pin this desk lacks (`VENUE_MISSING`) or any other refusal: said as the desk says it, never
        // as "no seating", which would be untrue.
        <Alert variant="destructive">
          <XCircle className="size-4" />
          <AlertDescription>{formatError(readError)}</AlertDescription>
        </Alert>
      ) : loading || data == null ? (
        <p className="text-sm text-muted-foreground">Reading the seating…</p>
      ) : (
        <>
          <p className="text-sm" aria-label="This show's seating">
            {current.length === 0
              ? 'No seating of its own.'
              : current.map((c) => `${c.name} · ${c.seats} seats${c.fromPreset ? '' : ' (its own layout)'}`).join(' · ')}
          </p>
          {presets.length === 0 ? (
            <p className="text-xs text-muted-foreground">v{venue.version} has no seating presets.</p>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              {/* A floor so the select wraps above the button on a phone rather than shrinking to nothing. */}
              <div className="min-w-48 flex-1 space-y-1">
                <Label htmlFor="seating-preset">Preset at v{venue.version}</Label>
                <Select value={choice} onValueChange={setChoice}>
                  <SelectTrigger id="seating-preset" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {presets.map((p) => (
                      <SelectItem key={p.uuid} value={p.uuid}>
                        {p.name} · {p.seats} seats
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <Button variant="outline" disabled={!chosen} onClick={() => setConfirming(true)}>
                Replace from preset…
              </Button>
            </div>
          )}
        </>
      )}
      <AlertDialog open={confirming} onOpenChange={close}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Replace the seating with {chosen?.name}?</AlertDialogTitle>
            <AlertDialogDescription>
              {current.length > 0 ? `${current.map((c) => c.name).join(' and ')} go${current.length === 1 ? 'es' : ''}` : 'Nothing goes'}, and
              a fresh copy of the preset takes {current.length === 1 ? 'its' : 'their'} place. Seat views move to it; cues and Looks
              that show or hide the stalls keep doing so.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {error && (
            <Alert variant={stranded ? 'default' : 'destructive'}>
              <XCircle className="size-4" />
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isLoading}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={isLoading}
              onClick={(e) => {
                e.preventDefault()
                void run(stranded)
              }}
            >
              {isLoading ? 'Replacing…' : stranded ? 'Replace anyway' : 'Replace'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  )
}

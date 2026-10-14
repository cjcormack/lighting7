import type { ParkState } from '@/store/park'

/**
 * The words every park surface uses for whose a park is (venue parks): the Channels cards, the DMX
 * sheet, the fixture sheet's Park and the Unpark All confirm. One place, so the hover an operator
 * reads before a press and the confirm after it say the same thing.
 *
 * A park is the project's own (`SHOW`) or its venue's (`VENUE`, inherited from the pinned version).
 * On a show on a venue, unparking a venue park **releases** it for this show — the venue keeps it —
 * and unparking the show's park over a venue park brings the venue's back rather than unparking.
 */

/** `Parked at 200`, `Parked by the venue at 255`, `Parked at 200 · over the venue's 255`. */
export function parkLabel(park: Pick<ParkState, 'value' | 'owner' | 'venueValue'>): string {
  if (park.owner === 'VENUE') return `Parked by the venue at ${park.value}`
  if (park.venueValue != null) return `Parked at ${park.value} · over the venue's ${park.venueValue}`
  return `Parked at ${park.value}`
}

/** What unparking this channel does, as the control's label. */
export function unparkLabel(park: Pick<ParkState, 'owner' | 'venueValue'>): string {
  if (park.owner === 'VENUE') return 'Release for this show'
  if (park.venueValue != null) return `Back to the venue's park (${park.venueValue})`
  return 'Unpark'
}

/**
 * The confirm an unpark of several channels asks, naming what happens to the venue's parks among
 * them: `Unpark 4 channel(s) on Front wash?`, plus `2 are the venue's: this releases them for this
 * show — the venue keeps them.` and `1 goes back to the venue's park.`
 */
export function unparkConfirm(parks: readonly Pick<ParkState, 'owner' | 'venueValue'>[], where: string): string {
  const venue = parks.filter((p) => p.owner === 'VENUE').length
  const back = parks.filter((p) => p.owner === 'SHOW' && p.venueValue != null).length
  const lines = [`Unpark ${parks.length} channel(s)${where ? ` ${where}` : ''}?`]
  if (venue > 0) {
    lines.push(
      `${venue} ${venue === 1 ? "is the venue's" : "are the venue's"}: this releases ${venue === 1 ? 'it' : 'them'} for this show — the venue keeps ${venue === 1 ? 'it' : 'them'}.`,
    )
  }
  if (back > 0) lines.push(`${back} ${back === 1 ? 'goes' : 'go'} back to the venue's park.`)
  return lines.join('\n\n')
}

/** Whether a park is one the venue's park can be taken back on (the show's own over the venue's). */
export function overVenuePark(park: Pick<ParkState, 'owner' | 'venueValue'> | undefined): boolean {
  return park != null && park.owner === 'SHOW' && park.venueValue != null
}

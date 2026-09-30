import type { StageElementDto } from '../../../../api/stageElementApi'
import { seatList, seatingParams } from '../../../../lib/stageSeats'
import type { ElementBuild } from '../sceneParts'

/**
 * A `SEATING` block: its **seats**, and nothing else. The list is `lib/stageSeats.ts`'s `seatList`,
 * the one statement of where each seat is — the drawn seat, the seat *Sit in a seat…* picks and a
 * saved seat view's seat are the same computation. The renderer draws them instanced, one seat
 * shape turned with the block; they take light but do not stop a beam, so a wash on the stalls
 * lights every seat in it rather than the front row's backs.
 */
export function buildSeating(element: StageElementDto): ElementBuild {
  const params = seatingParams(element)
  if (params == null) return { parts: [], seats: [] }
  return { parts: [], seats: seatList(element, params) }
}

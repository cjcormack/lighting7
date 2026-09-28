import { useProgrammerSummaryQuery } from '@/store/programmer'

/**
 * Is the programmer blind — its values held back from the stage? The same field
 * `ProgrammerIndicator` reads, narrowed through `selectFromResult` to the one boolean so a reader
 * re-renders when blind flips and not on every programmer event (`ProgrammerSummary` moves on
 * every entry change, which at busking rate is constantly).
 *
 * A module of its own rather than a line in each reader, so the busk band's marks (`BlindPill`,
 * `BlindDot`) — which are mounted in surfaces whose tests render with no store — have one seam to
 * mock, and so the question "what does blind mean to the busk view" is answered once.
 */
export function useProgrammerBlind(): boolean {
  const { blind } = useProgrammerSummaryQuery(undefined, {
    selectFromResult: ({ data }) => ({ blind: data?.blind ?? false }),
  })
  return blind
}

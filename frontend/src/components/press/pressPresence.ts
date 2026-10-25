import type { CueTarget } from '@/api/cuesApi'
import { lookPresence, paletteLayerPresence } from '@/components/busking/lookPresence'
import type { FxPresence } from '@/components/busking/buskingTypes'
import type { PressInto } from '@/hooks/usePress'
import type { ProgrammerAppliedSource } from '@/store/programmer'
import { cuePresence, type PressCue } from './pressCue'

/**
 * A chip's or a pad's ring for wherever its press lands: none in a focused Look, which holds values
 * rather than layers; the cue's, by record, in the cue editor; else the programmer's resolved applied
 * state — a palette's over the pressed heads, a Look's whether a layer of it is on. One reading for every door, so a change to it reaches them all.
 */
export function pressPresence(
  into: PressInto,
  cue: PressCue | null,
  applied: readonly ProgrammerAppliedSource[] | undefined,
  targets: readonly CueTarget[],
  kind: 'PALETTE' | 'LOOK',
  id: number,
): FxPresence {
  if (into.kind === 'look') return 'none'
  if (into.kind === 'cue') return cue == null ? 'none' : cuePresence(cue, kind, id)
  return kind === 'PALETTE' ? paletteLayerPresence(applied ?? [], targets, id) : lookPresence(applied ?? [], id)
}

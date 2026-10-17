import type { CueTarget } from '@/api/cuesApi'
import { lookLayerPresence, templateLayerPresence } from '@/components/busking/lookPresence'
import type { EffectPresence } from '@/components/busking/buskingTypes'
import type { PressInto } from '@/hooks/usePress'
import type { ProgrammerAppliedSource } from '@/store/programmer'
import { cuePresence, type PressCue } from './pressCue'

/**
 * A chip's or a pad's ring for wherever its press lands: none in a focused Look, which holds values
 * rather than layers; the cue's, by record, in the cue editor; else the programmer's resolved applied
 * state over the pressed heads. One reading for every door, so a change to it reaches them all.
 */
export function pressPresence(
  into: PressInto,
  cue: PressCue | null,
  applied: readonly ProgrammerAppliedSource[] | undefined,
  targets: readonly CueTarget[],
  kind: 'TEMPLATE' | 'LOOK',
  id: number,
): EffectPresence {
  if (into.kind === 'look') return 'none'
  if (into.kind === 'cue') return cue == null ? 'none' : cuePresence(cue, kind, id)
  return kind === 'TEMPLATE' ? templateLayerPresence(applied ?? [], targets, id) : lookLayerPresence(applied ?? [], targets, id)
}

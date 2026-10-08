import { isHeldBack } from '@/lib/heldBack'
import type { ProgrammerKeyState } from '@/api/programmerWsApi'
import type { ActiveEffect } from '@/store/fixtureFx'

/**
 * Who is driving a sheet row (fixture-fx-sheets plan D4): the programmer grid's ownership
 * vocabulary — park, programmer, effect, cue, and nothing (*Base*) — with the name of the thing.
 */
export type RowSourceKind = 'parked' | 'programmer' | 'effect' | 'cue' | 'base'

export interface RowSource {
  kind: RowSourceKind
  /** The chip's words: *Programmer*, *Pulse · ¼ · M2*, *Q12 · Night*, *Parked*, *Base*. */
  label: string
  /** The effect on stage, for an `effect` row. */
  effect?: ActiveEffect
  /** The programmer holds an entry on one of the row's keys, by an owner `clearEntry` releases — what draws its ×. */
  holds: boolean
  /** That entry is an operator edit (filled chip) rather than only what Include brought (outline). */
  touched: boolean
  /** An effect under the row's winner is running but held back by the programmer — the amber dot. */
  heldBack: boolean
  /** The programmer is blind and holds this row: the value is staged, not on stage. */
  staged: boolean
}

export interface RowSourceInput {
  /** The row's head — the fixture, or one of its heads. */
  headKey: string
  /** The row's keys and what the programmer says about each, first key first. */
  states: readonly { key: string; state: ProgrammerKeyState }[]
  /** Every effect that can paint this head: on it, or on a group it is in. */
  effects: readonly ActiveEffect[]
  blind: boolean
  /** `Q12` for a cue id, when the cue is known. */
  cueLabel: (cueId: number) => string | undefined
  /** `½ · M2` — an effect's speed and master, as the sheet's tray and chips spell it. */
  effectDetail: (effect: ActiveEffect) => string
}

const RANK: Record<RowSourceKind, number> = { parked: 4, programmer: 3, effect: 2, cue: 1, base: 0 }

function kindOf(source: string | undefined): RowSourceKind {
  switch (source) {
    case 'PARKED':
      return 'parked'
    case 'PROGRAMMER':
      return 'programmer'
    case 'EFFECT':
      return 'effect'
    case 'CUE':
      return 'cue'
    default:
      return 'base'
  }
}

/**
 * One row's source, from the desk's per-key provenance (`provenanceState`, read through
 * `getKeyState`) joined with the active-effect list. Pure, so the five kinds are a table a test
 * can read.
 *
 * Over several keys (Position reads `position` and its two axes) the strongest wins, the way the
 * grid collapses a cell: a parked axis is the thing to know about first.
 */
export function rowSourceOf({ headKey, states, effects, blind, cueLabel, effectDetail }: RowSourceInput): RowSource {
  let best: { kind: RowSourceKind; state: ProgrammerKeyState } | null = null
  let holds = false
  let touched = false
  for (const { state } of states) {
    // A slot only a pressed layer holds is not the operator's to clear — `clearEntry` leaves layer
    // slots, so the × would do nothing (the layer is released from the rail or the pad).
    const owners = state.entry ? (state.entry.owners?.length ? state.entry.owners : [state.entry.owner]) : []
    if (owners.some((o) => o !== 'layers')) {
      holds = true
      if (state.entry?.touched) touched = true
    }
    const kind = kindOf(state.provenance?.source)
    if (best == null || RANK[kind] > RANK[best.kind]) best = { kind, state }
  }

  const keys = new Set(states.map((s) => s.key))
  const held = (head: string, property: string) =>
    head === headKey && keys.has(property) && states.some((s) => s.key === property && s.state.entry != null)
  const heldBack = effects.some((e) => keys.has(e.propertyName) && isHeldBack(e, headKey, held, blind))

  const kind = best?.kind ?? 'base'
  const provenance = best?.state.provenance
  let label: string
  let effect: ActiveEffect | undefined
  switch (kind) {
    case 'parked':
      label = 'Parked'
      break
    case 'programmer': {
      const entry = best?.state.entry
      const layerName = provenance?.layerSource?.name
      // A Look or template pressed in the programmer names itself; the operator's own hand is
      // *Programmer* (Layers board, the vocabulary).
      label = entry?.owner === 'layers' && layerName ? `${layerName} · pressed` : 'Programmer'
      break
    }
    case 'effect': {
      effect = provenance?.effectId != null ? effects.find((e) => e.id === provenance.effectId) : undefined
      label = effect ? [effect.effectType, effectDetail(effect)].filter(Boolean).join(' · ') : 'Effect'
      break
    }
    case 'cue': {
      const cue = provenance?.cueId != null ? cueLabel(provenance.cueId) : undefined
      const layer = provenance?.layerSource?.name
      label = [cue, layer].filter(Boolean).join(' · ') || 'Cue'
      break
    }
    default:
      label = 'Base'
  }

  return { kind, label, effect, holds, touched, heldBack, staged: blind && holds }
}

/**
 * Every effect targeted at one of [targetKeys], or at a group the fixture is in — the one filter
 * the rows and the tray share.
 *
 * A row passes its head and, for a head of a multi-head fixture, the fixture too: the desk expands
 * an effect on the fixture onto its heads (`FxEngine`'s element coverage) while the effect keeps the
 * fixture's key, so a head row must see it. The tray passes the fixture and every head. Groups list
 * fixtures, not heads, so pass the fixture's own groups.
 */
export function effectsReaching(
  effects: readonly ActiveEffect[] | undefined,
  targetKeys: readonly string[],
  groups: readonly string[],
): ActiveEffect[] {
  if (!effects) return []
  const keys = new Set(targetKeys)
  const inGroup = new Set(groups)
  return effects.filter((e) => (e.isGroupTarget ? inGroup.has(e.targetKey) : keys.has(e.targetKey)))
}

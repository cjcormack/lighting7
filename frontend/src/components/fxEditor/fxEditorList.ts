import type { CueTarget } from '@/api/cuesApi'
import type { ActiveFx } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import type { FxPreviewAnswer } from '@/store/fxPreview'
import { PALETTE_FAMILY_ORDER, familyForCategory, type AttributeFamily } from '@/lib/attributeFamily'
import { targetFamilies } from '@/components/fixtures-list/rowModel'
import { selectionUnits } from '@/lib/fxTargets'
import { fxPaintedHeads, isLocalFx } from '@/components/fixtures-list/cellFx'

/**
 * **The FX editor's list** (FX editor plan D3, §4.3), as a model: every FX reaching the selected
 * heads, in family lanes — Intensity, Colour, Position, Beam — each top first in the desk's apply
 * order. Pure; the list draws it.
 *
 * **The order is the desk's.** The engine folds a key's FX by `(priority, composeOrder, id)`, and
 * the band puts every Local FX above every programmer layer's FX above every cue's (D5). The running
 * list carries no priority, so the tiers are read from each FX's home and, within a tier, from the
 * preview's answer, whose `fx` is the apply order bottom first. An FX the answer has not reached yet
 * — one just started — goes on top of its tier, where a new Local FX lands; layer FX the answer
 * lacks go by their layer's place in the stack.
 */

export type FxCardKind = 'local' | 'layer' | 'cue' | 'other'

export interface FxCard {
  effect: ActiveFx
  kind: FxCardKind
  family: AttributeFamily
  /** The selected heads it paints, and the selected heads that could take its attribute. */
  reached: number
  of: number
}

export interface FxLane {
  family: AttributeFamily
  /** Top first. */
  cards: FxCard[]
}

/** The family's default attribute: one a card does not name, since it is the lane's own word. */
export const LANE_ATTRIBUTE: Readonly<Record<AttributeFamily, string | null>> = {
  INTENSITY: 'dimmer',
  COLOUR: 'rgbColour',
  POSITION: 'position',
  BEAM: null,
}

/** The tiers top first: Local over the programmer's layers over a cue's FX over anything else. */
const TIER: Readonly<Record<FxCardKind, number>> = { local: 3, layer: 2, cue: 1, other: 0 }

export function cardKindOf(effect: ActiveFx): FxCardKind {
  if (isLocalFx(effect)) return 'local'
  if (effect.programmerLayerId != null) return 'layer'
  if (effect.cueId != null && !effect.programmerOwned) return 'cue'
  return 'other'
}

/**
 * The family an FX's attribute is in: the preview's answer where it has one, else the attribute's
 * own category on any head that carries it — `rgbColour` and `position` are the synthetic colour and
 * pan/tilt pair, whose families are their names'.
 */
export function familyOfFx(
  effect: Pick<ActiveFx, 'id' | 'propertyName'>,
  fixtures: readonly Fixture[],
  answered?: ReadonlyMap<number, AttributeFamily>,
): AttributeFamily {
  const known = answered?.get(effect.id)
  if (known != null) return known
  const name = effect.propertyName
  if (name === 'rgbColour' || name === 'colour') return 'COLOUR'
  if (name === 'position' || name === 'pan' || name === 'tilt') return 'POSITION'
  for (const fixture of fixtures) {
    for (const property of [...fixture.properties, ...(fixture.elements ?? []).flatMap((e) => e.properties)]) {
      if (property.name === name) return familyForCategory(property.category)
    }
  }
  return name === 'dimmer' ? 'INTENSITY' : 'BEAM'
}

/** The families the selection's heads can take: a lane is drawn for each, FX or none. */
export function selectionFamilies(targets: readonly CueTarget[], fixtures: readonly Fixture[]): AttributeFamily[] {
  return targetFamilies(selectionUnits(targets, fixtures))
}

/** The heads the selection holds that carry [propertyName] — what an FX over it on that attribute would paint. */
function selectedHeadsFor(targets: readonly CueTarget[], fixtures: readonly Fixture[], propertyName: string): string[] {
  return fxPaintedHeads(
    {
      targetKey: '',
      isGroupTarget: false,
      targetType: 'selection',
      targets: [...targets],
      propertyName,
      elementFilter: null,
      elementMode: null,
    },
    fixtures,
  )
}

/** Each running FX's place in the preview's apply order (0 the bottom), and its family. */
export function answeredOrder(answer: FxPreviewAnswer | null): {
  rank: ReadonlyMap<number, number>
  family: ReadonlyMap<number, AttributeFamily>
} {
  const rank = new Map<number, number>()
  const family = new Map<number, AttributeFamily>()
  answer?.fx.forEach((fx, i) => {
    if (fx.fxId == null) return
    rank.set(fx.fxId, i)
    if (fx.family === 'INTENSITY' || fx.family === 'COLOUR' || fx.family === 'POSITION' || fx.family === 'BEAM') {
      family.set(fx.fxId, fx.family)
    }
  })
  return { rank, family }
}

export interface FxListInput {
  effects: readonly ActiveFx[]
  targets: readonly CueTarget[]
  fixtures: readonly Fixture[]
  /** Each programmer layer's place in the stack, bottom first. */
  layerPosition: ReadonlyMap<number, number>
  answer: FxPreviewAnswer | null
}

/**
 * The lanes, Intensity · Colour · Position · Beam: one for each family an FX in the list is in or
 * the selection can take, each FX on the selection a card in the desk's apply order, top first.
 * An FX the desk is letting go of on a fade (`leaving`) is nobody's and is left out.
 */
export function fxLanes({ effects, targets, fixtures, layerPosition, answer }: FxListInput): FxLane[] {
  if (targets.length === 0) return []
  const { rank, family: answeredFamily } = answeredOrder(answer)
  const selected = new Map<string, Set<string>>()
  const selectedFor = (propertyName: string) => {
    let heads = selected.get(propertyName)
    if (heads == null) {
      heads = new Set(selectedHeadsFor(targets, fixtures, propertyName))
      selected.set(propertyName, heads)
    }
    return heads
  }

  const cards: FxCard[] = []
  for (const effect of effects) {
    if (effect.leaving === true) continue
    const heads = selectedFor(effect.propertyName)
    if (heads.size === 0) continue
    const reached = fxPaintedHeads(effect, fixtures).filter((head) => heads.has(head)).length
    if (reached === 0) continue
    cards.push({ effect, kind: cardKindOf(effect), family: familyOfFx(effect, fixtures, answeredFamily), reached, of: heads.size })
  }

  const compare = (a: FxCard, b: FxCard) => {
    const tier = TIER[b.kind] - TIER[a.kind]
    if (tier !== 0) return tier
    const ra = rank.get(a.effect.id)
    const rb = rank.get(b.effect.id)
    if (ra != null && rb != null) return rb - ra
    // One the answer has not reached is newer than it: on top of its tier.
    if (ra != null) return 1
    if (rb != null) return -1
    const la = a.effect.programmerLayerId != null ? (layerPosition.get(a.effect.programmerLayerId) ?? -1) : -1
    const lb = b.effect.programmerLayerId != null ? (layerPosition.get(b.effect.programmerLayerId) ?? -1) : -1
    if (la !== lb) return lb - la
    return b.effect.id - a.effect.id
  }

  const families = new Set<AttributeFamily>([...selectionFamilies(targets, fixtures), ...cards.map((c) => c.family)])
  return PALETTE_FAMILY_ORDER.filter((family) => families.has(family)).map((family) => ({
    family,
    cards: cards.filter((c) => c.family === family).sort(compare),
  }))
}

/**
 * The `programmer.moveFx` frames that drop a lane's Local FX [fxId] at [toIndex] among the lane's
 * Local cards (top first, [localIds]): a move puts an FX directly below another, so one frame names
 * the card that ends directly above it. The lane's top has no card above it: when the FX there is
 * the one directly above the dragged one, that one moves below it instead; otherwise the dragged FX
 * goes below it and it then goes below the dragged one — the one drop a single move cannot say.
 */
export function localMovesFor(localIds: readonly number[], fxId: number, toIndex: number): [number, number][] {
  const from = localIds.indexOf(fxId)
  if (from < 0) return []
  const rest = localIds.filter((id) => id !== fxId)
  const at = Math.max(0, Math.min(rest.length, toIndex))
  if (at === from) return []
  if (at > 0) return [[fxId, rest[at - 1]]]
  const top = localIds[0]
  if (localIds[1] === fxId) return [[top, fxId]]
  return [
    [fxId, top],
    [top, fxId],
  ]
}

/** What a coverage mark says where an FX reaches only some of the selection — *5 of 8*. */
export function coverageLabel(card: Pick<FxCard, 'reached' | 'of'>): string | null {
  return card.reached < card.of ? `${card.reached} of ${card.of}` : null
}

/** The heads an FX was started on, as a selection names them — what *All*'s row press selects. */
export function fxTargetsOf(effect: Pick<ActiveFx, 'targetKey' | 'targetType' | 'targets' | 'isGroupTarget'>): CueTarget[] {
  if (effect.targetType === 'selection') return [...(effect.targets ?? [])]
  return [{ type: effect.isGroupTarget ? 'group' : 'fixture', key: effect.targetKey }]
}

/** The held-back reasons the answer gives an FX on any row, for the card's mark. */
export function heldBackReasons(answer: FxPreviewAnswer | null, fxId: number): Set<string> {
  const out = new Set<string>()
  if (answer == null) return out
  const index = answer.fx.findIndex((fx) => fx.fxId === fxId)
  if (index < 0) return out
  for (const lane of answer.lanes) {
    for (const attribute of lane.attributes) {
      for (const row of attribute.rows) {
        for (const output of row.fx) if (output.fx === index && output.heldBackReason != null) out.add(output.heldBackReason)
      }
    }
  }
  return out
}

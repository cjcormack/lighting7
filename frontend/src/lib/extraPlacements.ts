import type { PatchPlacement, PatchPlacementInput } from '../api/patchApi'
import { focusFields, type LanternFocus } from './lanterns'
import { mediaEqual } from './fittedMedia'

/** The geometry a placement shares with the patch's own: rigging, position and body orientation. */
export interface PlacementGeometry {
  riggingUuid: string | null
  stageX: number | null
  stageY: number | null
  stageZ: number | null
  baseYawDeg: number | null
  basePitchDeg: number | null
  baseRollDeg?: number | null
}

/**
 * A new lantern for a paired dimmer, seeded as the mirror image of the fixture's own placement
 * across the centre line — same rigging, X, yaw and roll negated (a reflection across x = 0 turns
 * each of those the other way, and leaves pitch alone). That is the pair this feature exists
 * for (an SL unit and its SR partner on one bar), and x = 0 is the middle of a rigging and of the
 * stage alike, so the mirror lands on the other side of either. The operator adjusts from there.
 */
export function mirroredPlacement(primary: PlacementGeometry): PatchPlacementInput {
  return {
    label: null,
    riggingUuid: primary.riggingUuid,
    stageX: negate(primary.stageX),
    stageY: primary.stageY,
    stageZ: primary.stageZ,
    baseYawDeg: negate(primary.baseYawDeg),
    basePitchDeg: primary.basePitchDeg,
    baseRollDeg: negate(primary.baseRollDeg ?? null),
    // No `lengthM`: a new side of a variable-length run takes the fixture's own until given one.
  }
}

/** Negate without producing `-0`, which would read as a change against a stored `0`. */
function negate(v: number | null): number | null {
  if (v == null) return null
  return v === 0 ? 0 : -v
}

/** A stored placement as an editable entry, keeping its uuid so a save edits rather than re-adds it. */
export function toPlacementInput(p: PatchPlacement): PatchPlacementInput {
  return {
    uuid: p.uuid,
    label: p.label,
    riggingUuid: p.riggingUuid,
    stageX: p.stageX,
    stageY: p.stageY,
    stageZ: p.stageZ,
    baseYawDeg: p.baseYawDeg,
    basePitchDeg: p.basePitchDeg,
    // Carried even though no field edits it here: the list is sent whole, and an entry without it
    // would clear a roll set elsewhere (the Stage view, or place_fixtures over MCP).
    baseRollDeg: p.baseRollDeg ?? null,
    lengthM: p.lengthM ?? null,
    // The lantern and its focus (stage-view plan session 7), carried whole for the roll's reason:
    // an entry without them would clear a focus set on the Stage view's Focus tab or over MCP.
    ...focusFields(p),
    // Fitted media likewise: the list is sent whole, so an entry without it would clear it.
    media: p.media ?? null,
  }
}

/** Whether two lanterns are focused alike, as the desk stores them — absent and null alike. */
export function focusEqual(a: LanternFocus, b: LanternFocus): boolean {
  const x = focusFields(a)
  const y = focusFields(b)
  return (
    x.lanternType === y.lanternType &&
    x.zoomDeg === y.zoomDeg &&
    x.lampRotationDeg === y.lampRotationDeg &&
    x.gateRotationDeg === y.gateRotationDeg &&
    x.iris === y.iris &&
    x.focusSoftness === y.focusSoftness &&
    bladesEqual(x.shutters, y.shutters)
  )
}

function bladesEqual(a: LanternFocus['shutters'], b: LanternFocus['shutters']): boolean {
  if (a == null || b == null) return a == null && b == null
  return a.length === b.length && a.every((x, i) => x.depth === b[i].depth && x.angleDeg === b[i].angleDeg)
}

/** The label as the desk will store it: trimmed, and blank meaning none. */
export function normalisedLabel(label: string | null): string | null {
  const trimmed = label?.trim() ?? ''
  return trimmed === '' ? null : trimmed
}

/**
 * Whether two lists say the same thing to the desk — same entries in the same order, labels
 * compared as the desk stores them. What decides whether the form has an `extraPlacements`
 * change to send.
 */
export function placementListsEqual(
  a: readonly PatchPlacementInput[],
  b: readonly PatchPlacementInput[],
): boolean {
  if (a.length !== b.length) return false
  return a.every((x, i) => {
    const y = b[i]
    return (
      x.uuid === y.uuid &&
      normalisedLabel(x.label) === normalisedLabel(y.label) &&
      x.riggingUuid === y.riggingUuid &&
      x.stageX === y.stageX &&
      x.stageY === y.stageY &&
      x.stageZ === y.stageZ &&
      x.baseYawDeg === y.baseYawDeg &&
      x.basePitchDeg === y.basePitchDeg &&
      (x.baseRollDeg ?? null) === (y.baseRollDeg ?? null) &&
      // Absent and null both mean "the patch's own length", as the desk stores them.
      (x.lengthM ?? null) === (y.lengthM ?? null) &&
      focusEqual(x, y) &&
      mediaEqual(x.media, y.media)
    )
  })
}

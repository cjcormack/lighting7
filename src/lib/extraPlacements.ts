import type { PatchPlacement, PatchPlacementInput } from '../api/patchApi'

/** The geometry a placement shares with the patch's own: rigging, position and body orientation. */
export interface PlacementGeometry {
  riggingUuid: string | null
  stageX: number | null
  stageY: number | null
  stageZ: number | null
  baseYawDeg: number | null
  basePitchDeg: number | null
}

/**
 * A new lantern for a paired dimmer, seeded as the mirror image of the fixture's own placement
 * across the centre line — same rigging, X and yaw negated. That is the pair this feature exists
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
    lengthM: p.lengthM ?? null,
  }
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
      // Absent and null both mean "the patch's own length", as the desk stores them.
      (x.lengthM ?? null) === (y.lengthM ?? null)
    )
  })
}

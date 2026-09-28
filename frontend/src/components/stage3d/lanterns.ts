import type { FixturePatch, PatchPlacement } from '../../api/patchApi'

/** One lantern to draw: a paired dimmer's extra placement, as a patch `FixtureModel` can take. */
export interface Lantern {
  /** React key: the patch's id and the placement's uuid. */
  id: string
  /** The fixture this lantern belongs to — what a click on it selects. */
  source: FixturePatch
  /** `source` with the placement's geometry laid over its own. */
  patch: FixturePatch
}

/**
 * Every positioned lantern of `patches`, in patch then list order. Unpositioned lanterns are
 * skipped, as on the 2D plot: `worldPositionFor` would put one at the origin, and a lantern there
 * is a claim about the rig.
 */
export function lanternsFor(patches: readonly FixturePatch[]): Lantern[] {
  return patches.flatMap((source) =>
    (source.extraPlacements ?? [])
      .filter((placement) => placement.stageX != null && placement.stageY != null)
      .map((placement) => ({
        id: `${source.id}:${placement.uuid}`,
        source,
        patch: patchAtPlacement(source, placement),
      })),
  )
}

/**
 * `patch` drawn at one of its extra placements: the placement's rigging, position and body
 * orientation over the patch's own, everything else — key, type, beam, gel, kind — the patch's.
 * `FixtureModel` reads placement from exactly those six fields. A segment of a variable-length
 * fixture (one side of a lightstrip ring) also carries its own `lengthM`, and takes the patch's
 * where it has none. The one other field it changes is
 * `displayName`, which `FixtureModel` draws as the 3D label: a labelled lantern reads
 * "Name · SR", as it does on the 2D plot and the overview panel.
 */
export function patchAtPlacement(patch: FixturePatch, placement: PatchPlacement): FixturePatch {
  const label = placement.label?.trim()
  return {
    ...patch,
    displayName: label ? `${patch.displayName || patch.key} · ${label}` : patch.displayName,
    riggingUuid: placement.riggingUuid,
    stageX: placement.stageX,
    stageY: placement.stageY,
    stageZ: placement.stageZ,
    baseYawDeg: placement.baseYawDeg,
    basePitchDeg: placement.basePitchDeg,
    lengthM: placement.lengthM ?? patch.lengthM ?? null,
  }
}

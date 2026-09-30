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
 * skipped, as on every stage surface: `worldPositionFor` would put one at the origin, and a lantern there
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
 * `FixtureModel` reads placement from exactly those seven fields. A segment of a variable-length
 * fixture (one side of a lightstrip ring) also carries its own `lengthM`, and takes the patch's
 * where it has none. The one other field it changes is
 * `displayName`, which `FixtureModel` draws as the 3D label: a labelled lantern reads
 * "Name · SR".
 *
 * **A placement is its own lantern, focused separately** (stage-view plan D9, D14): its
 * `lanternType` wins and a null one takes the patch's, and its six focus fields are its own —
 * never the patch's, since a blade pushed in on one unit is not in on the other. The body then
 * derives the kind from the placement's lantern, so a pair can be a profile and a fresnel.
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
    baseRollDeg: placement.baseRollDeg ?? null,
    lengthM: placement.lengthM ?? patch.lengthM ?? null,
    lanternType: placement.lanternType ?? patch.lanternType ?? null,
    zoomDeg: placement.zoomDeg ?? null,
    lampRotationDeg: placement.lampRotationDeg ?? null,
    shutters: placement.shutters ?? null,
    gateRotationDeg: placement.gateRotationDeg ?? null,
    iris: placement.iris ?? null,
    focusSoftness: placement.focusSoftness ?? null,
  }
}

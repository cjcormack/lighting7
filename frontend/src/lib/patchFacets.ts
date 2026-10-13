import {
  PATCH_FACETS,
  type AddressFacetValue,
  type FacetValue,
  type FixturePatch,
  type FocusFacetValue,
  type MediaFacetValue,
  type PatchFacet,
  type PatchFacetState,
  type PositionFacetValue,
} from '@/api/patchApi'
import { focusFields } from './lanterns'
import { formatPatchAddress } from './patchAddress'

/**
 * A venue patch's four **facets** in a show (venue plan session 5, D4): what the fixture sheet's
 * Venue | This show switches read and write, and what the Patch list's chips say. Pure: no store,
 * no React. The desk owns every rule — what a facet's fields are, what is valid, what drift is —
 * and this side only reads its answers and builds a value in `PUT …/facets/{facet}`'s shape.
 *
 * A facet is **whole** (D4): an override carries every field the facet covers, the patch's and each
 * extra placement's, so a value built here starts from the patch as the rig shows it and changes
 * what the operator changed. A placement is named by its uuid and keeps the venue's label and order.
 */

export const FACET_LABEL: Readonly<Record<PatchFacet, string>> = {
  ADDRESS: 'Address',
  POSITION: 'Position',
  FOCUS: 'Focus',
  MEDIA: 'Media',
}

/** The show's override of [facet] on [patch], or null when the facet is the venue's. */
export function facetState(patch: FixturePatch, facet: PatchFacet): PatchFacetState | null {
  return patch.facets?.find((f) => f.facet === facet) ?? null
}

/** Each facet's chip state on the Patch list: `venue` (unlit), `show` (overridden) or `drifted`. */
export type FacetChipState = 'venue' | 'show' | 'drifted'

export function facetChips(patch: FixturePatch): { facet: PatchFacet; state: FacetChipState }[] {
  return PATCH_FACETS.map((facet) => {
    const state = facetState(patch, facet)
    return { facet, state: state == null ? 'venue' : state.drifted ? 'drifted' : 'show' }
  })
}

/** Whether any of the patch's overrides has drifted — the row's amber reading. */
export function anyDrifted(patch: FixturePatch): boolean {
  return (patch.facets ?? []).some((f) => f.drifted === true)
}

/**
 * [facet]'s value as [patch]'s own fields hold it — the venue's where the show does not override
 * it, the show's where it does: the starting point of an override, and what *This show* opens on.
 * An Address is named by universe number (the route resolves it in the show's rig), so a value
 * built here needs no universe list.
 */
export function facetValueOf(patch: FixturePatch, facet: PatchFacet): FacetValue {
  const placements = patch.extraPlacements ?? []
  switch (facet) {
    case 'ADDRESS':
      return { universe: patch.universe, subnet: patch.subnet, startChannel: patch.startChannel }
    case 'POSITION':
      return {
        riggingUuid: patch.riggingUuid,
        stageX: patch.stageX,
        stageY: patch.stageY,
        stageZ: patch.stageZ,
        baseYawDeg: patch.baseYawDeg,
        basePitchDeg: patch.basePitchDeg,
        baseRollDeg: patch.baseRollDeg ?? null,
        lengthM: patch.lengthM ?? null,
        placements: placements.map((p) => ({
          placementUuid: p.uuid,
          riggingUuid: p.riggingUuid,
          stageX: p.stageX,
          stageY: p.stageY,
          stageZ: p.stageZ,
          baseYawDeg: p.baseYawDeg,
          basePitchDeg: p.basePitchDeg,
          baseRollDeg: p.baseRollDeg ?? null,
          lengthM: p.lengthM ?? null,
        })),
      }
    case 'FOCUS':
      return {
        ...focusFields(patch),
        beamAngleDeg: patch.beamAngleDeg,
        lampWatts: patch.lampWatts ?? null,
        lampKelvin: patch.lampKelvin ?? null,
        placements: placements.map((p) => ({ placementUuid: p.uuid, ...focusFields(p) })),
      }
    case 'MEDIA':
      return {
        gelCode: patch.gelCode,
        media: patch.media ?? null,
        placements: placements.map((p) => ({ placementUuid: p.uuid, media: p.media ?? null })),
      }
  }
}

/** What a summary line needs that a value does not carry: names for a universe and a rigging. */
export interface FacetNames {
  /** A universe's number by uuid — the universe list's (`UniverseConfig.uuid`). */
  universeOf?: (uuid: string) => number | undefined
  /** A rigging's name by uuid. */
  riggingName?: (uuid: string) => string | undefined
  /** A lantern's name by library id. */
  lanternName?: (id: string) => string | undefined
}

/** A facet value in a line, as the fixture sheet's Venue side and the drift note read it. */
export function describeFacet(facet: PatchFacet, value: FacetValue, names: FacetNames = {}): string {
  switch (facet) {
    case 'ADDRESS': {
      const v = value as AddressFacetValue
      const universe = v.universe ?? (v.universeConfigUuid ? names.universeOf?.(v.universeConfigUuid) : undefined)
      return universe == null ? `ch ${v.startChannel}` : formatPatchAddress(universe, v.startChannel)
    }
    case 'POSITION': {
      const v = value as PositionFacetValue
      const where = v.riggingUuid ? (names.riggingName?.(v.riggingUuid) ?? 'a rigging') : 'Free-standing'
      const parts = [where]
      if (v.stageX != null) parts.push(`x ${v.stageX.toFixed(2)} m`)
      if (v.stageZ != null) parts.push(`z ${v.stageZ.toFixed(2)} m`)
      parts.push(`yaw ${fmt(v.baseYawDeg ?? 0)}°`)
      if (v.basePitchDeg) parts.push(`pitch ${fmt(v.basePitchDeg)}°`)
      return parts.join(' · ')
    }
    case 'FOCUS': {
      const v = value as FocusFacetValue
      const parts = [v.lanternType ? (names.lanternName?.(v.lanternType) ?? v.lanternType) : 'Default lantern']
      if (v.zoomDeg != null) parts.push(`zoom ${fmt(v.zoomDeg)}°`)
      if (v.beamAngleDeg != null) parts.push(`beam ${v.beamAngleDeg}°`)
      if (v.lampWatts != null) parts.push(`${v.lampWatts} W`)
      if (v.lampKelvin != null) parts.push(`${v.lampKelvin} K`)
      if (v.iris != null && v.iris < 1) parts.push(`iris ${Math.round(v.iris * 100)}%`)
      if (v.shutters?.some((b) => b.depth > 0)) parts.push('shuttered')
      return parts.join(' · ')
    }
    case 'MEDIA': {
      const v = value as MediaFacetValue
      const parts = [v.gelCode ? `Gel ${v.gelCode}` : 'No gel']
      const slots = countSlots(v.media) + (v.placements ?? []).reduce((n, p) => n + countSlots(p.media), 0)
      if (slots > 0) parts.push(`${slots} slot${slots === 1 ? '' : 's'} fitted`)
      return parts.join(' · ')
    }
  }
}

/**
 * The drift note's sentence: what the venue moved under the override — its value then, its value
 * now — or null when it has not drifted.
 */
export function driftLine(state: PatchFacetState, names: FacetNames = {}): string | null {
  if (!state.drifted) return null
  const now = describeFacet(state.facet, state.venue, names)
  const was = state.base ? describeFacet(state.facet, state.base, names) : null
  return was && was !== now ? `${was} → ${now}` : now
}

function countSlots(media: MediaFacetValue['media']): number {
  if (!media) return 0
  return Object.values(media.slots ?? {}).reduce((n, options) => n + Object.keys(options ?? {}).length, 0)
}

function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

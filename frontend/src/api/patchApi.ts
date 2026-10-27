import { InternalApiConnection } from "./internalApi";
import { Subscription } from "./subscription";
import type { LanternFocus } from "../lib/lanterns";
import type { FittedMedia } from "../lib/fittedMedia";

/**
 * A patch carries its lantern and focus (`LanternFocus`, stage-view plan session 7) for a type
 * that takes one (`FixtureTypeInfo.acceptsLantern` — a generic dimmer): which lantern from
 * `GET /lanterns` it is, and how it was focused — zoom, a PAR's lamp turn, four blades, gate
 * rotation, iris and the focus knob. With a lantern named the desk derives `kindOverride` from it.
 * Every field optional: an older desk omits them.
 */
export interface FixturePatch extends LanternFocus {
  id: number;
  /** The record's identity across a venue's versions — what a draft change names (`lib/venue.ts`). */
  uuid: string;
  key: string;
  displayName: string;
  /** The operator's number for this head — a ChamSys head number, another desk's fixture or
   *  channel number — unique in the project; null when unnumbered. Optional because a desk that
   *  predates the field omits it. The arithmetic is `lib/headNumber.ts`. */
  headNumber?: number | null;
  fixtureTypeKey: string;
  startChannel: number;
  channelCount: number | null;
  manufacturer: string | null;
  model: string | null;
  modeName: string | null;
  universe: number;
  subnet: number;
  sortOrder: number;
  groups: { id: number; name: string }[];
  stageX: number | null;
  stageY: number | null;
  stageZ: number | null;
  baseYawDeg: number | null;
  basePitchDeg: number | null;
  /** Body roll: the `Z` of the `YXZ` Euler, applied before pitch and yaw — it tips the body sideways
   *  in its own X–Y plane (for a moving head, onto its side; not a spin about the beam). What stands a strip on end: pitch turns a strip about its own length and yaw
   *  swings it round, so neither can lift it off level. Absent or null is 0. */
  baseRollDeg?: number | null;
  /**
   * The rigging this patch hangs on, or null when it is free-standing.
   *
   * A free-text position label sat beside this until the backend folded it into first-class
   * Rigging rows. Resolve the uuid against `useRiggingListQuery` for anything that wants to *name*
   * the position — that is the one spelling of it there is now.
   */
  riggingUuid: string | null;
  beamAngleDeg: number | null;
  gelCode: string | null;
  /** Per-patch FixtureKind override for the 3D view — null means inherit
   *  the kind declared on the fixture type. */
  kindOverride: string | null;
  /** The unit's installed length in metres, for a type whose length is set per install
   *  (`FixtureTypeInfo.acceptsLength` — a lightstrip). Null or absent draws the type's default;
   *  read it through `lib/fixtureLength.ts`, which also ignores it on a fixed-length type. Optional
   *  because a desk that predates the field omits it. */
  lengthM?: number | null;
  /** The lamp's power in watts, for a type hung with a lantern; null or absent is the lantern's
   *  `watts`. One lamp for every lantern the patch drives — read it through `lib/lamp.ts`. */
  lampWatts?: number | null;
  /** The lamp's colour temperature at full, in kelvin; null or absent is the lantern's `kelvin`. */
  lampKelvin?: number | null;
  /** Omit this patch from the Stage view (2D map and 3D scene). For real DMX
   *  that isn't a stage object — a dimmer driving hard power. Presentational
   *  only: the fixture still patches, outputs, and runs in cues and FX. */
  stageHidden: boolean;
  /** Infrastructure, not a lighting fixture — a dimmer on hard power, a relay. Hidden from every
   *  view but Patches and Channels (and so from the Stage, whatever `stageHidden` says); enumerate
   *  patches outside those two through `useVisiblePatchListQuery`. Optional so an older backend
   *  reads as "not infrastructure". */
  infrastructure?: boolean;
  /**
   * The other places this same fixture hangs — a paired dimmer's second lantern, SL beside SR on
   * one bar. One fixture to control (one address, one row in every cue and group), several to
   * draw: every stage surface draws each placement, lit from the patch's one set of channels.
   * Optional because a desk that predates the field omits it; read it as `?? []`.
   */
  extraPlacements?: PatchPlacement[];
  /**
   * The unit's **fitted media** (fixture optics plan D6): what is loaded in its loadable settings,
   * naming only the options that differ from the type's stock. Null or absent is the stock
   * everywhere. Each extra placement carries its own, layered over this option by option
   * (`lib/fittedMedia.ts`'s `mediaOver`). Optional because a desk that predates it omits it.
   */
  media?: FittedMedia | null;
  /**
   * The unit's **stored locate** (venue-visit plan D6, D17): the pan and tilt Locate points each of
   * its units at instead of the centre of travel, as DMX bytes — `head` absent for the fixture's own,
   * a head's index from 0 otherwise. Absent or empty is none. Part of the Focus facet; set by *Set
   * locate from current* (`POST …/locate/store`) and cleared by *Clear locate*, never by the patch's
   * `PUT`. Shown on the centre-relative scale (`lib/positionScale.ts`). Optional because a desk that
   * predates it omits it, and the desk omits an empty list.
   */
  locate?: UnitLocate[];
  /**
   * Whose the patch is in the rig it was read in (venue plan session 3): `SHOW` for the project's
   * own, `VENUE` for its venue's at the pinned version. A venue row's identity is the venue's and the
   * desk refuses a patch write to one through a show (`VENUE_OWNED`); a show can hide it, or make one
   * of its four facets its own (`facets`, `PUT …/facets/{facet}`, session 5). Absent from a desk older
   * than venues, which reads as `SHOW`.
   */
  owner?: RigOwner;
  /** The universe the patch sits on, as the rig sees it — what an Address facet names. Absent from an
   *  older desk. */
  universeConfigUuid?: string;
  /**
   * A venue patch's facets this show overrides (venue plan session 5), each with the show's value, the
   * venue's current value and whether the venue drifted under it. The fields above are already the
   * overridden ones. Absent or empty for a show's own patch, a standalone show's, and a venue patch the
   * show takes whole — and from a desk older than facet overrides.
   */
  facets?: PatchFacetState[];
  /**
   * The unit's colour (tables-colours plan D7), one of the desk's ten keys, as this rig draws it — a
   * venue patch's recolour by this show when it has one. Absent or null is none, and so is a key the
   * desk does not know. Identity only: never output. Absent from a desk older than colours.
   */
  colour?: string | null;
  /** On a venue patch in a show: whether `colour` is this show's recolour or the venue's own (D10). */
  colourOwner?: ColourOwner | null;
}

/** Whose a venue record's colour is in a show (tables-colours plan D10). */
export type ColourOwner = 'SHOW' | 'VENUE';

/** Whose a venue group's member order is in a show (group-order plan D8). */
export type OrderOwner = 'SHOW' | 'VENUE';

/** The four parts of a venue patch a show can make its own (venue plan D4), each whole. */
export type PatchFacet = 'ADDRESS' | 'POSITION' | 'FOCUS' | 'MEDIA';

export const PATCH_FACETS: readonly PatchFacet[] = ['ADDRESS', 'POSITION', 'FOCUS', 'MEDIA'];

/**
 * One overridden facet of a venue patch, as the desk reads it (`ShowOverrides.FacetStateDto`).
 * `value` and `venue` are the facet's fields in `PUT …/facets/{facet}`'s shape (`lib/patchFacets.ts`).
 */
export interface PatchFacetState {
  facet: PatchFacet;
  /** The show's value. */
  value: FacetValue;
  /** The pinned version's value now. */
  venue: FacetValue;
  /** The venue changed this facet since the override was made (or last kept). */
  drifted?: boolean;
  /** The venue's value when the override was made; present only when drifted. */
  base?: FacetValue;
  /** The override names a universe or rigging the rig no longer has, so the venue's facet stands. */
  dormant?: boolean;
}

/** A facet's fields. Placements are named by the venue placement's uuid, never added or removed. */
export type FacetValue = AddressFacetValue | PositionFacetValue | FocusFacetValue | MediaFacetValue;

export interface AddressFacetValue {
  /** Stored by uuid; a write may name the universe by number instead (`universe`, `subnet`). */
  universeConfigUuid?: string;
  universe?: number;
  subnet?: number;
  startChannel: number;
}

export interface PositionFields {
  riggingUuid?: string | null;
  stageX?: number | null;
  stageY?: number | null;
  stageZ?: number | null;
  baseYawDeg?: number | null;
  basePitchDeg?: number | null;
  baseRollDeg?: number | null;
  lengthM?: number | null;
}

export interface PositionFacetValue extends PositionFields {
  placements?: (PositionFields & { placementUuid: string })[];
}

/** One unit's stored locate: coarse pan and tilt, and the fine bytes where it has fine channels. */
export interface UnitLocate {
  /** Absent for the fixture's own pan and tilt; a head's zero-based index otherwise. */
  head?: number | null;
  pan: number;
  tilt: number;
  panFine?: number | null;
  tiltFine?: number | null;
}

export interface FocusFacetValue extends LanternFocus {
  beamAngleDeg?: number | null;
  lampWatts?: number | null;
  lampKelvin?: number | null;
  /** The stored locate — the patch's alone, so no placement carries one. */
  locate?: UnitLocate[];
  placements?: (LanternFocus & { placementUuid: string })[];
}

export interface MediaFacetValue {
  gelCode?: string | null;
  media?: FittedMedia | null;
  placements?: { placementUuid: string; media?: FittedMedia | null }[];
}

/** What a facet write answers: the patch as the rig now sees it, and an Address override's overlaps. */
export interface FacetWriteResponse {
  patch: FixturePatch;
  changed: boolean;
  warnings?: string[];
}

/** Whose a record of a show's rig is (venue plan §3.1). */
export type RigOwner = 'SHOW' | 'VENUE';

/** A venue row seen through a show: in the rig, not the show's to change. */
export function isVenueOwned(record: { owner?: RigOwner }): boolean {
  return record.owner === 'VENUE';
}

/**
 * One of a patch's extra placements. Same geometry and rigging-relative rule as the patch's own
 * `stageX`… fields; it carries none of the fixture's own facts (type, beam angle, gel, kind), which
 * a paired lantern shares with the primary one — except its **lantern and focus** (D9, D14): a pair
 * on one dimmer is two lanterns, focused separately. A null `lanternType` takes the patch's; the six
 * focus fields are this lantern's own and never inherited.
 */
export interface PatchPlacement extends LanternFocus {
  uuid: string;
  /** Short name for this lantern on the plot, e.g. "SR". */
  label: string | null;
  riggingUuid: string | null;
  stageX: number | null;
  stageY: number | null;
  stageZ: number | null;
  baseYawDeg: number | null;
  basePitchDeg: number | null;
  /** As `FixturePatch.baseRollDeg` — a ring's upright side is a placement rolled 90°. */
  baseRollDeg?: number | null;
  /** This segment's own length, for a variable-length type laid in segments (a lightstrip ring,
   *  one side per placement). Null or absent takes the patch's `lengthM`. */
  lengthM?: number | null;
  /** This unit's own fitted media, layered over the patch's option by option; null fits nothing of
   *  its own. Carried whole on every write of the list, as the focus is — an entry without it clears
   *  it. */
  media?: FittedMedia | null;
}

/**
 * An `extraPlacements` entry as written: the whole list is sent and replaces the stored one. An
 * entry carrying a `uuid` edits that placement; one without is new. At most
 * `MAX_EXTRA_PLACEMENTS`, mirroring the desk's cap.
 */
export type PatchPlacementInput = Omit<PatchPlacement, 'uuid'> & { uuid?: string };

/** Mirrors `MAX_EXTRA_PLACEMENTS` in lighting7's `models/fixturePatchPlacements.kt`. */
export const MAX_EXTRA_PLACEMENTS = 16;
/** Mirrors `MAX_PLACEMENT_LABEL_LENGTH` there. */
export const MAX_PLACEMENT_LABEL_LENGTH = 40;
/** Mirror `MIN_FIXTURE_LENGTH_M` / `MAX_FIXTURE_LENGTH_M` in lighting7's `models/fixturePatches.kt`:
 *  the bounds on a patch's (or a placement's) `lengthM`. */
export const MIN_FIXTURE_LENGTH_M = 0.01;
export const MAX_FIXTURE_LENGTH_M = 100;
/** Mirror `MIN_HEAD_NUMBER` / `MAX_HEAD_NUMBER` in lighting7's `models/fixturePatches.kt`. */
export const MIN_HEAD_NUMBER = 1;
export const MAX_HEAD_NUMBER = 99999;

/**
 * Default Art-Net transmit interval, mirroring
 * `ArtNetController.DEFAULT_REFRESH_INTERVAL_MS` in lighting7. Used only to decide whether
 * to surface the interval on a universe chip; the server is the authority on the value.
 */
export const DEFAULT_REFRESH_INTERVAL_MS = 25;

/** Bounds mirroring `ArtNetController.MIN_/MAX_REFRESH_INTERVAL_MS`. A full 513-slot
 *  DMX512 frame occupies ~22.6 ms on the wire, so a node cannot emit faster than ~44 Hz
 *  however fast we feed it; the ceiling keeps clear of node data-loss timeouts. */
export const MIN_REFRESH_INTERVAL_MS = 23;
export const MAX_REFRESH_INTERVAL_MS = 1000;

export interface UniverseConfig {
  id: number;
  subnet: number;
  universe: number;
  controllerType: string;
  address: string | null;
  /** Effective Art-Net transmit interval: this machine's override, else the default. */
  refreshIntervalMs: number;
  /** False when the interval is simply the default rather than pinned on this desk. */
  refreshIntervalOverridden: boolean;
  patchCount: number;
  /** A venue's universe (venue plan session 3): its address is still set here, machine-local. */
  owner?: RigOwner;
  /** The universe's identity — what an Address facet override names (venue plan session 5). Absent
   *  from an older desk. */
  uuid?: string;
}

export interface CreatePatchRequest {
  universe: number;
  fixtureTypeKey: string;
  key: string;
  name: string;
  startChannel: number;
  /** Unique in the project; the desk answers 409 for a number another head holds. */
  headNumber?: number | null;
  address?: string;
  groupName?: string;
  stageX?: number | null;
  stageY?: number | null;
  stageZ?: number | null;
  baseYawDeg?: number | null;
  basePitchDeg?: number | null;
  baseRollDeg?: number | null;
  riggingUuid?: string | null;
  beamAngleDeg?: number | null;
  gelCode?: string | null;
  kindOverride?: string | null;
  /** Only for a type that takes one (`acceptsLength`); the desk refuses it otherwise. */
  lengthM?: number | null;
  stageHidden?: boolean;
  infrastructure?: boolean;
  /** Only for a type that takes a lantern (`acceptsLantern`). */
  lanternType?: string | null;
  zoomDeg?: number | null;
  lampRotationDeg?: number | null;
  shutters?: LanternFocus['shutters'];
  gateRotationDeg?: number | null;
  iris?: number | null;
  focusSoftness?: number | null;
  /** Only for a type that takes a lantern; see `FixturePatch.lampWatts`. */
  lampWatts?: number | null;
  lampKelvin?: number | null;
  /** Only for a type with loadable settings; see `FixturePatch.media`. */
  media?: FittedMedia | null;
  /** One of the desk's colour keys; anything else is 400 `COLOUR_UNKNOWN`. */
  colour?: string | null;
}

export interface UpdatePatchRequest extends LanternFocus {
  displayName?: string;
  /** Null unnumbers the head; a number another head holds is a 409. */
  headNumber?: number | null;
  key?: string;
  startChannel?: number;
  addToGroup?: string;
  removeFromGroupId?: number;
  stageX?: number | null;
  stageY?: number | null;
  stageZ?: number | null;
  baseYawDeg?: number | null;
  basePitchDeg?: number | null;
  baseRollDeg?: number | null;
  riggingUuid?: string | null;
  beamAngleDeg?: number | null;
  gelCode?: string | null;
  kindOverride?: string | null;
  /** Only for a type that takes one (`acceptsLength`); null returns to the type's default. */
  lengthM?: number | null;
  /** Only for a type that takes a lantern; null returns to the lantern's lamp. */
  lampWatts?: number | null;
  lampKelvin?: number | null;
  stageHidden?: boolean;
  infrastructure?: boolean;
  /** The whole list, replacing the stored one; null or `[]` removes every extra placement. */
  extraPlacements?: PatchPlacementInput[] | null;
  /** The whole fitted media, replacing the stored; null clears it, absent keeps it. */
  media?: FittedMedia | null;
  /**
   * A colour key; null clears it, absent keeps it. On a venue patch in a show it is the show's
   * recolour, and null takes the venue's back.
   */
  colour?: string | null;
}

export interface PatchGroup {
  id: number;
  name: string;
  memberCount: number;
  /** A venue's group holds venue fixtures only; a show's may hold the venue's too (venue plan D12). */
  owner?: RigOwner;
  /** The group's colour as this rig draws it; never its members'. Absent from an older desk. */
  colour?: string | null;
  colourOwner?: ColourOwner | null;
  /** On a venue group in a show: whether its member order is the show's or the venue's. */
  orderOwner?: OrderOwner | null;
}

export interface PatchGroupDetail {
  id: number;
  name: string;
  members: PatchGroupMember[];
  owner?: RigOwner;
  colour?: string | null;
  colourOwner?: ColourOwner | null;
  orderOwner?: OrderOwner | null;
}

export interface PatchGroupMember {
  patchId: number;
  fixtureKey: string;
  fixtureName: string;
  fixtureTypeKey: string;
  sortOrder: number;
}

export interface UpdatePatchGroupRequest {
  name?: string;
  /**
   * Patch ids in the order wanted. On a venue group in a show, sent without a name it is the show's
   * order of it, and null takes the venue's back.
   */
  memberOrder?: number[] | null;
  /** A colour key; null clears it. On a venue group in a show, sent alone it is the show's recolour. */
  colour?: string | null;
}

export interface PatchApi {
  subscribe(fn: () => void): Subscription;
}

export function createPatchApi(conn: InternalApiConnection): PatchApi {
  let nextSubscriptionId = 1;
  const subscriptions = new Map<number, () => void>();

  const notifyChange = () => {
    subscriptions.forEach((fn) => fn());
  };

  conn.subscribe((evType, _ev, frame) => {
    if (evType === 'message') {
      const message = frame as { type?: string } | null;
      if (message?.type === 'patchListChanged') {
        notifyChange();
      }
    }
  });

  return {
    subscribe(fn: () => void): Subscription {
      const thisId = nextSubscriptionId++;
      subscriptions.set(thisId, fn);
      return {
        unsubscribe: () => {
          subscriptions.delete(thisId);
        },
      };
    },
  };
}

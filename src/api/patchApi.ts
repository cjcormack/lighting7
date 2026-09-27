import { InternalApiConnection } from "./internalApi";
import { Subscription } from "./subscription";

export interface FixturePatch {
  id: number;
  key: string;
  displayName: string;
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
  /** Omit this patch from the Stage view (2D map and 3D scene). For real DMX
   *  that isn't a stage object — a dimmer driving hard power. Presentational
   *  only: the fixture still patches, outputs, and runs in cues and FX. */
  stageHidden: boolean;
  /** Infrastructure, not a lighting fixture — a dimmer on hard power, a relay. Hidden from every
   *  view but Patches and Channels (and so from the Stage, whatever `stageHidden` says); enumerate
   *  patches outside those two through `useVisiblePatchListQuery`. Optional so an older backend
   *  reads as "not infrastructure". */
  infrastructure?: boolean;
}

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
}

export interface CreatePatchRequest {
  universe: number;
  fixtureTypeKey: string;
  key: string;
  name: string;
  startChannel: number;
  address?: string;
  groupName?: string;
  stageX?: number | null;
  stageY?: number | null;
  stageZ?: number | null;
  baseYawDeg?: number | null;
  basePitchDeg?: number | null;
  riggingUuid?: string | null;
  beamAngleDeg?: number | null;
  gelCode?: string | null;
  kindOverride?: string | null;
  stageHidden?: boolean;
  infrastructure?: boolean;
}

export interface UpdatePatchRequest {
  displayName?: string;
  key?: string;
  startChannel?: number;
  addToGroup?: string;
  removeFromGroupId?: number;
  stageX?: number | null;
  stageY?: number | null;
  stageZ?: number | null;
  baseYawDeg?: number | null;
  basePitchDeg?: number | null;
  riggingUuid?: string | null;
  beamAngleDeg?: number | null;
  gelCode?: string | null;
  kindOverride?: string | null;
  stageHidden?: boolean;
  infrastructure?: boolean;
}

export interface PatchGroup {
  id: number;
  name: string;
  memberCount: number;
}

export interface PatchGroupDetail {
  id: number;
  name: string;
  members: PatchGroupMember[];
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
  memberOrder?: number[]; // list of patch IDs in desired order
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

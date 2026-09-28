/**
 * REST types for speed masters — the named tempo buses effects subscribe to.
 *
 * Master 1 (`masterIndex === 1`) is the protected global master: a null uuid on a
 * `speedMasters.*` write means it, and every effect with no explicit master resolves to it.
 * The stored `bpm` here is the *starting* tempo — the
 * live value streams over the `speedMasters.*` WS family (see `speedMastersWsApi.ts`).
 */
export interface SpeedMaster {
  id: number
  /** The identity references use — int ids are re-minted on project import; uuids survive. */
  uuid: string
  /** 1-based display index; 1 is the protected global master. */
  masterIndex: number
  name: string
  /** Stored (starting) tempo. Live tempo comes from the WS state instead. */
  bpm: number
  /** How the tempo was last set — display only. */
  source: 'MANUAL' | 'TAP'
  notes?: string | null
  /**
   * Effect-library category this master is the apply-time default for (`dimmer` / `colour` /
   * `position`), or null/absent to route nothing. Unique within a project — the server 409s
   * with `SPEED_MASTER_USAGE_TAKEN` on a second claimant.
   */
  usage?: string | null
  /**
   * Follow ratio over {@link followTargetUuid}: this master's clock is *driven* by that
   * master's at `followNum / followDen` of its rate, so their beats line up. Both null (or
   * absent) means a manual tempo. Master 1 itself may never follow.
   */
  followNum?: number | null
  followDen?: number | null
  /**
   * The master being followed; null (or absent) means master 1. Only meaningful alongside a
   * ratio — the server nulls it out on a manual row rather than leaving a stale leader visible.
   *
   * Unlike {@link SpeedMasterLiveState.followTargetUuid}, this is the **stored** target, not the
   * resolved one: the degradations `SpeedMasterBank` applies at load happen to the running bank,
   * not to this DTO. The two normally agree, because the routes keep the rows honest — a forced
   * delete of a leader unlinks its followers rather than leaving them dangling. What can still
   * differ is a row no route wrote: an import or a hand-edited database carrying a target this
   * project doesn't have, or a cycle. There the desk runs the master manually while this DTO
   * still shows the link, and the leader lookups fall back to master 1. Read the live frame when
   * the question is what the bank is doing; read this when it is what the row says.
   */
  followTargetUuid?: string | null
  /** Persisted rows referencing this master. Gates delete. */
  referenceCount: number
}

export interface CreateSpeedMasterRequest {
  /** Defaults to "Master {index}" server-side. */
  name?: string
  bpm?: number
  notes?: string
  /** See {@link SpeedMaster.usage}. */
  usage?: string | null
  /** See {@link SpeedMaster.followNum} — both-or-neither, and never alongside `bpm`. */
  followNum?: number | null
  followDen?: number | null
  /** See {@link SpeedMaster.followTargetUuid}; omitted with a ratio means master 1. */
  followTargetUuid?: string | null
}

/**
 * PUT patch body — **only the keys present are changed**, which is why every field is optional
 * and nullable rather than defaulted. Three server-side rules the caller must respect:
 *
 * - `followNum` and `followDen` move together. A half-patch is a 400; unlinking is both
 *   explicitly `null`.
 * - `followTargetUuid` rides with that pair. Sent, it re-points the link; omitted on a
 *   ratio-only edit, the server carries the stored leader forward; an unlink clears it. A
 *   target that names no master is a 400 `SPEED_MASTER_FOLLOW_TARGET_UNKNOWN`, and one that
 *   would close a loop a 400 `SPEED_MASTER_FOLLOW_CYCLE`.
 * - `bpm` must not be sent when the resulting state follows another master (400
 *   `SPEED_MASTER_FOLLOWER`) — a follower's tempo is derived, not stored.
 * - `usage` present-with-null clears the routing.
 */
export interface UpdateSpeedMasterRequest {
  name?: string
  bpm?: number
  notes?: string | null
  usage?: string | null
  followNum?: number | null
  followDen?: number | null
  followTargetUuid?: string | null
}

/** 409 body when deleting a referenced master without `force`. */
export interface SpeedMasterInUseResponse {
  error: string
  code: string
  referenceCount: number
  /** Effects stored on a Look. Was `presetEffectCount`, over the retired FX presets. */
  lookEffectCount: number
  cueAdHocEffectCount: number
  /** Per-layer speed-master overrides. Was `cuePresetApplicationCount`. */
  cueLayerCount: number
  cueIds: number[]
  /**
   * The masters that follow this one, by name — a reference like any other (they are counted in
   * `referenceCount`), and the one an operator can act on directly: "unlink Movement, then delete".
   * A forced delete unlinks them, so they run manually from then on. Optional because a desk that
   * predates the field sends none.
   */
  followerNames?: string[]
}

export const CODE_SPEED_MASTER_IN_USE = 'SPEED_MASTER_IN_USE'

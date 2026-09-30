import type { RiggingDto } from '../../../api/riggingApi'

/**
 * How a body is carried (stage-view plan session 6, the design record's item 11): **hung** from a
 * bar, pipe or truss — a hanger or clamp up to the bar and the base above — or **stood** on a ledge
 * or a floor stand, base down and no hanger.
 *
 * Mirrors the desk's `STANDING_RIGGING_KINDS` (`ai/SetupToolSchemas.kt`), which `describe_rig`
 * reads to say a unit stands on its rigging and to flag a moving head whose base orientation
 * disagrees; `mount.test.ts` pins the two lists against each other through the desk's test
 * resource.
 *
 * The kind decides only how a body is **carried**, never which way it points. A moving head's
 * mount is already its `basePitchDeg` — 0 stands, 180 hangs — and `FixtureAim` solves from that,
 * so turning a mover over by its rigging's kind here would draw a beam `aim_fixtures` does not aim.
 * A static lantern's yoke hangs down from a bar or stands up from a ledge, and its head points
 * where its yaw and pitch say either way.
 */
export const STANDING_RIGGING_KINDS: ReadonlySet<string> = new Set(['FLOOR_STAND', 'LEDGE'])

export type Mount = 'hang' | 'stand'

/**
 * A fixture's mount: standing on a `LEDGE` or `FLOOR_STAND`, hung from anything else. A fixture on
 * no rigging hangs from nothing — drawn as today, with no hanger.
 */
export function mountFor(rigging: Pick<RiggingDto, 'kind'> | null | undefined): Mount {
  return rigging?.kind != null && STANDING_RIGGING_KINDS.has(rigging.kind.toUpperCase()) ? 'stand' : 'hang'
}

/** Shorter than this, a hanger is not drawn: the body sits on the bar. */
export const MIN_HANGER_M = 0.02

/**
 * The hanger's length, metres: from the top of the body (world height [bodyTopY], three.js Y) up to
 * the bar at [barY]. Zero when the bar is not above the body, or for a standing mount.
 */
export function hangerLengthM(mount: Mount, bodyTopY: number, barY: number | null): number {
  if (mount !== 'hang' || barY == null) return 0
  const h = barY - bodyTopY
  return h > MIN_HANGER_M ? h : 0
}

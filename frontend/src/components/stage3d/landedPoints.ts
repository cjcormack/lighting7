/**
 * Where the **selected** fixture's beam lands, as a Stage canvas last drew it — the point the
 * Focus tab's *Focus here* sends to `POST /programmer/focus` (fixture-optics plan D11).
 *
 * The landing is the beam director's own (`FixtureModel`'s `landBeam`: the first surface on the
 * beam's axis, cast from the aperture), so *Focus here* focuses on the surface the operator is
 * looking at, not on a point this side would have to work out again. It is cast whether or not the
 * head is lit — a head is focused before it is brought up as often as after. Only a fixture its
 * canvas is told to report writes here — the selected one, on an on-screen canvas, never a
 * `render_view` capture — so the director pays one extra cast a frame for one fixture.
 *
 * Kept **per reporter**: the Stage view and the Positions plan can both draw the selected fixture,
 * and one of them closing must not erase what the other still draws. A reporter forgets its entry
 * when it stops reporting ([forgetLanding]), so a deselected fixture never answers with a point
 * from before it was re-aimed; the reader takes the most recent report. A module map rather than
 * state: the panel reads it at the press, and the director writes it every frame it draws, which no
 * React render should follow.
 */

/** A point in stage coordinates, metres (x audience-right, y upstage, z up). */
export interface StagePoint {
  x: number
  y: number
  z: number
}

interface Report {
  point: StagePoint | null
  seq: number
}

const landed = new Map<string, Map<object, Report>>()
let seq = 0

/** [reporter] saw [patchKey]'s beam land at [point], or on nothing (`null`). */
export function recordLanding(reporter: object, patchKey: string, point: StagePoint | null): void {
  let reports = landed.get(patchKey)
  if (!reports) {
    reports = new Map()
    landed.set(patchKey, reports)
  }
  reports.set(reporter, { point, seq: ++seq })
}

/** [reporter] no longer reports [patchKey] — deselected, or its canvas gone. */
export function forgetLanding(reporter: object, patchKey: string): void {
  const reports = landed.get(patchKey)
  if (!reports) return
  reports.delete(reporter)
  if (reports.size === 0) landed.delete(patchKey)
}

/** Where [patchKey]'s beam last landed, or null — not reported, or landing on nothing. */
export function landedPoint(patchKey: string): StagePoint | null {
  let newest: Report | null = null
  for (const report of landed.get(patchKey)?.values() ?? []) {
    if (newest == null || report.seq > newest.seq) newest = report
  }
  return newest?.point ?? null
}

import type { SpreadCurve, SpreadOrder } from '@/store/programmerOps'
import { rgbToHex, serializeTemplateRef } from '@/components/fx/colourUtils'
import type { AttributeFamily } from './attributeFamily'
import {
  serializeTemplateIntent,
  templatePropertiesForFamily,
  type TemplateProperty,
  type WhitePolicy,
} from './templateIntent'

/**
 * The Spread tab's endpoints — **serialise only** (busk-further plan D9).
 *
 * A spread is two intents of one property's shape, a curve, an order, parts and an over-switch,
 * sent to `POST /programmer/spread`; the desk interpolates in the intent's own space and resolves
 * one literal per head through the same `TemplateResolver` a template click uses. This module is
 * the client's half of that grammar and nothing more: which properties a family offers, what an
 * endpoint of each shape looks like in the editor, and the string it goes out as. It **never
 * interpolates** — no lerp, no colour space, no per-head range — for `templateIntent.ts`'s reason:
 * only the desk knows a group's member order, each head's range, which cells a fixture has and what
 * a colour means on a head with amber. `spreadIntent.test.ts` pins the serialisation against
 * `templateIntent.ts` and asserts this file imports no resolver.
 */

/**
 * One end of a spread. Four are `TemplateIntent`s of the four shapes a spread can carry; the fifth
 * names a colour **template** (`tmpl:{uuid}`), which the desk resolves to the template's generic
 * colour the way an FX colour reference is resolved — so a spread can run from one library colour
 * to another. A switch (prism in / out) is not offered: the desk would flip it at the midpoint,
 * which is not a fan.
 */
export type SpreadEndpoint =
  | { kind: 'colour'; hex: string; policy: WhitePolicy }
  | { kind: 'template'; uuid: string }
  | { kind: 'percent'; value: number }
  | { kind: 'position'; panDeg: number; tiltDeg: number }
  | { kind: 'level'; value: number }

/**
 * The centre of the position range every editor on this side assumes — half of the 540° / 270°
 * travel `TemplateEditor`'s `PositionControl` runs over. A head with a shorter range is clamped by
 * the desk, which is the resolver's job and not this file's.
 */
export const POSITION_CENTRE_PAN_DEG = 270
export const POSITION_CENTRE_TILT_DEG = 135

/** The editor a property's endpoints take — a template reference is a colour editor's choice, not a fifth editor. */
export type SpreadEditorKind = 'colour' | 'percent' | 'position' | 'level'

/** The properties of a family a spread can carry: the template vocabulary, minus the switch. */
export function spreadPropertiesFor(family: AttributeFamily): TemplateProperty[] {
  return templatePropertiesForFamily(family).filter((property) => property.intent !== 'switch')
}

/** The editor for a property's endpoints. Callers hand in a property from [spreadPropertiesFor]. */
export function spreadEditorKind(property: TemplateProperty): SpreadEditorKind {
  switch (property.intent) {
    case 'colour':
      return 'colour'
    case 'percent':
      return 'percent'
    case 'position':
      return 'position'
    case 'level':
      return 'level'
    case 'switch':
      // Filtered out by [spreadPropertiesFor]; a switch reaching here is a caller's bug, and a
      // level editor over 0–255 at least draws something rather than throwing mid-render.
      return 'level'
  }
}

/**
 * Where a fresh spread of this property starts: the design's amber → blue for a colour, the full
 * range for a level or a percent, and for a position a modest pan swing about the desk's centre
 * **in absolute degrees** — `deg:` is each head's own annotated range, `0…degMax` with the centre
 * at half of it (`TemplateResolver.degreesToDmx` coerces into it; every fixture annotates pan
 * `degMin = 0`), the same convention `TemplateEditor`'s position control defaults to (270 / 135).
 * A signed value about the centre would clamp to the head's hard stop and read as a sliver.
 */
export function defaultSpreadEndpoints(property: TemplateProperty): { from: SpreadEndpoint; to: SpreadEndpoint } {
  switch (spreadEditorKind(property)) {
    case 'colour':
      return {
        from: { kind: 'colour', hex: '#F5B342', policy: 'extract' },
        to: { kind: 'colour', hex: '#2456FF', policy: 'extract' },
      }
    case 'percent':
      return { from: { kind: 'percent', value: 0 }, to: { kind: 'percent', value: 100 } }
    case 'position':
      return {
        from: { kind: 'position', panDeg: POSITION_CENTRE_PAN_DEG - 30, tiltDeg: POSITION_CENTRE_TILT_DEG },
        to: { kind: 'position', panDeg: POSITION_CENTRE_PAN_DEG + 30, tiltDeg: POSITION_CENTRE_TILT_DEG },
      }
    case 'level':
      return { from: { kind: 'level', value: 0 }, to: { kind: 'level', value: 255 } }
  }
}

/** A colour endpoint from the Colour tab's channels — what its *Spread to a second colour…* button hands over. */
export function colourEndpointOf(rgb: { r: number; g: number; b: number }, policy: WhitePolicy = 'extract'): SpreadEndpoint {
  return { kind: 'colour', hex: rgbToHex(rgb.r, rgb.g, rgb.b).toUpperCase(), policy }
}

/**
 * The wire form: a serialised `TemplateIntent` through `templateIntent.ts`'s own serialiser, or a
 * `tmpl:` reference through `colourUtils`'. Nothing is spelled here that either does not already
 * spell, which is what keeps the grammar in one place per kind.
 */
export function serializeSpreadEndpoint(endpoint: SpreadEndpoint): string {
  if (endpoint.kind === 'template') return serializeTemplateRef(endpoint.uuid)
  return serializeTemplateIntent(endpoint)
}

/** Whether an endpoint is one the desk can take: finite numbers, a real hex, a uuid. */
export function isCompleteSpreadEndpoint(endpoint: SpreadEndpoint): boolean {
  switch (endpoint.kind) {
    case 'colour':
      return /^#[0-9a-fA-F]{6}$/.test(endpoint.hex)
    case 'template':
      return endpoint.uuid.trim() !== ''
    case 'percent':
    case 'level':
      return Number.isFinite(endpoint.value)
    case 'position':
      return Number.isFinite(endpoint.panDeg) && Number.isFinite(endpoint.tiltDeg)
  }
}

/** The Titan four, each a picture in the tab; the hint is what the picture says. */
export const SPREAD_CURVES: readonly { id: SpreadCurve; label: string; hint: string }[] = [
  { id: 'LINE', label: 'Line', hint: 'From at the first head, To at the last' },
  { id: 'MIRROR', label: 'Mirror', hint: 'From at the centre, To at both ends' },
  { id: 'ARROW', label: 'Arrow', hint: 'From at both ends, To at the centre' },
  { id: 'WINGS', label: 'Wings', hint: 'Two fans meeting at the centre — To at each outer end, From in the middle' },
]

/**
 * The orders the tab offers, each a `DistributionStrategy` name the desk resolves — and the one
 * the design draws that the desk has no order for. `Stage L→R` would be `POSITIONAL`, but
 * `SpreadPlan` feeds that strategy a head's *index* as its position, so it is rig order under
 * another name; the tab says so in a footnote under the row rather than offering it as something
 * it is not (a disabled item in the row wrapped at 288px and read as a control that was merely off).
 */
export const SPREAD_ORDERS: readonly {
  id: SpreadOrder | null
  label: string
  hint: string
}[] = [
  { id: 'LINEAR', label: 'Rig', hint: 'Rig order: the rig’s rows and tiles, a group in member order, cells in element order' },
  { id: 'REVERSE', label: 'Reverse', hint: 'Rig order, the other way' },
  { id: 'CENTER_OUT', label: 'Centre', hint: 'From the centre outward: the two ends share a step' },
  { id: 'RANDOM', label: 'Random', hint: 'A shuffle of the rig order; press again for another' },
  { id: null, label: 'Stage L→R', hint: 'The desk has no stage order yet — its POSITIONAL strategy reads rig index, which is Rig again' },
]

/** The parts presets; `N…` beyond them is a typed number. */
export const SPREAD_PARTS_PRESETS: readonly number[] = [1, 2, 3, 4]

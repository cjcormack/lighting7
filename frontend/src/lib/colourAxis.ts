import type { ColourAxis } from '@/api/surfacesApi'

export type { ColourAxis }

/**
 * The colour-axis vocabulary, once — a mirror of lighting7's `midi/ColourAxis.kt`, the way
 * `attributeFamily.ts` mirrors `PropertyMaskGroup`. Keep the four strings in step with the Kotlin
 * `@SerialName`s: `colourAxis.test.ts` pins them, and the `Record` types below turn a fifth axis
 * with no label or swatch into a compile error rather than a chip that draws nothing.
 *
 * **Null is hue, and so is `'hue'`.** A binding carries no `colourAxis` for hue — the wire omits
 * the field, every row written before axes existed has none, and this side never sends `'hue'`
 * (see [withAxis]) — but the enum has a `hue` member so a hand-built payload can say it. Anything
 * that *compares* an axis goes through [sameAxis] / [isPlainHue] so the two spellings never read as
 * two axes; that is what keeps the encoder-bank LED, the inspector's label and the picker honest.
 *
 * This module labels and draws; it never resolves. What a fine trim or a saturation position
 * *does* to a head is `PropertyChannelResolver`'s, in both directions, and a second copy here would
 * be the drift `surfaceResolve.ts`'s header warns about.
 */

/** Display order: the coarse wheel, its trim, then the two HSV components. */
export const COLOUR_AXES: readonly ColourAxis[] = ['hue', 'hueFine', 'saturation', 'brightness']

/** The chip label — short, because a fixture row already carries a dozen chips. */
export const COLOUR_AXIS_LABELS: Record<ColourAxis, string> = {
  hue: 'hue',
  hueFine: 'hue fine',
  saturation: 'sat',
  brightness: 'bright',
}

/** The picker's label, where there is room to say what the axis is. */
export const COLOUR_AXIS_LONG_LABELS: Record<ColourAxis, string> = {
  hue: 'Hue',
  hueFine: 'Hue — fine trim (±½ step)',
  saturation: 'Saturation',
  brightness: 'Brightness',
}

/**
 * The chip swatches. Hue is the design's rainbow (`midi-surface-design/Edit.dc.html`); the trim
 * is a narrow slice of the same wheel; saturation runs grey → colour and brightness black → white,
 * which is what each fader does.
 */
export const COLOUR_AXIS_SWATCHES: Record<ColourAxis, string> = {
  hue: 'linear-gradient(90deg,#f43f5e,#3b82f6)',
  hueFine: 'linear-gradient(90deg,#f43f5e,#f97316)',
  saturation: 'linear-gradient(90deg,#d4d4d8,#ef4444)',
  brightness: 'linear-gradient(90deg,#18181b,#fafafa)',
}

/** Is this the default axis — null, absent, or spelled out as `'hue'`? */
export function isPlainHue(axis: ColourAxis | null | undefined): boolean {
  return axis == null || axis === 'hue'
}

/** The axis a nullable one means. */
export function effectiveAxis(axis: ColourAxis | null | undefined): ColourAxis {
  return axis ?? 'hue'
}

/** Do two axes mean the same thing, null and `'hue'` included? */
export function sameAxis(
  a: ColourAxis | null | undefined,
  b: ColourAxis | null | undefined,
): boolean {
  return effectiveAxis(a) === effectiveAxis(b)
}

/** ` · sat` for a non-default axis, `''` for hue — what `describeTarget` appends to a property. */
export function axisSuffix(axis: ColourAxis | null | undefined): string {
  return isPlainHue(axis) ? '' : ` · ${COLOUR_AXIS_LABELS[axis as ColourAxis]}`
}

/**
 * [target] with [axis] set, or with the field **absent** for hue. Absent rather than null or
 * `'hue'`: absent is what every pre-axis row has, what the server writes for a hue binding, and
 * what makes a derived target `toEqual` a hand-built one in the tests.
 */
export function withAxis<T extends { colourAxis?: ColourAxis | null }>(
  target: T,
  axis: ColourAxis | null | undefined,
): T {
  if (isPlainHue(axis)) {
    const { colourAxis: _dropped, ...rest } = target
    void _dropped
    return rest as T
  }
  return { ...target, colourAxis: axis as ColourAxis }
}

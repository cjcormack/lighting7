import type { PropertyDescriptor, SettingOption, SettingPropertyDescriptor } from '../store/fixtures'
import { findGel, type GelIndex } from './gels'

/**
 * What is loaded into one slot of a unit (fixture optics plan session 3, D6): a gel by its library
 * code, or a gobo by its pattern name. Neither is an **empty** slot — fitted, with nothing in it —
 * which is not the same as no entry, which leaves the type's stock content.
 */
export interface FittedSlot {
  gel?: string | null
  gobo?: string | null
}

/**
 * A unit's **fitted media**: what is loaded in its loadable settings, keyed by the setting's
 * property name and then the option's name, naming only the options that differ from the type's
 * stock. On a patch and on each extra placement (`FixturePatch.media`, `PatchPlacement.media`), so
 * two units of one type carry different strings. lighting7's `fixture/media/FittedMedia.kt` is the
 * other half and the write boundary.
 */
export interface FittedMedia {
  slots?: Record<string, Record<string, FittedSlot>>
}

/** An open frame's colour — the stock string's open frames, and what an empty fitted slot draws. */
export const OPEN_WHITE = '#FFFFFF'

/** Whether `media` fits anything at all. */
export function hasFittedMedia(media: FittedMedia | null | undefined): media is FittedMedia {
  return Object.values(media?.slots ?? {}).some((options) => Object.keys(options).length > 0)
}

/**
 * A placement's media layered over its patch's, **option by option**: an option the placement
 * names is the placement's, every other the patch's, every other again the type's stock (which the
 * overlay below supplies). The resolution order `patchAtPlacement` draws a placement with.
 */
export function mediaOver(
  own: FittedMedia | null | undefined,
  base: FittedMedia | null | undefined,
): FittedMedia | null {
  if (!hasFittedMedia(own)) return hasFittedMedia(base) ? base : null
  if (!hasFittedMedia(base)) return own
  const slots: Record<string, Record<string, FittedSlot>> = {}
  for (const name of new Set([...Object.keys(base.slots ?? {}), ...Object.keys(own.slots ?? {})])) {
    slots[name] = { ...(base.slots?.[name] ?? {}), ...(own.slots?.[name] ?? {}) }
  }
  return { slots }
}

/**
 * One option as the unit holds it: a fitted gel is its library colour (a code the library does not
 * hold — an archive from a desk whose library was newer — keeps the stock), a fitted gobo is the
 * pattern and no colour, an empty fitted slot is open white with no pattern, and an option the unit
 * has not fitted is the stock. Mirrors `colourOf` / `goboOf` in `FittedMedia.kt`.
 */
export function fittedOption(option: SettingOption, slot: FittedSlot | undefined, gels: GelIndex): SettingOption {
  if (!slot) return option
  if (slot.gel) {
    const gel = findGel(gels, slot.gel)
    return { ...option, colourPreview: gel?.color ?? option.colourPreview, gobo: undefined }
  }
  if (slot.gobo) return { ...option, colourPreview: undefined, gobo: slot.gobo }
  return { ...option, colourPreview: OPEN_WHITE, gobo: undefined }
}

/**
 * The type's property descriptors as **this unit** holds them: every loadable setting's options
 * overlaid with the unit's fitted media, the rest untouched. Returns `properties` itself when
 * nothing is fitted, so a memo keyed on the result does not churn for the common case.
 *
 * The overlay is the client's on purpose. A descriptor is per fixture, and a fixture with extra
 * placements is several units — two lanterns on one dimmer, each its own scroller string — so only
 * the surface that draws each placement can know which unit it is drawing. The desk resolves the
 * same order itself for what it answers alone (the template snap, Locate, `describe_rig`).
 */
export function fittedProperties<P extends PropertyDescriptor>(
  properties: P[] | undefined,
  media: FittedMedia | null | undefined,
  gels: GelIndex,
): P[] | undefined {
  if (!properties || !hasFittedMedia(media)) return properties
  let changed = false
  const out = properties.map((p) => {
    if (p.type !== 'setting' || !(p as SettingPropertyDescriptor).media) return p
    const fitted = media.slots?.[p.name]
    if (!fitted || Object.keys(fitted).length === 0) return p
    changed = true
    const setting = p as SettingPropertyDescriptor
    return { ...setting, options: setting.options.map((o) => fittedOption(o, fitted[o.name], gels)) } as P
  })
  return changed ? out : properties
}

/** Whether a loadable setting's slots take a gel — a scroller, a media frame, a module wheel. */
export function takesGel(setting: SettingPropertyDescriptor): boolean {
  return setting.media === 'GEL' || setting.media === 'GOBO_OR_GEL'
}

/** Whether a loadable setting's slots take a gobo. */
export function takesGobo(setting: SettingPropertyDescriptor): boolean {
  return setting.media === 'GOBO' || setting.media === 'GOBO_OR_GEL'
}

/** A type's loadable settings, in descriptor order. Empty for an older desk, which sends no `media`. */
export function loadableSettings(properties: PropertyDescriptor[] | undefined): SettingPropertyDescriptor[] {
  return (properties ?? []).filter(
    (p): p is SettingPropertyDescriptor => p.type === 'setting' && !!(p as SettingPropertyDescriptor).media,
  )
}

/**
 * The colour filters a unit's beam passes through besides its colour source: every **other**
 * loadable setting that takes a gel — a media frame's wing, a module wheel's dichroic. Each
 * multiplies the beam's colour by its current option's colour (`colourPreview`, after the overlay),
 * and an option with none — out of the beam, an empty slot, a gobo — passes the beam unchanged.
 */
export function mediaFilters(
  properties: PropertyDescriptor[] | undefined,
  colourSourceName: string | undefined,
): SettingPropertyDescriptor[] {
  return loadableSettings(properties).filter((s) => s.name !== colourSourceName && takesGel(s))
}

/**
 * Every colour filter a unit's beam passes through besides its colour source, as both dispatches
 * apply them (`filterColour`): its **other colour wheels**, in channel order, then its media filters
 * ([mediaFilters]).
 *
 * A second colour wheel (fixture-optics plan session 4 — the Robe ColorSpot 575's, whose deep and
 * corrective dichroics sit in series with the first wheel's) is a filter exactly as a media frame's
 * gel is: its current slot's colour multiplies the beam's, subtractively, and a slot with none —
 * open white, a scroll band — passes it unchanged. So it joins this path rather than standing
 * beside it: one multiply, one per-filter subscription on each dispatch, one parity test. Only
 * where the colour source is itself a **wheel** (a COLOUR setting): beside an RGB colour property a
 * COLOUR setting is a preset or a macro on the same emitters (the Hex's, the Orbit's), not glass in
 * the beam, and multiplying by it would be wrong.
 */
export function colourFilters(
  properties: PropertyDescriptor[] | undefined,
  colourSource: { type: 'colour' | 'setting'; property: { name: string } } | undefined,
): SettingPropertyDescriptor[] {
  const media = mediaFilters(properties, colourSource?.property.name)
  if (colourSource?.type !== 'setting') return media
  const wheels = (properties ?? [])
    .filter(
      (p): p is SettingPropertyDescriptor =>
        p.type === 'setting' && p.category === 'colour' && p.name !== colourSource.property.name,
    )
    .sort((a, b) => a.channel.universe - b.channel.universe || a.channel.channelNo - b.channel.channelNo)
  if (wheels.length === 0) return media
  return [...wheels, ...media.filter((m) => !wheels.includes(m))]
}

/** `#rrggbb` (or `#rgb`) → `[r, g, b]` 0–255, or null for anything else. */
function parseHex(hex: string): [number, number, number] | null {
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return null
  const h = m[1].length === 3 ? m[1].split('').map((c) => c + c).join('') : m[1]
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}

/**
 * `hex` passed through each filter colour — subtractive, as gels in series are: the channels
 * multiply. A filter that is not a `#rrggbb` colour is skipped, and so is a base that is not one
 * (the beam keeps it unfiltered rather than drawing black for want of data).
 */
export function filterColour(hex: string, filters: readonly (string | undefined)[]): string {
  const live = filters.filter((f): f is string => !!f)
  if (live.length === 0) return hex
  const base = parseHex(hex)
  if (!base) return hex
  const out = live.reduce<[number, number, number]>((acc, f) => {
    const c = parseHex(f)
    return c ? [acc[0] * (c[0] / 255), acc[1] * (c[1] / 255), acc[2] * (c[2] / 255)] : acc
  }, base)
  return '#' + out.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')
}

/** The media as the desk stores it: empty option maps dropped, nothing fitted as null. */
export function normaliseMedia(media: FittedMedia | null | undefined): FittedMedia | null {
  if (!hasFittedMedia(media)) return null
  const slots: Record<string, Record<string, FittedSlot>> = {}
  for (const [name, options] of Object.entries(media.slots ?? {})) {
    if (Object.keys(options).length === 0) continue
    slots[name] = Object.fromEntries(
      Object.entries(options).map(([option, slot]) => [option, slotOf(slot)]),
    )
  }
  return { slots }
}

/** A slot as stored: only its set field, an empty slot `{}`. */
function slotOf(slot: FittedSlot): FittedSlot {
  if (slot.gel) return { gel: slot.gel }
  if (slot.gobo) return { gobo: slot.gobo }
  return {}
}

/** Whether two media say the same thing to the desk — absent, null and nothing fitted alike. */
export function mediaEqual(a: FittedMedia | null | undefined, b: FittedMedia | null | undefined): boolean {
  const x = normaliseMedia(a)?.slots ?? {}
  const y = normaliseMedia(b)?.slots ?? {}
  const names = new Set([...Object.keys(x), ...Object.keys(y)])
  for (const name of names) {
    const xo = x[name] ?? {}
    const yo = y[name] ?? {}
    const options = new Set([...Object.keys(xo), ...Object.keys(yo)])
    for (const option of options) {
      const xs = xo[option]
      const ys = yo[option]
      if (!xs || !ys) return false
      if ((xs.gel ?? null) !== (ys.gel ?? null) || (xs.gobo ?? null) !== (ys.gobo ?? null)) return false
    }
  }
  return true
}

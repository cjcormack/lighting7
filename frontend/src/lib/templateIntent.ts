import type { AttributeFamily } from './attributeFamily'

/**
 * The template intent grammar, client side — **serialise and parse only**.
 *
 * A mirror of `fx/TemplateIntent.kt`, and deliberately a narrow one: it builds the string the editor
 * saves and reads the string a library row displays, and it does **not** resolve anything. Resolution
 * — the white/amber policy, the wheel snap and its ΔE, the degrees-to-DMX conversion, the per-head
 * clamp — lives in Kotlin alone, behind `POST /templates/resolve`, because the editor's job is to
 * promise what the rig will actually do and two implementations of that promise would drift. See
 * §6 of `desk-simplification-plan.md`.
 *
 * Keep in step with `TemplateIntent.kt`. `templateIntent.test.ts` pins the vocabulary against the
 * backend's list the way `maskPicker.test.ts` pins the family list.
 */

/**
 * How a colour intent uses a head's white / amber emitters, on the heads that have them.
 *
 * The *derived* half of a template's emitter story. The other half is an explicit `white` / `amber`
 * / `uv` row carrying a [level] intent, and the two must not both drive one emitter: an explicit
 * white or amber row forces the colour row to `rgbonly`, which the editor enforces and the backend
 * refuses by name. UV is exempt — no policy has ever driven it.
 */
export type WhitePolicy = 'extract' | 'additive' | 'rgbonly'

export const WHITE_POLICIES: readonly WhitePolicy[] = ['extract', 'additive', 'rgbonly']

export const WHITE_POLICY_LABELS: Record<WhitePolicy, { label: string; hint: string }> = {
  extract: {
    label: 'Extract',
    hint: 'Pulls the neutral part of the colour into the white emitter — brighter and cleaner, and the sensible default for a wash.',
  },
  additive: {
    label: 'Additive',
    hint: 'Drives the white emitter alongside RGB rather than instead of part of it.',
  },
  rgbonly: {
    label: 'RGB only',
    hint: 'Leaves the extra emitters at zero, for when a rig is being matched to camera.',
  },
}

export type TemplateIntent =
  | { kind: 'colour'; hex: string; policy: WhitePolicy }
  /**
   * A DMX byte, 0–255 — the bundled colour emitters only, and the one literal in this grammar.
   *
   * Deliberate: the rest of the grammar exists so a value can mean the same thing on heads that
   * differ, and an emitter has nothing to re-derive. It is one LED, its slider is 0–255 on every
   * head in this rig, and the operator setting it is matching a colour by eye. What *is* per head —
   * whether the emitter exists at all — is answered by resolution, not by the value.
   */
  | { kind: 'level'; value: number }
  /** A proportion of whatever range the target property has, 0–100. */
  | { kind: 'percent'; value: number }
  /** Pan and tilt in **degrees**, resolved through each head's own annotated range. */
  | { kind: 'position'; panDeg: number; tiltDeg: number }
  /** A two-state beam role — prism in or out. */
  | { kind: 'switch'; on: boolean }

/**
 * The closed property vocabulary, mirroring `TemplateProperty` in Kotlin.
 *
 * An allow-list, which is where "a template cannot carry a gobo" lives on this side too: the editor
 * offers these and nothing else, so the backend's refusal is a backstop rather than the only guard.
 * `strobe` is **intensity**, not beam — it is an intensity modulation, HTP like a dimmer.
 */
export interface TemplateProperty {
  propertyName: string
  family: AttributeFamily
  label: string
  intent: TemplateIntent['kind']
}

export const TEMPLATE_PROPERTIES: readonly TemplateProperty[] = [
  { propertyName: 'dimmer', family: 'INTENSITY', label: 'Level', intent: 'percent' },
  { propertyName: 'strobe', family: 'INTENSITY', label: 'Strobe', intent: 'percent' },
  { propertyName: 'position', family: 'POSITION', label: 'Position', intent: 'position' },
  { propertyName: 'rgbColour', family: 'COLOUR', label: 'Colour', intent: 'colour' },
  { propertyName: 'white', family: 'COLOUR', label: 'White', intent: 'level' },
  { propertyName: 'amber', family: 'COLOUR', label: 'Amber', intent: 'level' },
  { propertyName: 'uv', family: 'COLOUR', label: 'UV', intent: 'level' },
  { propertyName: 'zoom', family: 'BEAM', label: 'Zoom', intent: 'percent' },
  { propertyName: 'focus', family: 'BEAM', label: 'Focus', intent: 'percent' },
  { propertyName: 'iris', family: 'BEAM', label: 'Iris', intent: 'percent' },
  { propertyName: 'frost', family: 'BEAM', label: 'Frost', intent: 'percent' },
  { propertyName: 'prism', family: 'BEAM', label: 'Prism', intent: 'switch' },
]

/**
 * The roles a template **cannot** carry, with the reason, so the beam editor can show them disabled
 * rather than omitting them.
 *
 * `BeamColour.dc.html`'s point, and worth keeping: an operator looking for gobo needs to learn
 * *where* it lives, not conclude the desk cannot do it.
 */
export const TEMPLATE_EXCLUSIONS: readonly { label: string; reason: string }[] = [
  { label: 'Gobo', reason: 'Wheel slots are per-model — “gobo 3” is a different pattern on every head. Record a look instead.' },
  { label: 'Gobo rotation', reason: 'Tied to the gobo wheel it belongs to, so it cannot travel between models.' },
  { label: 'Colour wheel slot', reason: 'A colour template already picks the nearest slot per head; naming one would fix it to a single model.' },
  { label: 'Macros', reason: 'A macro channel means something different on every fixture.' },
]

export function templatePropertyFor(propertyName: string): TemplateProperty | null {
  const canonical = canonicalTemplateProperty(propertyName)
  return TEMPLATE_PROPERTIES.find((p) => p.propertyName === canonical) ?? null
}

export function templatePropertiesForFamily(family: AttributeFamily): TemplateProperty[] {
  return TEMPLATE_PROPERTIES.filter((p) => p.family === family)
}

/** Mirrors the backend's `canonicalPropertyName` for the one alias that matters here. */
function canonicalTemplateProperty(propertyName: string): string {
  const lower = propertyName.trim().toLowerCase()
  if (lower === 'colour' || lower === 'color' || lower === 'rgbcolour') return 'rgbColour'
  return propertyName.trim()
}

export function serializeTemplateIntent(intent: TemplateIntent): string {
  switch (intent.kind) {
    case 'colour':
      return `${intent.hex.toUpperCase()};policy=${intent.policy}`
    case 'level':
      // Clamped on the way out as well as the way in. The editor's slider cannot leave 0–255, but a
      // value written out of range would only be corrected the *next* time the row was parsed — so
      // what was saved and what is redisplayed would disagree, with nothing reporting it.
      return `dmx:${Math.min(255, Math.max(0, Math.round(intent.value)))}`
    case 'percent':
      return `pct:${trimNumber(intent.value)}`
    case 'position':
      return `deg:${trimNumber(intent.panDeg)},${trimNumber(intent.tiltDeg)}`
    case 'switch':
      return intent.on ? 'on' : 'off'
  }
}

/**
 * Parse a stored value, or null when it is not an intent.
 *
 * Null is a real answer: a hand-edited or corrupt row produces it, and a caller should say so rather
 * than guess. Note a colour with **no** policy token reads as `rgbonly`, matching Kotlin — and
 * matching what every other reader of that string already does with it.
 */
export function parseTemplateIntent(raw: string): TemplateIntent | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null

  if (trimmed.startsWith('#')) {
    const [head, ...rest] = trimmed.split(';')
    const hex = head.trim()
    if (!/^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(hex)) return null
    const token = rest
      .map((t) => t.trim().toLowerCase())
      .find((t) => t.startsWith('policy='))
      ?.slice('policy='.length)
    const policy = WHITE_POLICIES.find((p) => p === token) ?? 'rgbonly'
    return { kind: 'colour', hex, policy }
  }

  const lower = trimmed.toLowerCase()
  if (lower === 'on') return { kind: 'switch', on: true }
  if (lower === 'off') return { kind: 'switch', on: false }

  if (lower.startsWith('dmx:')) {
    const value = strictNumber(lower.slice(4))
    if (value == null) return null
    return { kind: 'level', value: Math.min(255, Math.max(0, Math.round(value))) }
  }
  if (lower.startsWith('pct:')) {
    const value = strictNumber(lower.slice(4))
    if (value == null) return null
    return { kind: 'percent', value: Math.min(100, Math.max(0, value)) }
  }
  if (lower.startsWith('deg:')) {
    const axes = lower.slice(4).split(',')
    if (axes.length !== 2) return null
    const panDeg = strictNumber(axes[0])
    const tiltDeg = strictNumber(axes[1])
    if (panDeg == null || tiltDeg == null) return null
    return { kind: 'position', panDeg, tiltDeg }
  }
  return null
}

/**
 * `Number()`, but null for an empty string.
 *
 * Not fussiness: `Number('')` is **0**, where Kotlin's `toDoubleOrNull()` is null — so a plain
 * `Number` here would read `pct:` as 0% while the backend rejects the row, and the two halves of one
 * grammar would disagree about whether a value exists. `templateIntent.test.ts` caught exactly that.
 */
function strictNumber(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  const value = Number(trimmed)
  return Number.isFinite(value) ? value : null
}

/** A one-line operator-facing rendering of an intent, for a library row. */
export function describeTemplateIntent(raw: string): string {
  const intent = parseTemplateIntent(raw)
  if (intent == null) return raw
  switch (intent.kind) {
    case 'colour':
      return `${intent.hex.toUpperCase()} · ${WHITE_POLICY_LABELS[intent.policy].label}`
    case 'level':
      return String(intent.value)
    case 'percent':
      return `${trimNumber(intent.value)}%`
    case 'position':
      return `${trimNumber(intent.panDeg)}° / ${trimNumber(intent.tiltDeg)}°`
    case 'switch':
      return intent.on ? 'On' : 'Off'
  }
}

/** The swatch colour for a row, or null when the row is not a colour. */
export function templateIntentSwatch(raw: string): string | null {
  const intent = parseTemplateIntent(raw)
  return intent?.kind === 'colour' ? intent.hex : null
}

/**
 * A whole template's rows in one line — `#FF9D4A · RGB only · White 180`.
 *
 * The tooltip and title text beside [templateRowsSwatch]'s dot. Naming only the first row was
 * accurate while a colour template held exactly one; it now hides the very rows an operator added a
 * template for.
 *
 * `rows` is optional for the same reason [templateRowsSwatch]'s is: an effect template's rows are
 * empty by construction, and `encodeDefaults = false` drops that empty collection off the wire
 * instead of sending `[]`. Absent reads as empty, same as there.
 */
export function describeTemplateRows(
  rows: readonly { propertyName: string; value: string }[] | undefined,
): string {
  return (rows ?? [])
    .map((row) => {
      const property = templatePropertyFor(row.propertyName)
      const described = describeTemplateIntent(row.value)
      // The colour row's own description already says what it is; an emitter's is a bare number and
      // needs its name in front of it.
      return property != null && property.intent === 'level'
        ? `${property.label} ${described}`
        : described
    })
    .join(' · ')
}

/** The bundled emitters a template may name outright, in the order every surface shows them. */
export const EMITTER_PROPERTIES: readonly string[] = ['white', 'amber', 'uv']

/**
 * The tint each emitter is drawn in — a swatch dot, a slider track, a pad face.
 *
 * Lives here rather than in a component so the five surfaces that draw an emitter agree. The values
 * are `ColourEditorBody`'s, which had them first.
 */
export const EMITTER_TINTS: Record<string, string> = {
  white: '#fffbe6',
  amber: '#ffbf00',
  uv: '#7f00ff',
}

/**
 * The swatch for a whole template's rows — the colour row's hex, or an emitter's tint.
 *
 * **Not `rows[0]`**, which is what every caller did while a colour template could hold exactly one
 * row. A template may now hold a hex *and* explicit emitters, and row order is authoring order: a
 * template whose `uv` row happens to sort first would otherwise be drawn purple under a name whose
 * colour is amber. An emitter-only template has no hex to draw, so it falls back to the tint of the
 * first emitter it names — which is the whole of what it asserts, not a stand-in for it.
 */
export function templateRowsSwatch(
  rows: readonly { propertyName: string; value: string }[] | undefined,
): string | null {
  // **Absent is empty, and absent is a real wire shape.** `TemplateSummary.rows` is declared
  // required, but the desk's WebSocket `Json` sets `encodeDefaults = false`, so an **effect**
  // template — whose rows are empty by construction — arrives over a WS frame with no `rows` field
  // at all. `hand.state` is the frame that embeds one, and `HandChip` draws it through
  // `padFaceOf` on every route, so reading `rows.find` off `undefined` took the whole app down
  // behind the router's error boundary for as long as the desk held an effect template.
  // The honest end of that fix is `@EncodeDefault(ALWAYS)` on the backend field, the way
  // `ProgrammerLayerStateOutMessage.applied` already does it; this guard is what makes the client
  // degrade instead of crash, and it is right on its own terms — no rows and no colour row are the
  // same question to a swatch.
  if (rows == null) return null
  const colourRow = rows.find((row) => templatePropertyFor(row.propertyName)?.intent === 'colour')
  if (colourRow != null) return templateIntentSwatch(colourRow.value)
  // Compared against the row's own spelling rather than through `templatePropertyFor`: an emitter
  // has no aliases (only the three colour spellings collapse), so the lookup could only ever hand
  // back the string it was given.
  const emitter = EMITTER_PROPERTIES.find((name) => rows.some((row) => row.propertyName === name))
  return emitter != null ? EMITTER_TINTS[emitter] : null
}

function trimNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

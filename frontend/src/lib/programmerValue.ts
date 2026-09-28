import {
  NAMED_COLOURS,
  hexToRgb,
  parseExtendedColour,
  rgbToHex,
  serializeExtendedColour,
} from '../components/fx/colourUtils'

/**
 * The canonical assignment-value string form shared by cue assignments and the programmer
 * (`Layer3Resolver.PropertyValue.serialize()` / `parseAssignmentValue()` in lighting7):
 *
 * - sliders and settings — `"0".."255"`
 * - colours — `"#rrggbb"` with optional `;wNNN` / `;aNNN` / `;uvNNN` tags, emitted only for
 *   non-zero components
 * - position — `"pan,tilt"`, each `0..255`
 *
 * We need to read it (not just write it) because of **blind**: while the programmer's
 * contribution is gated out of the merge, the staged value exists only in `programmer.state`
 * — the wire values the sheet normally displays still show the layers underneath.
 *
 * **One rule covers every reference form: this parser reads literals only.**
 *
 * There is nothing left for it to resolve. `"ref:{uuid}"` — the named-palette reference — retired
 * in session 4 and is now actively *rejected* at the Look write boundary, which is the no-nesting
 * guarantee. The positional `"P1"` / `"P*"` colour-list form retired with it. Neither can reach a
 * programmer entry or a cue row any more, so both are simply unparseable strings here, handled by
 * the same fallback as junk: return null and let the caller sample live DMX.
 *
 * The one reference grammar still alive is `tmpl:{uuid}`, and it is legal only inside an **FX
 * colour parameter**, never as a value — `colourUtils.ts` owns it end to end and resolves it
 * itself. It never reaches this parser either.
 */
export type ProgrammerParsedValue =
  | { kind: 'level'; value: number }
  | { kind: 'colour'; r: number; g: number; b: number; w: number; a: number; uv: number }
  | { kind: 'position'; pan: number; tilt: number }

const LEVEL_RE = /^\d{1,3}$/
const POSITION_RE = /^(\d{1,3}),(\d{1,3})$/

/**
 * Parse a programmer/assignment value. Returns null for anything that doesn't match the
 * canonical grammar (a retired reference form, a future value form, or junk) — callers
 * fall back to the live DMX value rather than rendering a guess.
 */
export function parseProgrammerValue(value: string): ProgrammerParsedValue | null {
  const trimmed = value.trim()
  if (!trimmed) return null

  if (LEVEL_RE.test(trimmed)) {
    const level = Number(trimmed)
    return level <= 255 ? { kind: 'level', value: level } : null
  }

  const position = POSITION_RE.exec(trimmed)
  if (position) {
    const pan = Number(position[1])
    const tilt = Number(position[2])
    if (pan > 255 || tilt > 255) return null
    return { kind: 'position', pan, tilt }
  }

  // Everything else must be colour-shaped: `#rrggbb` / `#rgb`, or one of the backend's named
  // colours, either optionally followed by `;wNNN` etc.
  //
  // The check is deliberately against the *known* names rather than "is it alphabetic":
  // `resolveColourToHex` treats any three letters in a-f as hex shorthand and silently
  // returns black for everything else, so a looser gate turns junk into a confident-looking
  // swatch — "bad" becomes lilac, "gobo" becomes black — instead of falling back to the live
  // DMX value the way an unparseable value is supposed to.
  const head = trimmed.split(';')[0].trim()
  const isHex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(head)
  const isNamed = Object.hasOwn(NAMED_COLOURS, head.toLowerCase())
  if (!isHex && !isNamed) return null

  const extended = parseExtendedColour(trimmed)
  const { r, g, b } = hexToRgb(extended.hex)
  return { kind: 'colour', r, g, b, w: extended.white, a: extended.amber, uv: extended.uv }
}

/** Serialize a slider or setting level. */
export function serializeLevel(level: number): string {
  return String(Math.max(0, Math.min(255, Math.round(level))))
}

/** Serialize a position pair. */
export function serializePosition(pan: number, tilt: number): string {
  return `${serializeLevel(pan)},${serializeLevel(tilt)}`
}

/** Serialize a colour, emitting `w`/`a`/`uv` tags only for non-zero components. */
export function serializeColour(
  r: number,
  g: number,
  b: number,
  w = 0,
  a = 0,
  uv = 0,
): string {
  return serializeExtendedColour({ hex: rgbToHex(r, g, b), white: w, amber: a, uv })
}

/**
 * Parse whatever a programmer entry should *display* as.
 *
 * A thin wrapper over [parseProgrammerValue] now, and kept as its own function because its callers
 * hold a whole entry: one entry-shaped reader is what stops them re-forking on `entry.value` the
 * next time the entry grows a field that matters.
 *
 * It used to branch: an entry's `value` could be `ref:{uuid}`, in which case the displayed literal
 * came from the entry's `resolvedValue` — the value the backend resolved that reference to for this
 * specific target and property, which mattered because a position palette gives every head a
 * different answer. The `ref:` value grammar retired in session 4 of the looks-and-layers plan, and
 * with it `PALETTE_REF_PREFIX` / `parsePaletteRefUuid` / `isPaletteRefValue` / `serializePaletteRef`.
 * The positional `P1` grammar went too. What replaced neither of them but occupies the same
 * ground is `tmpl:{uuid}` in `components/fx/colourUtils.ts` — an FX *parameter* grammar, not a
 * value grammar, pinned by `colourUtils.test.ts`.
 */
export function parseProgrammerEntryValue(entry: { value: string }): ProgrammerParsedValue | null {
  return parseProgrammerValue(entry.value)
}

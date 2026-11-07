/**
 * The recent-value keys and how each value is spelled (programmer-fixes plan D15), mirroring
 * lighting7 `state/RecentValues.kt`, which drops anything else. Each is recorded from an editor's
 * commit and replayed through the editor's own typed path.
 *
 * - `colour`: `#rrggbb` then `;wNNN` / `;aNNN` / `;uvNNN` for every emitter the editor wrote, zero
 *   included, so a white set to 0 replays as 0 rather than as "leave white".
 * - `level:<attribute>`: `pct:<0–100>`, a share of 255, as the grid reads a byte.
 * - `palette-level:<attribute>`: `pct:<0–100>`, a share of each head's own range.
 * - `position`: `pos:<pan>,<tilt>`, centre-relative, −128..127.
 * - `warmth`: `<kelvin>K;<0–100>`.
 */

export const RECENT_COLOUR = 'colour'
export const RECENT_POSITION = 'position'
export const RECENT_WARMTH = 'warmth'

export function recentLevelKey(attribute: string): string {
  return `level:${attribute}`
}

export function recentPaletteLevelKey(attribute: string): string {
  return `palette-level:${attribute}`
}

/** A colour as an editor writes it: an emitter left out is one the editor did not write. */
export interface RecentColour {
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
}

const EMITTERS = ['w', 'a', 'uv'] as const
const EMITTER_LABELS: Record<(typeof EMITTERS)[number], string> = { w: 'W', a: 'A', uv: 'UV' }

function byte(value: number): number {
  return Math.max(0, Math.min(255, Math.round(value)))
}

function byteHex(value: number): string {
  return byte(value).toString(16).padStart(2, '0')
}

/**
 * The `colour` spelling: lowercase hex and a tag per emitter written, zeros kept. Its own, not
 * `serializeExtendedColour` (which drops zeros) nor `rgbToHex` (uppercase).
 */
export function serializeRecentColour(colour: RecentColour): string {
  const tags = EMITTERS.flatMap((emitter) => {
    const value = colour[emitter]
    return value === undefined ? [] : [`${emitter}${byte(value)}`]
  })
  return [`#${byteHex(colour.r)}${byteHex(colour.g)}${byteHex(colour.b)}`, ...tags].join(';')
}

/** A `colour` value back, or null for one this side cannot read. */
export function parseRecentColour(value: string): RecentColour | null {
  const [hex, ...tags] = value.toLowerCase().split(';')
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/.exec(hex)
  if (match == null) return null
  const colour: RecentColour = { r: parseInt(match[1], 16), g: parseInt(match[2], 16), b: parseInt(match[3], 16) }
  for (const tag of tags) {
    const emitter = /^(w|a|uv)(\d{1,3})$/.exec(tag)
    if (emitter == null) return null
    const level = Number(emitter[2])
    if (level > 255) return null
    colour[emitter[1] as 'w' | 'a' | 'uv'] = level
  }
  return colour
}

/** A `pct:` value as its percent, or null. */
export function parseRecentPercent(value: string): number | null {
  const match = /^pct:(\d{1,3}(?:\.\d{1,2})?)$/.exec(value)
  if (match == null) return null
  const pct = Number(match[1])
  return pct <= 100 ? pct : null
}

export function serializeRecentPercent(pct: number): string {
  return `pct:${Math.round(Math.max(0, Math.min(100, pct)) * 100) / 100}`
}

/** A `pos:` value as its centre-relative pair, or null. */
export function parseRecentPosition(value: string): { pan: number; tilt: number } | null {
  const match = /^pos:(-?\d{1,3}),(-?\d{1,3})$/.exec(value)
  if (match == null) return null
  const pan = Number(match[1])
  const tilt = Number(match[2])
  return pan >= -128 && pan <= 127 && tilt >= -128 && tilt <= 127 ? { pan, tilt } : null
}

export function serializeRecentPosition(pan: number, tilt: number): string {
  return `pos:${Math.round(pan)},${Math.round(tilt)}`
}

/** A warmth value as its kelvin and level percent, or null. */
export function parseRecentWarmth(value: string): { kelvin: number; pct: number } | null {
  const match = /^(\d{3,5})K;(\d{1,3}(?:\.\d{1,2})?)$/.exec(value)
  if (match == null) return null
  const pct = Number(match[2])
  return pct <= 100 ? { kelvin: Number(match[1]), pct } : null
}

export function serializeRecentWarmth(kelvin: number, pct: number): string {
  return `${Math.round(kelvin)}K;${Math.round(Math.max(0, Math.min(100, pct)) * 100) / 100}`
}

const MINUS = '−'

function signed(n: number): string {
  return n < 0 ? `${MINUS}${-n}` : String(n)
}

/**
 * A chip's words: `62%`, `−20, 12`, `3200 K · 80%`; a colour's hex and each emitter it lights,
 * `#000000 · W 255`. Null for a value it cannot read.
 */
export function recentValueLabel(key: string, value: string): string | null {
  if (key === RECENT_COLOUR) {
    const colour = parseRecentColour(value)
    if (colour == null) return null
    const lit = EMITTERS.flatMap((emitter) => (colour[emitter] ? [`${EMITTER_LABELS[emitter]} ${colour[emitter]}`] : []))
    return [value.split(';')[0].toLowerCase(), ...lit].join(' · ')
  }
  if (key === RECENT_POSITION) {
    const position = parseRecentPosition(value)
    return position == null ? null : `${signed(position.pan)}, ${signed(position.tilt)}`
  }
  if (key === RECENT_WARMTH) {
    const warmth = parseRecentWarmth(value)
    return warmth == null ? null : `${warmth.kelvin} K · ${warmth.pct}%`
  }
  if (key.startsWith('level:') || key.startsWith('palette-level:')) {
    const pct = parseRecentPercent(value)
    return pct == null ? null : `${pct}%`
  }
  return null
}

/**
 * The `level:` key a slider records under, or null for one that is no level: a pan or a tilt (a
 * position has its own list), as the desk's own rule drops it.
 */
export function recentLevelKeyFor(attribute: { name: string; category: string }): string | null {
  return NOT_LEVELS.has(attribute.category) ? null : recentLevelKey(attribute.name)
}

const NOT_LEVELS = new Set(['pan', 'tilt', 'pan_fine', 'tilt_fine'])

/**
 * The `position` value for a pair of axes the editor's heads hold after a commit, centre-relative
 * per head: the pair, or null while either axis is mixed across them (D15) — a pan set over heads
 * whose tilts differ is no pair a tap could replay.
 */
export function recentPositionOf(heads: readonly { pan: number; tilt: number }[]): string | null {
  if (heads.length === 0) return null
  const [first] = heads
  if (heads.some((head) => head.pan !== first.pan || head.tilt !== first.tilt)) return null
  return serializeRecentPosition(first.pan, first.tilt)
}

/**
 * A `#rgb` or `#rrggbb` hex — either case, `#` optional — as a colour with no emitter stated, or
 * null. The FX and palette editors hold their colour as a hex.
 */
export function recentColourFromHex(hex: string): RecentColour | null {
  const digits = hex.trim().replace(/^#/, '').toLowerCase()
  const full = /^[0-9a-f]{3}$/.test(digits) ? digits.replace(/./g, (d) => d + d) : digits
  if (!/^[0-9a-f]{6}$/.test(full)) return null
  return { r: parseInt(full.slice(0, 2), 16), g: parseInt(full.slice(2, 4), 16), b: parseInt(full.slice(4, 6), 16) }
}

/** A colour's hex, `#rrggbb`. */
export function recentColourHex(colour: RecentColour): string {
  return serializeRecentColour({ r: colour.r, g: colour.g, b: colour.b })
}

import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value))
}

/** Comparator for entities ordered by `sortOrder` with `id` as a stable tiebreaker. */
export function bySortOrder<T extends { sortOrder: number; id: number }>(a: T, b: T): number {
  return a.sortOrder - b.sortOrder || a.id - b.id
}

/** Parse a number input value where empty string means "leave unset" (null). */
export function parseNullableNumber(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === "") return null
  const n = Number(trimmed)
  return Number.isFinite(n) ? n : null
}

/** Format a 3-tuple of nullable numbers (e.g. metres on X/Y/Z). Nulls render as `—`. */
export function formatTriple(
  a: number | null,
  b: number | null,
  c: number | null,
  sep = ", ",
): string {
  const fmt = (v: number | null) => (v == null ? "—" : v.toFixed(1))
  return `${fmt(a)}${sep}${fmt(b)}${sep}${fmt(c)}`
}

/** Format a yaw/pitch/roll triple in degrees as `0°/0°/0°`. Nulls render as `—`. */
export function formatRotation(
  yaw: number | null,
  pitch: number | null,
  roll: number | null,
): string {
  const fmt = (v: number | null) => (v == null ? "—" : `${v.toFixed(0)}°`)
  return `${fmt(yaw)}/${fmt(pitch)}/${fmt(roll)}`
}

/**
 * The marker every word `labelUnlessCompact` hides also carries — an inert hook, never styled by
 * Tailwind, so one ancestor can bring a whole row of words back at once.
 *
 * It exists because `compact` is a *boolean* and the question it answers stopped being one. The
 * programmer's folded row wraps when it cannot hold the source box, the verbs and these controls
 * on one line, and on the second line the controls own the full width — so the caller's "I know
 * something the query does not" is true on one line and false on the other, and no boolean can
 * say that. The reveal has to be a container query, and it has to sit on the row rather than in
 * here: Tailwind scans source *text*, so a breakpoint composed at runtime generates no CSS.
 *
 * Same reasoning as `AUTO_CUE_NUMBER_CLASS`: a class name used as a hook, not as a style.
 */
export const CONTROL_LABEL_CLASS = 'control-label'

/**
 * A control's word: hidden, unless the caller says it has room.
 *
 * The idiom five controls had a copy of — `Lit`, `Columns`, `Groups` and the scope band's two
 * pills — each pairing a `compact` flag from its caller with its own breakpoint. `compact` wins
 * outright, because it is a caller saying "I know something the query does not": on the
 * programmer's folded row the viewport is wide and the *row* is not.
 *
 * Except when that row has wrapped, which is what `CONTROL_LABEL_CLASS` above is for — the word
 * stays hidden here and an ancestor un-hides it, because only an ancestor is measured by the
 * query that can tell the two lines apart.
 *
 * `showAt` stays a literal at the call site rather than being computed here, and must: Tailwind
 * scans source text for class names, so a breakpoint assembled in this function would generate no
 * CSS and the word would never appear at any width.
 */
export function labelUnlessCompact(compact: boolean, showAt: string): string {
  return cn(CONTROL_LABEL_CLASS, 'hidden', !compact && showAt)
}

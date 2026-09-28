import { computeCombinedCss } from '@/lib/colourMath'
import { hexToRgb, parseExtendedColour } from '@/components/fx/colourUtils'
import { parseProgrammerValue } from '@/lib/programmerValue'

/**
 * Rendering one *stored Look literal*, which is the canonical assignment grammar and nothing else —
 * a Look row can never hold a `ref:` (the backend rejects that at the write boundary, because
 * nesting would make resolution recursive).
 *
 * Kept apart from the sheet's cell renderers deliberately. Those are editors bound to a fixture's
 * channel ranges; these are read-only chips that have no fixture in hand, so a position can only
 * ever be shown as its raw pan/tilt pair rather than as a crosshair in some fixture's range.
 *
 * Note what is *not* here any more: a family-typed chip. A Look declares no attribute type, so a
 * chip is rendered from the shape of the value in front of it — a colour literal gets a swatch
 * whatever the Look happens to also cover.
 */

/** The CSS colour a literal paints, or null when it isn't colour-shaped. */
function lookValueColourCss(value: string): string | null {
  const parsed = parseProgrammerValue(value)
  if (parsed?.kind !== 'colour') return null
  return computeCombinedCss(parsed.r, parsed.g, parsed.b, parsed.w, parsed.a, parsed.uv)
}

/**
 * A short operator-facing rendering of a literal: `128`, `40,215`, `#ff8800`.
 *
 * Levels stay raw 0..255 rather than becoming percentages — the stored value *is* the DMX level,
 * and rounding it to "50%" here would make it impossible to tell 127 from 128 in a Look whose whole
 * purpose is to be re-applied exactly.
 */
function describeLookValue(value: string): string {
  const parsed = parseProgrammerValue(value)
  if (!parsed) return value
  switch (parsed.kind) {
    case 'level':
      return String(parsed.value)
    case 'position':
      return `${parsed.pan},${parsed.tilt}`
    case 'colour': {
      const extended = parseExtendedColour(value)
      const { r, g, b } = hexToRgb(extended.hex)
      const tags = [
        extended.white ? `W${extended.white}` : '',
        extended.amber ? `A${extended.amber}` : '',
        extended.uv ? `UV${extended.uv}` : '',
      ].filter(Boolean)
      const hex = `#${[r, g, b].map((c) => c.toString(16).padStart(2, '0')).join('')}`
      return tags.length > 0 ? `${hex} ${tags.join(' ')}` : hex
    }
  }
}

/**
 * One literal as a chip.
 *
 * A colour gets a swatch because the colour *is* the information; everything else gets mono text,
 * because a level or a pan/tilt pair has no visual form that reads faster than the number. The
 * border on the swatch is what keeps a near-black value from vanishing into the card.
 */
export function LookValueChip({ value, className }: { value: string; className?: string }) {
  const css = lookValueColourCss(value)
  if (css) {
    return (
      <span
        className={`inline-block size-4 shrink-0 rounded-sm border border-border/60 ${className ?? ''}`}
        style={{ background: css }}
        title={describeLookValue(value)}
      />
    )
  }
  return (
    <span
      className={`inline-block shrink-0 rounded-sm bg-muted px-1 font-mono text-[10px] leading-4 text-muted-foreground ${className ?? ''}`}
      title={value}
    >
      {describeLookValue(value)}
    </span>
  )
}

/**
 * A Look's `preview` — up to eight distinct literals, most-frequent first — as a row of chips.
 *
 * Pre-resolved server-side precisely so a library page needs no per-row detail fetch; rendering it
 * from `rows` instead would mean N round trips to draw one page.
 */
export function LookPreviewSwatches({
  preview,
  className,
}: {
  preview: readonly string[]
  className?: string
}) {
  if (preview.length === 0) {
    return <span className="text-xs text-muted-foreground">Empty</span>
  }
  return (
    <div className={`flex flex-wrap items-center gap-1 ${className ?? ''}`}>
      {preview.map((value, index) => (
        <LookValueChip key={`${value}-${index}`} value={value} />
      ))}
    </div>
  )
}

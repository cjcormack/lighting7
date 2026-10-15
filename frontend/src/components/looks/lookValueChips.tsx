import { useLayoutEffect, useRef, useState } from 'react'
import { AudioWaveform } from 'lucide-react'
import { computeCombinedCss } from '@/lib/colourMath'
import { hexToRgb, parseExtendedColour } from '@/components/fx/colourUtils'
import { parseProgrammerValue } from '@/lib/programmerValue'
import { previewChipsThatFit } from './previewFit'
import { dmxToCentre, formatCentre } from '@/lib/positionScale'

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
 * A short operator-facing rendering of a literal: `128`, `-88,+87`, `#ff8800`.
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
      // Centre-relative, as every position reads (venue-visit plan D3). A Look's preview carries no
      // property names, so a head whose pan and tilt are separate sliders still shows two levels.
      return `${formatCentre(dmxToCentre(parsed.pan))},${formatCentre(dmxToCentre(parsed.tilt))}`
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

/** The gap between two items on the strip — `gap-1`, in px, for the fit. */
const STRIP_GAP_PX = 4

/** An effect type's id as words: `ColourCycle` → `Colour Cycle`. */
function effectTypeLabel(effectType: string): string {
  return effectType.replace(/([a-z0-9])([A-Z])/g, '$1 $2')
}

/**
 * A Look's Preview as the library sheet draws it: **one line that never wraps.** The chips that fit
 * the cell, then `+N` for the rest — every value is on the cell's hover — then, for a Look that runs
 * effects, the effects glyph. A Look of effects alone names them instead of showing a bare glyph.
 *
 * It wrapped until this: `LookPreviewSwatches`' `flex-wrap` in a 36px row put a Look's second and
 * third lines of chips over the rows above and below. What fits is measured off an invisible copy
 * of every chip ([previewChipsThatFit]) and re-measured as the column resizes; with no layout to
 * measure (a test, a cell not yet laid out) every chip is drawn and the cell clips them.
 */
export function LookPreviewStrip({
  preview,
  effectTypes,
  effectCount,
}: {
  preview: readonly string[]
  /** The Look's effect types, each once; absent from an older desk. */
  effectTypes?: readonly string[]
  effectCount: number
}) {
  const lineRef = useRef<HTMLDivElement>(null)
  const measureRef = useRef<HTMLDivElement>(null)
  const [shown, setShown] = useState(preview.length)
  const effectNames = (effectTypes ?? []).map(effectTypeLabel)
  const effectsTitle =
    effectCount > 0
      ? `${effectCount} effect${effectCount === 1 ? '' : 's'}${effectNames.length > 0 ? `: ${effectNames.join(', ')}` : ''}`
      : ''

  useLayoutEffect(() => {
    const line = lineRef.current
    const measure = measureRef.current
    if (!line || !measure) {
      setShown(preview.length)
      return
    }
    const fit = () => {
      const width = (selector: string) => measure.querySelector<HTMLElement>(selector)?.getBoundingClientRect().width ?? 0
      const chips = [...measure.querySelectorAll<HTMLElement>('[data-preview-chip]')].map(
        (chip) => chip.getBoundingClientRect().width,
      )
      const tail = width('[data-preview-effects]')
      const available = line.getBoundingClientRect().width - (tail > 0 ? tail + STRIP_GAP_PX : 0)
      setShown(previewChipsThatFit(chips, available, STRIP_GAP_PX, width('[data-preview-more]')))
    }
    fit()
    // Absent in some test environments — the one-off fit above still stands.
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(fit)
    observer.observe(line)
    return () => observer.disconnect()
  }, [preview, effectCount])

  if (preview.length === 0) {
    if (effectCount === 0) return null
    return (
      <span className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground" title={effectsTitle}>
        <AudioWaveform className="size-3.5 shrink-0 text-violet-400" aria-label="Effects only" />
        <span className="truncate">{effectNames.length > 0 ? effectNames.join(', ') : effectsTitle}</span>
      </span>
    )
  }

  const hidden = preview.length - shown
  const effectsGlyph =
    effectCount > 0 ? (
      <AudioWaveform className="size-3.5 shrink-0 text-violet-400" aria-label={effectsTitle} />
    ) : null

  return (
    <div
      ref={lineRef}
      data-preview-line
      className="relative flex min-w-0 flex-1 items-center gap-1 overflow-hidden whitespace-nowrap"
      title={[preview.join('  '), effectsTitle].filter(Boolean).join(' · ')}
    >
      {/* The measuring copy: every chip, a `+N` as wide as the widest, and the glyph. Absolute and
          invisible, so it takes no room and is never read; `aria-hidden` for the same reason. */}
      <div
        ref={measureRef}
        aria-hidden
        className="pointer-events-none invisible absolute left-0 top-0 flex items-center gap-1 whitespace-nowrap"
      >
        {preview.map((value, index) => (
          <span key={`${value}-${index}`} data-preview-chip className="inline-flex shrink-0">
            <LookValueChip value={value} />
          </span>
        ))}
        <MoreChip data-preview-more count={preview.length} />
        {effectCount > 0 && <span data-preview-effects className="inline-flex size-3.5 shrink-0" />}
      </div>
      {preview.slice(0, shown).map((value, index) => (
        <LookValueChip key={`${value}-${index}`} value={value} />
      ))}
      {hidden > 0 && <MoreChip count={hidden} />}
      {effectsGlyph}
    </div>
  )
}

/** `+N` for the chips that did not fit. */
function MoreChip({ count, ...rest }: { count: number; 'data-preview-more'?: boolean }) {
  return (
    <span {...rest} className="inline-block shrink-0 px-0.5 text-[10px] leading-4 text-muted-foreground">
      +{count}
    </span>
  )
}

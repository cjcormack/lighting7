import { cn } from '@/lib/utils'
import {
  COLOUR_DIGITS,
  FIXTURE_COLOURS,
  FIXTURE_COLOUR_LABELS,
  colourVar,
  type FixtureColour,
} from '@/lib/fixtureColours'

/**
 * The name mark's swatch (tables-colours plan D8): reads `--tag` from its row's `tagClass`, so a
 * table draws the colour without reading it per cell. `relative` to sit over the name cell's
 * selection overlay.
 */
export function TagMark({ colour, className }: { colour: FixtureColour; className?: string }) {
  return (
    <span
      role="img"
      aria-label={`Colour: ${FIXTURE_COLOUR_LABELS[colour]}`}
      title={FIXTURE_COLOUR_LABELS[colour]}
      data-tag-mark={colour}
      className={cn('relative size-2.5 shrink-0 rounded-[3px] bg-[var(--tag)]', className)}
    />
  )
}

/** One colour's swatch, outside a tagged row. */
export function ColourSwatch({ colour, className }: { colour: FixtureColour; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn('size-3.5 shrink-0 rounded-[4px]', className)}
      style={{ background: colourVar(colour) }}
    />
  )
}

/**
 * The ten colours as a 5 × 2 grid of swatches, each with its name and the digit that picks it
 * (`Colours.dc.html` §B's popover). A press picks; [value] is ringed.
 */
export function ColourSwatchGrid({
  value,
  onPick,
  disabled = false,
  className,
}: {
  value: FixtureColour | null
  onPick: (colour: FixtureColour) => void
  disabled?: boolean
  className?: string
}) {
  return (
    <div role="group" aria-label="Colours" className={cn('grid grid-cols-5 gap-2', className)}>
      {FIXTURE_COLOURS.map((colour) => {
        const on = colour === value
        return (
          <button
            key={colour}
            type="button"
            disabled={disabled}
            aria-pressed={on}
            data-colour={colour}
            title={`${FIXTURE_COLOUR_LABELS[colour]} (${COLOUR_DIGITS[colour]})`}
            onClick={() => onPick(colour)}
            className={cn(
              'group/swatch flex flex-col items-center gap-1 rounded-md p-0.5 text-[10px] text-muted-foreground outline-none',
              'focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50',
              on && 'text-foreground',
            )}
          >
            <span
              className={cn(
                'relative block size-8 rounded-lg',
                on && 'shadow-[0_0_0_2px_var(--popover),0_0_0_4px_var(--foreground)]',
              )}
              style={{ background: colourVar(colour) }}
            >
              <span className="absolute right-0.5 bottom-0 text-[9px] font-medium text-black/60 tabular-nums">
                {COLOUR_DIGITS[colour]}
              </span>
            </span>
            <span className="leading-none">{FIXTURE_COLOUR_LABELS[colour]}</span>
          </button>
        )
      })}
    </div>
  )
}

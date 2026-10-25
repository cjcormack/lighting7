import { createContext, useContext } from 'react'
import { Waves } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useFocusedPaletteLayer } from '@/components/programmer/FocusedPaletteLayer'
import { useProgrammerScope } from '@/components/programmer/ProgrammerScope'

/**
 * Where a position cell's *Spread…* opens the fan — row C's popover. Provided by the list container,
 * which holds it. The button sits in
 * the popup's footer, which only the programmer draws, so the plain lists and the cue grid have none.
 */
export const PositionFanContext = createContext<(() => void) | null>(null)

/**
 * *Spread…* in a position cell's popup footer, beside *Aim…* (venue-visit plan S12): opens Spread
 * on Position, in Fan, at no fan — the fan starts where the heads are, so it needs no seed, where a
 * colour's *Spread…* hands its colour over as From. The panel spreads over the marquee, which is
 * this cell's heads.
 *
 * A fan of one head moves nothing, so under two it refuses as row C's Spread does. Output is a read
 * of the cook and a focused palette layer a read of a palette, so both refuse with the words Spread
 * uses; a focused Look layer does not, since the fan lands in the layer's draft.
 */
export function PositionSpreadTool({ heads, onOpened }: { heads: number; onOpened?: () => void }) {
  const openFan = useContext(PositionFanContext)
  const scope = useProgrammerScope()
  const focusedPalette = useFocusedPaletteLayer()
  if (openFan == null) return null

  const refusal =
    scope?.kind === 'output'
      ? 'Output is a read of the cook — switch to Local to fan these heads'
      : focusedPalette != null
        ? 'This layer applies a palette — switch to Local to fan these heads'
        : heads < 2
          ? 'A fan needs two or more heads — select more cells in this column'
          : null

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="h-7 text-xs"
      disabled={refusal != null}
      title={refusal ?? 'Fan these heads about their own positions'}
      onClick={() => {
        openFan()
        onOpened?.()
      }}
    >
      <Waves className="size-3.5" />
      <span className="sr-only">Fan…</span>
    </Button>
  )
}

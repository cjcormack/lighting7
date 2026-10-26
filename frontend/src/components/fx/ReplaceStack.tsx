import { useCallback, useMemo } from 'react'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import type { CueTarget } from '@/api/cuesApi'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useActiveFxQuery, useFxLibraryQuery } from '@/store/fixtureFx'
import { findFxEntry } from '@/components/busking/buskingTypes'
import { familyOfFx, fxLanes, type FxCard, type FxLane } from '@/components/fxEditor/fxEditorList'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * **Replace · Stack** (FX editor plan D6; programmer-toolbar plan D8): what a bare FX press does to
 * the Local FX already on the heads. *Replace*, the default, is the press as it always was — the
 * desk takes those heads off the family's other Local FX; *Stack* adds it on top, on the family's
 * stacking blend, taking nothing off (`stack: true`).
 *
 * It is asked only where it changes something: where a Local FX of the family already reaches a
 * selected head ([localHolderIn]). An FX editor lane and the picker's FX arm ask it through one hook,
 * [useReplaceStack].
 */
export function ReplaceStack({
  stack,
  onChange,
  holder,
}: {
  stack: boolean
  onChange: (stack: boolean) => void
  /** What already runs there — *Rainbow Cycle on 6 heads already* — when the host knows it. */
  holder?: string | null
}) {
  return (
    <div data-replace-stack className="flex flex-col gap-1">
      {holder != null && <span className="text-[10.5px] text-muted-foreground">{holder}</span>}
      <ToggleGroup
        type="single"
        size="sm"
        value={stack ? 'stack' : 'replace'}
        aria-label="Over the Local FX already here"
        onValueChange={(v) => v && onChange(v === 'stack')}
        className="flex w-full"
      >
        <ToggleGroupItem value="replace" className="flex-1 text-xs" title="Take the selected heads off this family’s other Local FX">
          Replace
        </ToggleGroupItem>
        <ToggleGroupItem value="stack" className="flex-1 text-xs" title="Add it on top, taking nothing off">
          Stack
        </ToggleGroupItem>
      </ToggleGroup>
      <span data-replace-stack-note className="text-[10.5px] text-muted-foreground">
        {stack ? 'On top, on the family’s stacking blend — takes nothing off' : 'Takes these heads off it, as a press always has'}
      </span>
    </div>
  )
}

/**
 * The top Local FX of [family] on the selection, other than [except] — the one this picker session
 * started, which is not "already here". Null when there is none, so nothing need be asked.
 */
export function localHolderIn(lanes: readonly FxLane[], family: AttributeFamily, except?: number | null): FxCard | null {
  const lane = lanes.find((l) => l.family === family)
  return lane?.cards.find((c) => c.kind === 'local' && c.effect.id !== except) ?? null
}

const NO_LAYERS: ReadonlyMap<number, number> = new Map()

/**
 * [localHolderIn] for a door that draws no lanes of its own: the lanes over [targets] from the
 * running list alone, as the FX editor's list reads them before the desk's preview answers. Asked of
 * the **attribute** a tap lands on, whose family is the one the desk replaces in — a Pulse on `uv` is
 * Colour — and answering the holder's words for [ReplaceStack], or null where nothing need be asked.
 */
export function useLocalFxHolder(targets: readonly CueTarget[]): (attribute: string, except?: number | null) => string | null {
  const { fixtures } = useFixtureLookup()
  const { data: effects } = useActiveFxQuery()
  const { data: library } = useFxLibraryQuery()
  const lanes = useMemo(
    () =>
      fxLanes({
        effects: effects ?? [],
        targets,
        fixtures: fixtures ?? [],
        // Only the Local cards are asked about, and a layer's place orders layer cards alone.
        layerPosition: NO_LAYERS,
        answer: null,
      }),
    [effects, fixtures, targets],
  )
  return useCallback(
    (attribute, except) => {
      const card = localHolderIn(lanes, familyOfFx({ id: -1, attribute }, fixtures ?? []), except)
      return card == null ? null : holderWords(card, findFxEntry(library, card.effect.fxType)?.name ?? card.effect.fxType)
    },
    [fixtures, lanes, library],
  )
}

/** *Rainbow Cycle on all 6 heads already*, *on both heads*, *Pulse on 4 of 6 heads already*. */
export function holderWords(card: Pick<FxCard, 'reached' | 'of'>, name: string): string {
  const heads =
    card.of === 1 ? 'this head' : card.reached < card.of ? `${card.reached} of ${card.of} heads` : card.of === 2 ? 'both heads' : `all ${card.of} heads`
  return `${name} on ${heads} already`
}

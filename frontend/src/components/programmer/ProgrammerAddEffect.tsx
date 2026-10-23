import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApplyPicker } from '@/components/press/ApplyPicker'
import { useWindowEffectPair } from '@/components/fx/selectionEffectTarget'
import { useWindowPaletteFit, type PressPair } from '@/hooks/usePress'
import { useLookRowStore } from './LookRowStore'
import { useProgrammerScope } from './ProgrammerScope'
import { registerAddEffectDoor } from './addEffectDoor'

/** What `+ Effect` can do right now, and why not when it cannot. */
export interface AddEffectOffer {
  disabled: boolean
  /** The tooltip — a reason when disabled, a promise when not. */
  reason: string
  /** The selection pair the effect starts on: the window's, its targets in the grid's order. */
  pair: PressPair
}

/**
 * `+ Effect`, landing wherever the focused scope's values land.
 *
 * One rule, applied to effects as it is to values, rather than a special case:
 *
 * - **Local** — the bare effect press (`POST /programmer/effects`, apply-model plan §3.4): one
 *   Local effect over the window's selection under its mask, taking those heads off the family's
 *   other Local effects. Record then writes it onto the cue as an ad-hoc child.
 * - **a Look layer** — into that Look, as a `LookEffect`: started on `/fx/add` and absorbed when the
 *   picker closes, so the Look takes the effect the operator settled on at the settings they left
 *   it at, and no other effect loses a head to it (§3.2 rule 6). It travels with the Look, so every
 *   other layer applying it starts running the effect too.
 * - **Output** — disabled. Output is a read of what every entry produced together; it owns nothing,
 *   so there is nowhere for a new effect to go. The tooltip names the two places that can take one,
 *   because "disabled" on its own teaches nobody.
 *
 * A hook plus a picker rather than one button component: the FX table's *+ Effect* and ⌘K's
 * *Apply FX → On the selection* ([ProgrammerAddEffectDoor]) are two doors onto one gesture. The
 * authoring UI is the one picker's effects arm (`ApplyPicker`, `library="effects"`): `FxPicker` and
 * the live editor, above the effect palettes.
 */
export function useProgrammerAddEffect(): AddEffectOffer {
  const scope = useProgrammerScope()
  const store = useLookRowStore()

  // A **template layer takes no effect from here**: a palette holds one thing, fixed at creation
  // (fx-templates D1), and adding a *first* effect to a value palette would flip what it holds,
  // which is its identity. A focused template layer has no `LookRowStore` either, so without this
  // the button would offer to absorb an effect into nothing at all.
  const templateFocused = scope?.kind === 'layer' && store == null

  // The selection is the operator's own answer to "which heads?", in the grid's order: one row is
  // that fixture, cell or group alone, several are one effect spread over them in that order, as a
  // group's members are (effects plan D3).
  const pair = useWindowEffectPair(true)

  const reason =
    scope == null || scope.kind === 'output'
      ? 'Output is a read of everything composed together, so it owns nothing. Switch to Local for an FX on this cue, or focus a layer to put one in its look.'
      : templateFocused
        ? 'A palette holds one thing — a value, or one FX chosen when it was made. Edit it in the palette library, switch to Local for an FX on this cue, or focus a look layer to put one in its look.'
        : pair.targets.length === 0
          ? 'Select the heads the FX should drive first.'
          : scope.kind === 'layer'
            ? `Add an FX to ${store?.lookName ?? 'this look'} — every layer using it will run it`
            : 'Add an FX to the programmer. Record writes it onto the cue.'
  const disabled = scope == null || scope.kind === 'output' || templateFocused || pair.targets.length === 0

  return { disabled, reason, pair }
}

/**
 * The picker for `+ Effect`, mounted only while open — its pair is a marquee subscription and it
 * reads the fixture list.
 */
export function ProgrammerAddEffectSheet({
  projectId,
  open,
  offer,
  onClose,
}: {
  projectId: number
  open: boolean
  offer: AddEffectOffer
  onClose: () => void
}) {
  if (!open || offer.disabled) return null
  return <AddEffectPicker projectId={projectId} pair={offer.pair} onClose={onClose} />
}

function AddEffectPicker({ projectId, pair, onClose }: { projectId: number; pair: PressPair; onClose: () => void }) {
  const fit = useWindowPaletteFit()
  // One family masked opens the picker on it; several, or none, on the first the heads can take.
  const family = pair.families?.length === 1 ? pair.families[0] : null
  return (
    <ApplyPicker
      projectId={projectId}
      open
      onOpenChange={(next) => !next && onClose()}
      library="effects"
      family={family}
      pair={pair}
      fit={fit}
      offerNew={false}
    />
  )
}

/**
 * ⌘K's *Apply FX → On the selection* on the programmer (`addEffectDoor.ts`): *+ Effect*, whichever
 * table is showing. Mounted by the tables' frame for the page's life, it holds **no** selection
 * subscription until the door is asked — the offer is read then, and a refusal is said as a toast.
 */
export function ProgrammerAddEffectDoor({ projectId }: { projectId: number }) {
  const [asked, setAsked] = useState(0)
  useEffect(() => registerAddEffectDoor(() => setAsked((n) => n + 1)), [])
  const done = useCallback(() => setAsked(0), [])
  return asked === 0 ? null : <AskedAddEffect key={asked} projectId={projectId} onDone={done} />
}

function AskedAddEffect({ projectId, onDone }: { projectId: number; onDone: () => void }) {
  const offer = useProgrammerAddEffect()
  // The offer as it stood when the door was asked; a later move of the selection is the picker's.
  const asked = useRef(offer)
  useEffect(() => {
    if (!asked.current.disabled) return
    toast.warning(asked.current.reason)
    onDone()
  }, [onDone])
  return <ProgrammerAddEffectSheet projectId={projectId} open offer={offer} onClose={onDone} />
}

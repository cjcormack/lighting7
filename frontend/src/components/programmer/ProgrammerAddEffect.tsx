import { ApplyPicker } from '@/components/press/ApplyPicker'
import { useWindowEffectPair } from '@/components/fx/selectionEffectTarget'
import { useWindowPaletteFit, type PressPair } from '@/hooks/usePress'
import { useLookRowStore } from './LookRowStore'
import { useProgrammerScope } from './ProgrammerScope'

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
 * A hook plus a picker rather than one button component, since session 3 of the space plan: the
 * rail's footer and the collapsed strip's `+` menu are two doors onto one gesture, and the picker
 * has to be mounted once, above both, where it outlives the rail body. `ProgrammerRail` calls the
 * hook, hands the offer to both doors and mounts `ProgrammerAddEffectSheet` beside them. The
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
      ? 'Output is a read of everything composed together, so it owns nothing. Switch to Local for an effect on this cue, or focus a layer to put one in its look.'
      : templateFocused
        ? 'A palette holds one thing — a value, or one effect chosen when it was made. Edit it in the palette library, switch to Local for an effect on this cue, or focus a look layer to put one in its look.'
        : pair.targets.length === 0
          ? 'Select the heads the effect should drive first.'
          : scope.kind === 'layer'
            ? `Add an effect to ${store?.lookName ?? 'this look'} — every layer using it will run it`
            : 'Add an effect to the programmer. Record writes it onto the cue.'
  const disabled = scope == null || scope.kind === 'output' || templateFocused || pair.targets.length === 0

  return { disabled, reason, pair }
}

/**
 * The picker for `+ Effect`, mounted only while open — its pair is a marquee subscription and it
 * reads the fixture list, and the rail is always on screen.
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

import { useSelector } from 'react-redux'
import { AddEditFxSheet, type FxTarget } from '@/components/fx/AddEditFxSheet'
import { useFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import { useAbsorbLookEffectsMutation } from '@/store/looks'
import { selectTargetKeys } from '@/store/selectionSlice'
import { useLookRowStore } from './LookRowStore'
import { useProgrammerScope } from './ProgrammerScope'

/** What `+ Effect` can do right now, and why not when it cannot. */
export interface AddEffectOffer {
  disabled: boolean
  /** The tooltip — a reason when disabled, a promise when not. */
  reason: string
  /** The head the sheet is authored against; null is one of the disabled cases. */
  target: FxTarget | null
  /** Where a created effect goes after the sheet reports it. */
  onCreated: (effectId: number) => void
}

/**
 * `+ Effect`, landing wherever the focused scope's values land.
 *
 * One rule, applied to effects as it is to values, rather than a special case:
 *
 * - **a layer** — into that Look, as a `LookEffect`. It travels with the Look, so every other layer
 *   applying it starts running the effect too.
 * - **Local** — into the programmer band, which is what a busked effect is. Record then writes it
 *   onto the cue as an ad-hoc child. Local has no Look to put it in, and that is exactly right: an
 *   effect you want *here only* belongs to the cue.
 * - **Output** — disabled. Output is a read of what every entry produced together; it owns nothing,
 *   so there is nowhere for a new effect to go. The tooltip names the two places that can take one,
 *   because "disabled" on its own teaches nobody.
 *
 * A hook plus a sheet rather than one button component, since session 3 of the space plan: the
 * rail's footer and the collapsed strip's `+` menu are two doors onto one gesture, and the sheet
 * has to be mounted once, above both, where it outlives the rail body. `ProgrammerRail` calls
 * the hook, hands the offer to both doors and mounts `ProgrammerAddEffectSheet` beside them.
 *
 * The authoring UI is unchanged — `AddEditFxSheet` as it stands, which is deliberate: this changes
 * where an effect *lands*, not how a parameter is set. The layer case creates the instance in the
 * band exactly as Local does and then moves it, because that is the only order in which the effect
 * is a real running thing the server can describe rather than a form the client is guessing at.
 */
export function useProgrammerAddEffect(): AddEffectOffer {
  const scope = useProgrammerScope()
  const store = useLookRowStore()
  const [absorb] = useAbsorbLookEffectsMutation()
  const { data: fixtures } = useFixtureListQuery()
  const { data: groups } = useGroupListQuery()
  const selectedKeys = useSelector((s: Parameters<typeof selectTargetKeys>[0]) =>
    selectTargetKeys(s, 'programmer'),
  )

  // A **template layer takes no effect from here**, and the reason has changed rather than gone.
  // It used to be D7 — a template held no effects at all. A template may now hold one
  // (fx-templates D1), but exactly one, fixed at creation: there is no gesture for adding a second,
  // and adding a *first* to a value template would flip what it holds, which is its identity. A
  // focused template layer has no `LookRowStore` either, so without this the button would offer to
  // absorb an effect into nothing at all.
  const templateFocused = scope?.kind === 'layer' && store == null

  // An effect needs one target to be authored against. The selection is the operator's own answer
  // to "which heads?", and `AddEditFxSheet` offers the distribution controls once it knows whether
  // that target is multi-head — so a group beats a fixture where the selection is a group.
  const group = groups?.find((g) => selectedKeys.includes(g.name))
  const fixture = fixtures?.find((f) => selectedKeys.includes(f.key))
  const target: FxTarget | null = group
    ? { type: 'group', group }
    : fixture
      ? { type: 'fixture', fixture }
      : null

  const reason =
    scope == null || scope.kind === 'output'
      ? 'Output is a read of everything composed together, so it owns nothing. Switch to Local for an effect on this cue, or focus a layer to put one in its look.'
      : templateFocused
        ? 'A template holds one thing — a value, or one effect chosen when it was made. Edit it in the template library, switch to Local for an effect on this cue, or focus a look layer to put one in its look.'
        : target == null
          ? 'Select the heads the effect should drive first.'
          : scope.kind === 'layer'
            ? `Add an effect to ${store?.lookName ?? 'this look'} — every layer using it will run it`
            : 'Add an effect to the programmer. Record writes it onto the cue.'
  const disabled = scope == null || scope.kind === 'output' || templateFocused || target == null

  return {
    disabled,
    reason,
    target,
    onCreated: (effectId) => {
      // Layer scope only: move it out of the band and into the focused Look. The store's `lookId`
      // rather than the scope's `layerId`, because a Look is what holds effects — two layers may
      // apply the same one.
      if (scope?.kind === 'layer' && store) {
        void absorb({ projectId: store.projectId, lookId: store.lookId, effectIds: [effectId] })
      }
    },
  }
}

/**
 * The authoring sheet for `+ Effect`, mounted only while open — it subscribes to the fixture
 * list, and the rail is always on screen.
 */
export function ProgrammerAddEffectSheet({
  open,
  offer,
  onClose,
}: {
  open: boolean
  offer: AddEffectOffer
  onClose: () => void
}) {
  if (!open || offer.target == null) return null
  return (
    <AddEditFxSheet
      target={offer.target}
      mode={{ mode: 'add' }}
      programmerOwned
      onCreated={offer.onCreated}
      onClose={onClose}
    />
  )
}

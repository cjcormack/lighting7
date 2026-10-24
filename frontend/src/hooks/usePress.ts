import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import type { CueTarget } from '@/api/cuesApi'
import type { LookSummary } from '@/api/looksApi'
import type { PaletteSummary } from '@/api/palettesApi'
import { useMarquee } from '@/components/fixtures-list/marqueeContext'
import { useLookRowStore, type LookRowStoreValue } from '@/components/programmer/LookRowStore'
import { landValuesInLook } from '@/components/programmer/landValuesInLook'
import { usePressCue, type PressCue } from '@/components/press/pressCue'
import { useProgrammerScope } from '@/components/programmer/ProgrammerScope'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { PaletteFit } from '@/lib/paletteOffer'
import { skippedRowsMessage } from '@/lib/selectionMask'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { useRemoveFxMutation } from '@/store/fixtureFx'
import { useAbsorbLookFxMutation, useApplyLookMutation, useToggleLookMutation } from '@/store/looks'
import { usePressFamilies, useSelectionPair } from '@/store/selection'
import { useApplyPaletteMutation, useTogglePaletteMutation } from '@/store/palettes'

export type PressSource =
  | { kind: 'palette'; palette: PaletteSummary }
  | { kind: 'look'; look: Pick<LookSummary, 'id' | 'name'> }

export type PressGesture = 'press' | 'asValues' | 'off'

/** The targets a press lands on and the attribute mask it carries; null families is every attribute. */
export interface PressPair {
  targets: readonly CueTarget[]
  families: readonly AttributeFamily[] | null
}

/** Where this window's presses land: the programmer, the focused Look layer's draft, or the cue being edited. */
export type PressInto = { kind: 'programmer' } | { kind: 'look'; lookName: string } | { kind: 'cue'; cueLabel: string }

export interface PressOptions {
  /**
   * `'programmer'` ignores a focused Look layer and the cue editor: the door always writes Local.
   * Default `'window'`.
   */
  scope?: 'window' | 'programmer'
}

/**
 * What a press did: put the record on (`on` — a layer, or values), took the pressed heads off it
 * (`off`), wrote values into the focused Look or the cue's own rows (`into`), or nothing (`null` —
 * refused, already said).
 */
export type PressResult = 'on' | 'off' | 'into' | null

export interface Press {
  press: (source: PressSource, gesture: PressGesture) => Promise<PressResult>
  into: PressInto
}

const PROGRAMMER: PressInto = { kind: 'programmer' }

/**
 * The window's selection pair. On the programmer, what its own list presses — the palette strip's
 * targets and the marquee's families, published through the marquee store — so a door outside the
 * list presses what the strip would, an unlinked window's own selection included; elsewhere the
 * desk's pair while this tab follows the desk and the tab's own copy when it does not.
 */
export function useWindowPressPair(): PressPair {
  const marquee = useMarquee()
  const selection = useSelectionPair()
  const press = marquee?.press
  const families = usePressFamilies(press?.askedFamilies ?? null)
  return useMemo(
    () => (press != null ? { targets: press.targets, families } : selection),
    [press, families, selection],
  )
}

/**
 * What the window's heads can take, for a door that offers palettes over [useWindowPressPair] — the
 * rail's *+ Palette*: on the programmer the strip's own offer (the marquee's families, else what
 * the heads can take, and their emitters); elsewhere nothing is known, so nothing is filtered.
 */
export function useWindowPaletteFit(): PaletteFit {
  const press = useMarquee()?.press
  return useMemo(
    () =>
      press == null
        ? NO_FIT
        : press.targets.length === 0
          ? NO_FIT
          : { families: press.askedFamilies ?? press.targetFamilies, emitters: press.targetEmitters },
    [press],
  )
}

const NO_FIT: PaletteFit = { families: null, emitters: null }

/** [usePress] over the window's own pair — for a door that has none of its own (the hand's place). */
export function useWindowPress(projectId: number, options: PressOptions = {}): Press {
  return usePress(projectId, useWindowPressPair(), options)
}

/** The focused Look layer's draft a press from this door lands in, or null when it lands on the programmer. */
export function usePressLook(options: PressOptions = {}): LookRowStoreValue | null {
  const scope = useProgrammerScope()
  const lookStore = useLookRowStore()
  return options.scope !== 'programmer' && scope?.kind === 'layer' ? lookStore : null
}

/** The cue a press from this door lands in — the cue editor's ([PressCue]) — or null. */
export function usePressInCue(options: PressOptions = {}): PressCue | null {
  const cue = usePressCue()
  return options.scope !== 'programmer' ? cue : null
}

/**
 * One press, whatever it is pressed from (apply-model plan §4.1).
 *
 * A press is **source × selection pair → scope**. The source is a palette or a Look; the pair is the
 * targets and the attribute mask the door is acting on — the window's (`useWindowPress`), a cell
 * batch, a fixture sheet's one target, a cue slot's none; the scope is where the programmer is
 * pointed. This hook sends the one route that combination calls for and reports the answer one way,
 * and every press door — the palette strip and the picker, the popup's Palettes bands, a busk
 * pad's *Apply as values*, the hand's place on the programmer's stack, the fixture sheet's Look
 * picker and a cue slot's Look — calls it, so no two surfaces can answer "what does a press do"
 * differently.
 *
 * **The desk owns every rule** (plan P2). Coverage, family replacement, widening and the mask test
 * are server-side: this sends what was pressed, the pair and the gesture, and renders what comes
 * back. It pre-refuses one thing only: a **palette with no targets that would land nowhere** — a
 * generic one on either gesture, a per-fixture one applied as values — which the palette routes take
 * without refusing (the busk pad's route refuses it by name, `PALETTE_NEEDS_SELECTION`). A per-fixture
 * palette's tap names its own heads and a Look with none lands on its own fixtures, so both go out; and nothing is refused from
 * the mask this window holds — it is tested on the on arm only, and only the desk knows which arm a
 * press is on. A refusal arrives as a 400 whose message `errorToastMiddleware` toasts.
 *
 * The gestures (D1, D8): **`press`** puts on a layer that tracks the record, or takes the pressed
 * heads off it when it already covers them all — the desk decides which; **`off`** is the same
 * request, named by the hold menu's *Take off these heads* so a caller can say which it offered;
 * **`asValues`** is *Apply as values*, literals and Local effects with no layer left behind.
 *
 * **With a Look layer focused, a press goes into the Look** (D7, plan §3.6): a value palette is
 * resolved on the desk with `write: false` and each head's literal lands in the layer's draft
 * through `LookRowStore`, which saves through the Look's own PUT; an effect palette's effect is
 * started on the heads the layer targets and then absorbed into the Look (`absorb-fx`, *+
 * Effect*'s path) — and stopped again if the absorb is refused, so nothing is left on stage; a Look is
 * refused, since Looks don't nest. No `programmer.*` frame is sent. A door that writes Local whatever
 * the grid is pointed at — the fixture sheet, a cue slot — passes `scope: 'programmer'`.
 *
 * **In the cue editor a press writes the cue** (plan §3.6, session 8; [PressCue]): a palette or a
 * Look goes on, or comes off, as a layer of the stored cue through `POST /cues/{id}/layers/press`
 * — the programmer's rule, applied by the desk to the cue's layers — and *Apply as values* lands a
 * value palette's literals as the cue's own rows. Nothing is sent to the stage.
 */
export function usePress(projectId: number, pair: PressPair, options: PressOptions = {}): Press {
  const look = usePressLook(options)
  const cue = usePressInCue(options)

  const [applyPalette] = useApplyPaletteMutation()
  const [togglePalette] = useTogglePaletteMutation()
  const [applyLook] = useApplyLookMutation()
  const [toggleLook] = useToggleLookMutation()
  const [absorb] = useAbsorbLookFxMutation()
  const [removeFx] = useRemoveFxMutation()

  const into = useMemo<PressInto>(
    () =>
      cue != null
        ? { kind: 'cue', cueLabel: cue.cueLabel }
        : look != null
          ? { kind: 'look', lookName: look.lookName ?? 'this Look' }
          : PROGRAMMER,
    [cue, look],
  )

  const press = useCallback(
    async (source: PressSource, gesture: PressGesture): Promise<PressResult> => {
      const targets = [...pair.targets]
      const families = pair.families != null && pair.families.length > 0 ? [...pair.families] : undefined
      // A per-fixture palette's toggle names its own heads; its apply does not, so it lands nowhere.
      if (source.kind === 'palette' && targets.length === 0 && (source.palette.isGeneric || gesture === 'asValues')) {
        toast.error('Select the fixtures this should land on first')
        return null
      }
      try {
        if (cue != null) {
          if (cue.readOnly) {
            toast.error(`${cue.cueLabel} is read-only here — nothing was written`)
            return null
          }
          if (gesture === 'asValues') {
            if (source.kind === 'look' || source.palette.kind === 'fx') {
              toast.error(`In ${cue.cueLabel} a tap puts ${source.kind === 'look' ? source.look.name : source.palette.name} on as a layer`)
              return null
            }
            const answer = await applyPalette({ projectId, paletteId: source.palette.id, targets, families, write: false }).unwrap()
            const values = answer.values ?? []
            const landed = cue.landValues(values)
            const missed = values.length - landed + answer.skipped.length
            if (missed > 0) {
              toast.warning(`${landed} value${landed === 1 ? '' : 's'} into ${cue.cueLabel} · ${missed} could not take it`)
            }
            return landed > 0 ? 'into' : null
          }
          const answer = await cue.pressLayer(
            source.kind === 'palette' ? { paletteId: source.palette.id } : { lookId: source.look.id },
            targets,
            families ?? null,
          )
          if (answer == null) return null
          reportSkipped(answer.skippedFamilies, pair.families)
          return answer.action === 'removed' ? 'off' : 'on'
        }
        if (look != null) {
          const lookName = look.lookName ?? 'this Look'
          if (source.kind === 'look') {
            toast.error(`Looks don’t nest — ${lookName} is focused. Focus Local to press ${source.look.name}.`)
            return null
          }
          const template = source.palette
          if (template.kind === 'fx') {
            // Only the heads the layer targets, as the value arm's draft guard lands only those.
            const onLayer = targets.filter((target) =>
              look.targetedKeys == null
                ? true
                : target.type === 'group'
                  ? look.targets.some((t) => t.type === 'group' && t.key === target.key)
                  : look.targetedKeys.has(target.key),
            )
            if (onLayer.length === 0) {
              toast.warning(`No selected head is on ${lookName}’s layer`)
              return null
            }
            const started = await applyPalette({
              projectId,
              paletteId: template.id,
              targets: onLayer,
              families,
            }).unwrap()
            const fxIds = started.fxIds ?? []
            if (fxIds.length === 0) {
              toast.warning('Nothing started — no selected head could take this FX')
              return null
            }
            try {
              await absorb({ projectId: look.projectId, lookId: look.lookId, fxIds }).unwrap()
            } catch (refused) {
              // The absorb keeps its effects on a refusal; a press into a Look must leave the stage
              // as it was, so the copies started for it come off again.
              for (const id of fxIds) void removeFx({ id, fixtureKey: onLayer[0]?.key ?? '' })
              throw refused
            }
            return 'into'
          }
          const answer = await applyPalette({
            projectId,
            paletteId: template.id,
            targets,
            families,
            write: false,
          }).unwrap()
          const values = answer.values ?? []
          const landed = landValuesInLook(look, values)
          const missed = values.length - landed + answer.skipped.length
          if (missed > 0) {
            toast.warning(
              `${landed} value${landed === 1 ? '' : 's'} into ${lookName} · ${missed} could not take it`,
            )
          }
          return landed > 0 ? 'into' : null
        }

        if (source.kind === 'palette') {
          const template = source.palette
          if (gesture === 'asValues') {
            const result = await applyPalette({ projectId, paletteId: template.id, targets, families }).unwrap()
            reportApplied(template, result.written, result.skipped.length, result.fxIds)
            return 'on'
          }
          const result = await togglePalette({
            projectId,
            paletteId: template.id,
            targets,
            // What this client believes the layer's mask is; the desk derives the real one from the
            // palette and answers it, so a disagreement surfaces in the response.
            propertyMask: template.family ?? undefined,
            families,
          }).unwrap()
          return result.action === 'removed' ? 'off' : 'on'
        }

        if (gesture === 'asValues') {
          const result = await applyLook({ projectId, lookId: source.look.id, targets, families }).unwrap()
          reportSkipped(result.skippedFamilies, pair.families)
          return 'on'
        }
        const result = await toggleLook({ projectId, lookId: source.look.id, targets, families }).unwrap()
        reportSkipped(result.skippedFamilies, pair.families)
        return result.action === 'removed' ? 'off' : 'on'
      } catch {
        // Already on screen: `errorToastMiddleware` toasts every rejected mutation with the desk's
        // own sentence, which for a mask refusal is the whole answer.
        ignoreReportedError()
        return null
      }
    },
    [pair, cue, look, projectId, applyPalette, togglePalette, applyLook, toggleLook, absorb, removeFx],
  )

  return useMemo(() => ({ press, into }), [press, into])
}

/**
 * *Apply as values*' report. A value palette's skips are the honest half of a type-agnostic apply —
 * a head with no dimmer takes no level, and saying nothing would look like a press that did nothing.
 * An effect palette writes no literals, so its effect ids are the whole answer, gated on the
 * palette's **kind** and not on the field's presence: the desk answers a value press with
 * `fxIds: []` as well.
 */
function reportApplied(template: PaletteSummary, written: number, skipped: number, fxIds?: number[]) {
  if (skipped > 0) {
    toast.warning(`${written} head${written === 1 ? '' : 's'} set · ${skipped} could not take it`)
    return
  }
  if (template.kind === 'fx' && fxIds != null) {
    const count = fxIds.length
    if (count === 0) toast.warning('Nothing started — no selected head could take this FX')
    else toast.success(`${count} FX started`)
  }
}

/** A Look's skipped families, toasted on the pressing window (multi-screen plan D6). */
function reportSkipped(skipped: readonly string[] | undefined, mask: readonly AttributeFamily[] | null) {
  const message = skippedRowsMessage(skipped ?? [], mask)
  if (message != null) toast.warning(message)
}

import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import type { CueTarget } from '@/api/cuesApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'
import { useMarquee } from '@/components/fixtures-list/marqueeContext'
import { useLookRowStore } from '@/components/programmer/LookRowStore'
import { landValuesInLook } from '@/components/programmer/landValuesInLook'
import { useProgrammerScope } from '@/components/programmer/ProgrammerScope'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { skippedRowsMessage } from '@/lib/selectionMask'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { useRemoveFxMutation } from '@/store/fixtureFx'
import { useAbsorbLookEffectsMutation, useApplyLookMutation, useToggleLookMutation } from '@/store/looks'
import { usePressFamilies, useSelectionPair } from '@/store/selection'
import { useApplyTemplateMutation, useToggleTemplateMutation } from '@/store/templates'

export type PressSource =
  | { kind: 'template'; template: TemplateSummary }
  | { kind: 'look'; look: Pick<LookSummary, 'id' | 'name'> }

export type PressGesture = 'press' | 'asValues' | 'off'

/** The targets a press lands on and the attribute mask it carries; null families is every attribute. */
export interface PressPair {
  targets: readonly CueTarget[]
  families: readonly AttributeFamily[] | null
}

/** Where this window's presses land: the programmer, or the focused Look layer's draft. */
export type PressInto = { kind: 'programmer' } | { kind: 'look'; lookName: string }

export interface PressOptions {
  /** `'programmer'` ignores a focused Look layer: the door always writes Local. Default `'window'`. */
  scope?: 'window' | 'programmer'
}

/**
 * What a press did: put the record on (`on` — a layer, or values), took the pressed heads off it
 * (`off`), wrote it into the focused Look (`into`), or nothing (`null` — refused, already said).
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

/** [usePress] over the window's own pair — for a door that has none of its own (the hand's place). */
export function useWindowPress(projectId: number, options: PressOptions = {}): Press {
  return usePress(projectId, useWindowPressPair(), options)
}

/**
 * One press, whatever it is pressed from (apply-model plan §4.1).
 *
 * A press is **source × selection pair → scope**. The source is a palette or a Look; the pair is the
 * targets and the attribute mask the door is acting on — the window's (`useWindowPress`), a cell
 * batch, a fixture sheet's one target, a cue slot's none; the scope is where the programmer is
 * pointed. This hook sends the one route that combination calls for and reports the answer one way,
 * and every press door — the palette strip and the picker, the colour editor's Recent chips, a busk
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
 * started on the heads the layer targets and then absorbed into the Look (`absorb-effects`, *+
 * Effect*'s path) — and stopped again if the absorb is refused, so nothing is left on stage; a Look is
 * refused, since Looks don't nest. No `programmer.*` frame is sent. A door that writes Local whatever
 * the grid is pointed at — the fixture sheet, a cue slot — passes `scope: 'programmer'`.
 */
export function usePress(projectId: number, pair: PressPair, options: PressOptions = {}): Press {
  const scope = useProgrammerScope()
  const lookStore = useLookRowStore()
  const look = options.scope !== 'programmer' && scope?.kind === 'layer' ? lookStore : null

  const [applyTemplate] = useApplyTemplateMutation()
  const [toggleTemplate] = useToggleTemplateMutation()
  const [applyLook] = useApplyLookMutation()
  const [toggleLook] = useToggleLookMutation()
  const [absorb] = useAbsorbLookEffectsMutation()
  const [removeFx] = useRemoveFxMutation()

  const into = useMemo<PressInto>(
    () => (look != null ? { kind: 'look', lookName: look.lookName ?? 'this Look' } : PROGRAMMER),
    [look],
  )

  const press = useCallback(
    async (source: PressSource, gesture: PressGesture): Promise<PressResult> => {
      const targets = [...pair.targets]
      const families = pair.families != null && pair.families.length > 0 ? [...pair.families] : undefined
      // A per-fixture palette's toggle names its own heads; its apply does not, so it lands nowhere.
      if (source.kind === 'template' && targets.length === 0 && (source.template.isGeneric || gesture === 'asValues')) {
        toast.error('Select the fixtures this should land on first')
        return null
      }
      try {
        if (look != null) {
          const lookName = look.lookName ?? 'this Look'
          if (source.kind === 'look') {
            toast.error(`Looks don’t nest — ${lookName} is focused. Focus Local to press ${source.look.name}.`)
            return null
          }
          const template = source.template
          if (template.kind === 'effect') {
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
            const started = await applyTemplate({
              projectId,
              templateId: template.id,
              targets: onLayer,
              families,
            }).unwrap()
            const effectIds = started.effectIds ?? []
            if (effectIds.length === 0) {
              toast.warning('Nothing started — no selected head could take this effect')
              return null
            }
            try {
              await absorb({ projectId: look.projectId, lookId: look.lookId, effectIds }).unwrap()
            } catch (refused) {
              // The absorb keeps its effects on a refusal; a press into a Look must leave the stage
              // as it was, so the copies started for it come off again.
              for (const id of effectIds) void removeFx({ id, fixtureKey: onLayer[0]?.key ?? '' })
              throw refused
            }
            return 'into'
          }
          const answer = await applyTemplate({
            projectId,
            templateId: template.id,
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

        if (source.kind === 'template') {
          const template = source.template
          if (gesture === 'asValues') {
            const result = await applyTemplate({ projectId, templateId: template.id, targets, families }).unwrap()
            reportApplied(template, result.written, result.skipped.length, result.effectIds)
            return 'on'
          }
          const result = await toggleTemplate({
            projectId,
            templateId: template.id,
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
    [pair, look, projectId, applyTemplate, toggleTemplate, applyLook, toggleLook, absorb, removeFx],
  )

  return useMemo(() => ({ press, into }), [press, into])
}

/**
 * *Apply as values*' report. A value palette's skips are the honest half of a type-agnostic apply —
 * a head with no dimmer takes no level, and saying nothing would look like a press that did nothing.
 * An effect palette writes no literals, so its effect ids are the whole answer, gated on the
 * palette's **kind** and not on the field's presence: the desk answers a value press with
 * `effectIds: []` as well.
 */
function reportApplied(template: TemplateSummary, written: number, skipped: number, effectIds?: number[]) {
  if (skipped > 0) {
    toast.warning(`${written} head${written === 1 ? '' : 's'} set · ${skipped} could not take it`)
    return
  }
  if (template.kind === 'effect' && effectIds != null) {
    const count = effectIds.length
    if (count === 0) toast.warning('Nothing started — no selected head could take this effect')
    else toast.success(`${count} effect${count === 1 ? '' : 's'} started`)
  }
}

/** A Look's skipped families, toasted on the pressing window (multi-screen plan D6). */
function reportSkipped(skipped: readonly string[] | undefined, mask: readonly AttributeFamily[] | null) {
  const message = skippedRowsMessage(skipped ?? [], mask)
  if (message != null) toast.warning(message)
}

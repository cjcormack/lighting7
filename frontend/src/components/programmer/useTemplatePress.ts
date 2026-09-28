import { useCallback, useMemo, type PointerEvent as ReactPointerEvent } from 'react'
import { toast } from 'sonner'
import { useLongPress } from '@/hooks/useLongPress'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { useApplyTemplateMutation, useToggleTemplateMutation } from '@/store/templates'
import type { TemplateSummary, TemplateTarget } from '@/api/templatesApi'

/**
 * The two apply gestures, in one place, so the chip and the picker's pad cannot drift.
 *
 * A template has two presses and they are genuinely different mutations — **click** sets literal
 * values in Local, **⌥click or a hold** adds a layer that *tracks* the template — but everything
 * around them is one rule: where it lands, what a refusal says, and which of the two arms of the
 * response is worth a toast. That was written once, inside `TemplateStrip`, while the strip was the
 * only surface that pressed a template. The picker presses the same templates onto the same
 * selection, and two copies of this would be two answers to "what does a press do", a chip apart.
 *
 * It is **not** a third gesture and adds nothing of its own: the ⌥/hold decision is still the
 * caller's, because only the caller knows what the operator did with their hand.
 *
 * **Both presses carry [families]** — the selection's attribute mask, beside the targets it is a
 * mask of (multi-screen plan D4): the desk's pair while this tab follows the desk, the tab's own
 * when unlinked (`usePressFamilies`). A template is one family, so under a mask it lands whole or
 * the desk refuses it by name (400 `TEMPLATE_OUTSIDE_MASK`, on the click and the layer alike),
 * and that refusal is toasted with the desk's own sentence — *'Warm Amber' is a Colour template,
 * and the selection is masked to Position* — by `errorToastMiddleware`, which reports every
 * rejected mutation. **Nothing is pre-refused here from the mask this tab holds**: the mask is
 * tested on the on arm only, so a press that takes a lit layer off comes off under any mask, and
 * only the desk knows which arm a press is on.
 */
export function useTemplatePress(
  projectId: number,
  targets: readonly TemplateTarget[],
  families: readonly AttributeFamily[] | null = null,
) {
  const [applyTemplate] = useApplyTemplateMutation()
  const [toggleTemplate] = useToggleTemplateMutation()

  return useCallback(
    (template: TemplateSummary, additive: boolean) => {
      // Defence in depth: both surfaces render nothing with no targets, and both have done since
      // space plan D3. One careless host is all it would take to make this reachable again.
      if (targets.length === 0) {
        toast.error('Select the fixtures this should land on first')
        return
      }
      const mask = families != null && families.length > 0 ? [...families] : undefined
      const request = additive
        ? toggleTemplate({
            projectId,
            templateId: template.id,
            targets: [...targets],
            // What this client *believes* the mask is. The server derives the real one from the
            // template's own rows and reports it back, so a disagreement surfaces in the response
            // rather than silently on the rig.
            propertyMask: template.family ?? undefined,
            families: mask,
          })
        : applyTemplate({ projectId, templateId: template.id, targets: [...targets], families: mask })
      request
        .unwrap()
        .then((result) => {
          // The skips are the honest half of a type-agnostic apply: a head with no dimmer takes no
          // level, and saying nothing would look like the press did nothing.
          if ('skipped' in result && result.skipped.length > 0) {
            toast.warning(
              `${result.written} head${result.written === 1 ? '' : 's'} set · ${result.skipped.length} could not take it`,
            )
            return
          }
          // An **effect** template writes no literals at all — it mints detached programmer-band
          // copies, so `written` stays 0 and `effectIds` is the whole result. Without this the one
          // gesture that reaches the rig hardest is the only one that says nothing.
          //
          // An *empty* list is reported too, and that is the half worth keeping: a press that
          // started nothing looks exactly like a press that started everything, and the value arm
          // above has `skipped` to say so where this one has only the count.
          //
          // Gated on the template's **kind**, not on the field being present: the desk answers a
          // value press with `effectIds: []` as well, and reading that as "nothing started" put a
          // failure toast on every successful value press. Found on a desk, not by a test — the
          // mock answered without the field.
          if (template.kind === 'effect' && 'effectIds' in result && result.effectIds != null) {
            const count = result.effectIds.length
            if (count === 0) {
              toast.warning('Nothing started — no selected head could take this effect')
            } else {
              toast.success(`${count} effect${count === 1 ? '' : 's'} started`)
            }
          }
        })
        // The failure is already on screen: `errorToastMiddleware` toasts every rejected
        // mutation with the desk's message. Toasting it again here said the same thing twice —
        // and for a mask refusal the desk's sentence is the whole answer.
        .catch(ignoreReportedError)
    },
    [applyTemplate, toggleTemplate, projectId, targets, families],
  )
}

/**
 * The press **gesture** on one template — the other half of what the chip and the pad must answer
 * identically.
 *
 * [useTemplatePress] above shares the two mutations; this shares the hand that reaches them, and it
 * is here for the same reason. The rule has three parts, and each was written out twice before this
 * hook existed:
 *
 *  - a **click** applies literals, and `altKey` on it means the tracking layer instead;
 *  - a **hold** is ⌥click's touch twin, so the layer is reachable from a surface with no ⌥ key;
 *  - the hold is **touch and pen only**. A mouse has ⌥, so a mouse hold would be a second, silent
 *    door to the tracking mutation: an operator who paused on a chip for half a second would get a
 *    layer where they meant literals, with nothing on screen saying which happened. The busk pads
 *    keep their mouse hold because theirs opens an inspector; these two change the rig.
 *
 * `consumeLongPress` swallows the click the release generates, or a hold would add the layer *and*
 * set the literals.
 *
 * Returns props to spread straight onto the button, so neither caller can spread half of them.
 */
export function useTemplatePressHandlers(
  template: TemplateSummary,
  onPress: (template: TemplateSummary, additive: boolean) => void,
) {
  const { handlers: hold, consumeLongPress } = useLongPress({
    onLongPress: () => onPress(template, true),
  })
  return useMemo(
    () => ({
      ...hold,
      onPointerDown: (e: ReactPointerEvent) => {
        if (e.pointerType === 'touch' || e.pointerType === 'pen') hold.onPointerDown(e)
      },
      onClick: (e: React.MouseEvent) => {
        if (consumeLongPress()) return
        onPress(template, e.altKey)
      },
    }),
    [hold, consumeLongPress, onPress, template],
  )
}

/**
 * The chip's and the pad's shared `title`: both gestures, stated rather than left to be discovered.
 *
 * ⌥click is not a thing an operator guesses, and it is the one that creates a dependency. For an
 * effect the click half says **a copy**, which is the whole difference between the two: the instance
 * a click mints carries no `LayerSource`, so retuning the template afterwards never moves it.
 */
export function templatePressTitle(template: TemplateSummary): string {
  return template.kind === 'effect'
    ? `Click to run a copy of “${template.name}” on the selection · hold or ⌥click to add a layer that tracks it`
    : `Click to set these values · hold or ⌥click to add a layer that tracks “${template.name}”`
}

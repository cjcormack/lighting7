import { useCallback } from 'react'
import type { Cue, CueLayer } from '@/api/cuesApi'
import type { HeldRecord } from '@/api/handApi'
import { buildCueInput } from '@/lib/cueUtils'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { programmerAddLayer } from '@/store/programmer'
import { usePatchProjectCueMutation } from '@/store/cues'
import { useDeskSelection } from '@/store/selection'
import { useHandPlace } from '@/store/hand'
import { HandPlaceStrip, useHandOffer } from './HandTarget'

/**
 * The two **layer** places for the desk's hand — the programmer's stack and one cue's stack.
 *
 * They are one module because they are one gesture over two hosts: a Look or a template becomes a
 * layer, and the only difference is which stack it joins. Each is still the placing window's *own*
 * existing mutation followed by `hand.drop` (D12); there is no `hand.place`.
 *
 * ### The targets are the desk selection, and an empty one is not a failure
 *
 * Both send the desk selection as the layer's `targets`, which is what the plan's §3.5 says and
 * what the template chip's ⌥click already does. It means two things depending on what is held, and
 * both are the layer model's own: for a **template**, whose rows are generic, the targets are
 * *supplied* — with nothing selected a template layer contributes nothing, which is why the strip
 * says so rather than placing silently. For a **Look**, whose rows are bound, the targets *filter* —
 * so an empty selection is the Look's own rows, the ordinary case, and a standing selection narrows
 * it on purpose.
 *
 * The family mask is **the server's** for a template layer, derived from the template's own rows:
 * which family a template layer belongs to is a fact about the template, not about the press.
 *
 * ### Each strip is two components, and the split is not cosmetic
 *
 * The outer one asks `useHandOffer` — a narrowed subscription that only wakes when *this* target's
 * answer changes — and renders nothing at all when the hand holds nothing it can take, which is
 * almost always. The `useDeskSelection()` subscription lives in the inner one, so it exists only
 * while a placeable record is actually held.
 *
 * Written flat, both hooks ran unconditionally in an always-mounted component: the programmer's
 * strip sits in the rail footer on every visit to `/programmer`, so every marquee that crossed a
 * row boundary re-rendered it to refresh a `targets` array for a strip that was rendering `null`;
 * and the cue strip is mounted once per **expanded** cue card, multiplying the same cost by however
 * many the operator has open.
 */

/**
 * Which id a held record contributes — **exactly one**, which is the shape both the cue layer and
 * the programmer's op declare. Shared so the two hosts cannot disagree about it.
 *
 * A `CUE` never reaches here: `canHandLand` refuses both layer targets for one, so the strip is
 * never drawn. The fallback is written as the Look arm rather than thrown, because a throw in a
 * click handler on a live desk is worse than a request the server refuses by name.
 */
function layerSource(held: HeldRecord): { templateId: number } | { lookId: number } {
  return held.kind === 'TEMPLATE' ? { templateId: held.id } : { lookId: held.id }
}

/**
 * *Place “X” here* on the **programmer's** layer stack, drawn in the rail's footer.
 *
 * **No Undo**, and that is a decision rather than an omission. `programmer.addLayer` is a
 * fire-and-forget WS op answered by the whole `programmer.layerState` broadcast — it returns no id —
 * so this window cannot address the layer it just made. The only way to find it would be to diff
 * the stack before and after, and the stack is *shared*: another window or a busking pad adding a
 * layer in the same moment would have Undo remove someone else's, on a live rig. A wrong inverse is
 * worse than none, and the row this just added is one click from its own remove in the rail above.
 */
export function HandProgrammerLayerStrip() {
  // The cheap question first: nothing placeable held means no selection subscription at all.
  const offer = useHandOffer('layer-stack')
  return offer == null ? null : <ProgrammerLayerStripBody />
}

function ProgrammerLayerStripBody() {
  const targets = useDeskSelection()
  const placeFromHand = useHandPlace()

  const onPlace = useCallback(
    (held: HeldRecord) => {
      void placeFromHand(held, {
        where: 'the programmer',
        // A WS op has no receipt, so "it landed" is "it was sent" — but *was it sent* is a real
        // question with a real answer, and dropping it was a bug: `sendGesture` refuses a closed
        // socket, toasts "that did not reach the rig", and returns false. Returning `true`
        // regardless let `useHandPlace` drop the hand and toast success **beside that error**, on
        // a place that never left the browser. `null` takes the hook's "nothing landed" path, which
        // keeps the record held and stays quiet — the failure is already on screen.
        run: async () => (programmerAddLayer({ ...layerSource(held), targets: [...targets] }) ? true : null),
      })
    },
    [placeFromHand, targets],
  )

  return <HandPlaceStrip target="layer-stack" where="the programmer" onPlace={onPlace} />
}

/**
 * *Place “X” here* on **one cue's** stack, drawn in that cue's expanded card.
 *
 * `patchProjectCue` with the layers rebuilt through `buildCueInput` — which is the rule that file's
 * comment is about: a field missing from that rebuild is dropped on every inline cue edit. The
 * inverse is clean here in a way the programmer's is not, because the PATCH answers the whole cue
 * and the array to put back is the one this window read before sending.
 */
export function HandCueLayerStrip({ projectId, cue }: { projectId: number; cue: Cue }) {
  // Same split as the programmer's, for the same reason and multiplied by the number of expanded
  // cue cards.
  const offer = useHandOffer('cue-stack')
  return offer == null ? null : <CueLayerStripBody projectId={projectId} cue={cue} />
}

function CueLayerStripBody({ projectId, cue }: { projectId: number; cue: Cue }) {
  const targets = useDeskSelection()
  const placeFromHand = useHandPlace()
  const [patchCue] = usePatchProjectCueMutation()

  const onPlace = useCallback(
    (held: HeldRecord) => {
      const before = buildCueInput(cue).layers
      const highest = before.reduce((max, l) => Math.max(max, l.sortOrder ?? 0), 0)
      void placeFromHand(held, {
        where: cue.cueNumber != null ? `cue ${cue.cueNumber}` : cue.name,
        run: () =>
          patchCue({
            projectId,
            cueId: cue.id,
            // Appended at the top of the stack: within a cue the later layer wins, so a record
            // placed by hand asserts over what is already there — which is what placing it means.
            layers: [
              ...before,
              {
                ...layerSource(held),
                targets: [...targets],
                enabled: true,
                sortOrder: highest + 1,
              } satisfies CueLayer,
            ],
          }).unwrap(),
        undo: () =>
          patchCue({ projectId, cueId: cue.id, layers: before })
            .unwrap()
            .catch(ignoreReportedError),
      })
    },
    [cue, projectId, patchCue, placeFromHand, targets],
  )

  return (
    <HandPlaceStrip
      target="cue-stack"
      where={cue.cueNumber != null ? `cue ${cue.cueNumber}` : cue.name}
      onPlace={onPlace}
    />
  )
}

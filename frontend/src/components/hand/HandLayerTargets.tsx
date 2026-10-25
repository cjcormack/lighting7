import { useCallback, useMemo } from 'react'
import { useParams } from 'react-router'
import type { Cue, CueLayer, CueTarget } from '@/api/cuesApi'
import type { HeldRecord } from '@/api/handApi'
import { buildCueInput } from '@/lib/cueUtils'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { usePatchProjectCueMutation } from '@/store/cues'
import { usePress, useWindowPress, useWindowPressPair, type PressSource } from '@/hooks/usePress'
import { useHandPlace } from '@/store/hand'
import { HandPlaceStrip, useHandOffer } from './HandTarget'

/**
 * The two **layer** places for the desk's hand — the programmer's stack and one cue's stack.
 *
 * They are one module because they are one gesture over two hosts: a Look or a template becomes a
 * layer, and the only difference is which stack it joins. Each is still the placing window's *own*
 * existing mutation followed by `hand.drop` (D12); there is no `hand.place`.
 *
 * ### The targets are the window's selection, and an empty one is not a failure
 *
 * Both send the window's selection pair (`useWindowPressPair`: the desk's while this window follows
 * it, its own when unlinked — apply-model plan §4.1). The programmer's place **is a press**
 * (`usePress`), so it toggles, replaces the palette's family and lands in a focused Look exactly as
 * the strip's tap does; so is the cue editor's, which lands in the stored cue through the editor's
 * `PressCue`, and a cue strip with no editor host appends a layer to the cue. Only a **palette**
 * takes the targets: its rows are generic, so with nothing selected its layer contributes nothing,
 * which is why the strip says so rather than placing silently. A **Look** names none, whatever is
 * selected — it lands whole (absolute-looks plan D1).
 *
 * The layer's mask is **the server's** for a template layer, derived from the template's own rows:
 * which family a template layer belongs to is a fact about the template, not about the press.
 *
 * ### Each strip is two components, and the split is not cosmetic
 *
 * The outer one asks `useHandOffer` — a narrowed subscription that only wakes when *this* target's
 * answer changes — and renders nothing at all when the hand holds nothing it can take, which is
 * almost always. The selection subscription lives in the inner one, so it exists only
 * while a placeable record is actually held.
 *
 * Written flat, both hooks ran unconditionally in an always-mounted component: the programmer's
 * strip sits under the programmer's tables on every visit to `/programmer`, so every marquee that crossed a
 * row boundary re-rendered it to refresh a `targets` array for a strip that was rendering `null`;
 * and the cue strip is mounted once per **expanded** cue card, multiplying the same cost by however
 * many the operator has open.
 */

const NO_TARGETS: readonly CueTarget[] = []

/**
 * Which id a held record contributes — **exactly one**, which is the shape both the cue layer and
 * the programmer's op declare. Shared so the two hosts cannot disagree about it.
 *
 * A `CUE` never reaches here: `canHandLand` refuses both layer targets for one, so the strip is
 * never drawn. The fallback is written as the Look arm rather than thrown, because a throw in a
 * click handler on a live desk is worse than a request the server refuses by name.
 */
function layerSource(held: HeldRecord): { paletteId: number } | { lookId: number } {
  return held.kind === 'PALETTE' ? { paletteId: held.id } : { lookId: held.id }
}

/**
 * *Place “X” here* on the **programmer's** layer stack, drawn at the foot of the programmer's tables
 * whichever is showing: the press, with the window's pair (apply-model plan §5).
 *
 * **No Undo**, and that is a decision rather than an omission. A press answers no layer id — the
 * stack comes back whole on `programmer.layerState` — so this window cannot address the layer it
 * just made. The only way to find it would be to diff the stack before and after, and the stack is
 * *shared*: another window or a busking pad pressing in the same moment would have Undo remove
 * someone else's, on a live rig. A wrong inverse is worse than none, and the row this just added is
 * one click from its own remove on the Stack table.
 */
export function HandProgrammerLayerStrip({ className }: { className?: string }) {
  // The cheap question first: nothing placeable held means no selection subscription at all.
  const offer = useHandOffer('layer-stack')
  return offer == null ? null : <ProgrammerLayerStripBody className={className} />
}

function ProgrammerLayerStripBody({ className }: { className?: string }) {
  const { projectId } = useParams()
  const { press, into } = useWindowPress(Number(projectId))
  const placeFromHand = useHandPlace()
  const where = into.kind === 'look' ? into.lookName : into.kind === 'cue' ? into.cueLabel : 'the programmer'

  const onPlace = useCallback(
    (held: HeldRecord) => {
      const source = pressSourceOf(held)
      if (source == null) return
      void placeFromHand(held, {
        where,
        // A refused press is already on screen (`errorToastMiddleware`) and answers `null`, the
        // hook's "nothing landed" path, which keeps the record held and stays quiet.
        run: () => press(source, 'press'),
        // The press toggles: a record already on every selected head comes off them.
        describe: (result, name) =>
          result === 'off'
            ? `“${name}” was already on the selection — taken off it`
            : result === 'into'
              ? `“${name}” written into ${where}`
              : `“${name}” placed in ${where}`,
      })
    },
    [placeFromHand, press, where],
  )

  return <HandPlaceStrip target="layer-stack" where={where} onPlace={onPlace} className={className} />
}

/**
 * The press source a held record is — its own summary DTO, which the hand frame carries. A cue
 * never reaches here (`canHandLand` refuses both layer targets for one).
 */
function pressSourceOf(held: HeldRecord): PressSource | null {
  if (held.kind === 'PALETTE' && held.palette != null) return { kind: 'palette', palette: held.palette }
  if (held.kind === 'LOOK' && held.look != null) return { kind: 'look', look: held.look }
  return null
}

/**
 * *Place “X” here* on **one cue's** stack, drawn in that cue's expanded card.
 *
 * `patchProjectCue` with the layers rebuilt through `buildCueInput` — which is the rule that file's
 * comment is about: a field missing from that rebuild is dropped on every inline cue edit. The
 * inverse is clean here in a way the programmer's is not, because the PATCH answers the whole cue
 * and the array to put back is the one this window read before sending.
 */
export function HandCueLayerStrip({ projectId, cue, host }: { projectId: number; cue: Cue; host?: HandCueLayerHost }) {
  // Same split as the programmer's, for the same reason and multiplied by the number of expanded
  // cue cards.
  const offer = useHandOffer('cue-stack')
  return offer == null ? null : <CueLayerStripBody projectId={projectId} cue={cue} host={host} />
}

/**
 * How the cue editor's Looks tab places a layer: a press on its Values selection, which the editor's
 * `PressCue` lands in the cue through the desk's rule (`usePress`), as its *+ Look* does — so the
 * place toggles and replaces a palette's family like every other door; the editor's own save toast
 * carries its Undo. Absent, the strip PATCHes
 * the cue it was handed and lands on the window's selection.
 */
export interface HandCueLayerHost {
  targets: readonly CueTarget[]
}

function CueLayerStripBody({ projectId, cue, host }: { projectId: number; cue: Cue; host?: HandCueLayerHost }) {
  const { targets: windowTargets } = useWindowPressPair()
  const targets = host?.targets ?? windowTargets
  const placeFromHand = useHandPlace()
  const [patchCue] = usePatchProjectCueMutation()
  const pair = useMemo(() => ({ targets: host?.targets ?? NO_TARGETS, families: null }), [host?.targets])
  const { press } = usePress(projectId, pair)

  const onPlace = useCallback(
    (held: HeldRecord) => {
      if (host != null) {
        const source = pressSourceOf(held)
        if (source == null) return
        const where = cue.cueNumber != null ? `cue ${cue.cueNumber}` : cue.name
        void placeFromHand(held, {
          where,
          // A refused press is already on screen and answers null, which keeps the record held.
          run: () => press(source, 'press'),
          describe: (result, name) =>
            result === 'off' ? `“${name}” was already on the selection — taken off it` : `“${name}” placed in ${where}`,
        })
        return
      }
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
                targets: held.kind === 'PALETTE' ? [...targets] : [],
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
    [cue, projectId, patchCue, placeFromHand, targets, host, press],
  )

  return (
    <HandPlaceStrip
      target="cue-stack"
      where={cue.cueNumber != null ? `cue ${cue.cueNumber}` : cue.name}
      onPlace={onPlace}
    />
  )
}

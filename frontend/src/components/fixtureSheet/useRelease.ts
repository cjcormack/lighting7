import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { useProgrammerRevision } from '@/store/programmer'
import { isLocalEffect, partialSweepMessage } from '../fixtures-list/cellEffects'
import type { TargetCleared } from '@/api/programmerWsApi'
import { useRemoveFxMutation, type ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import { sheetTargetKey, type SheetTarget } from './sheetContext'
import { parseSelectionKey, selectionItems } from '@/lib/effectTargets'

/**
 * Whether the desk's `clearTarget` stops [effect]: one on the target itself, or a selection whose
 * every item is inside it — a selection that also drives heads outside is left running.
 */
function releaseStops(effect: ActiveEffect, keys: ReadonlySet<string>, ownGroup: string | null): boolean {
  const items = selectionItems(effect)
  if (items != null) return items.length > 0 && items.every((t) => (t.type === 'group' ? t.key === ownGroup : keys.has(t.key)))
  return effect.isGroupTarget ? effect.targetKey === ownGroup : keys.has(effect.targetKey)
}

/** What this fixture holds in the programmer — what *Release n* takes and the scope line counts. */
export interface HeldOnFixture {
  /** Property entries on the fixture or its heads, plus raw channels in its footprint. */
  values: number
  /** Local effects on it: the operator's own, on the fixture or a head (`isLocalEffect`). */
  effects: number
}

/** The fixture's own key and every head's — the targets a fixture-scoped Release covers. */
export function fixtureHeadKeys(fixture: Fixture): Set<string> {
  return new Set([fixture.key, ...(fixture.elements ?? []).map((e) => e.key)])
}

/**
 * Counted from the client's programmer snapshot and effect list, the same two things Release's
 * desk pass walks (W2): every entry whatever its owner but a layer's (which `clearTarget` leaves,
 * as `clearEntry` does), the raw-channel sideband inside the fixture's channels, and the local
 * effects targeted at it. A group effect is not counted — Release leaves it running and names it.
 */
export function heldOnFixture(fixture: Fixture, effects: readonly ActiveEffect[] | undefined): HeldOnFixture {
  return heldOnTarget({ type: 'fixture', fixture }, effects)
}

/**
 * [heldOnFixture] for any sheet target. A group's Release takes every member and its heads (the
 * desk's `clearTarget` walks the group's fixtures), their sideband, the members' own local effects
 * and the group's own; another group's effect is left running and is not counted.
 */
export function heldOnTarget(target: SheetTarget, effects: readonly ActiveEffect[] | undefined): HeldOnFixture {
  // The desk's roots: the fixture, or the group's members — a member that is one head of a bar is
  // that head alone, with no footprint of its own (`clearTarget` walks only DMX fixtures' channels).
  const fixtures =
    target.type === 'fixture' ? [target.fixture] : target.members.filter((m) => m.elementIndex == null).map((m) => m.fixture)
  const keys = new Set([
    ...fixtures.flatMap((f) => [...fixtureHeadKeys(f)]),
    ...(target.type === 'group' ? target.members.map((m) => m.key) : []),
  ])
  const state = lightingApi.programmer.getState()
  let values = 0
  for (const entry of state.entries.values()) {
    if (!keys.has(entry.targetKey)) continue
    const owners = entry.owners?.length ? entry.owners : [entry.owner]
    if (owners.some((o) => o !== 'layers')) values += 1
  }
  for (const ch of state.channels) {
    if (fixtures.some((f) => ch.universe === f.universe && ch.channel >= f.firstChannel && ch.channel <= f.firstChannel + f.channelCount - 1)) {
      values += 1
    }
  }
  const ownGroup = target.type === 'group' ? target.group.name : null
  const local = (effects ?? []).filter(
    (e) => isLocalEffect(e) && releaseStops(e, keys, ownGroup),
  ).length
  return { values, effects: local }
}

/** The held line, `3 values · 2 effects held`, or null when the fixture holds nothing. */
export function heldLine({ values, effects }: HeldOnFixture): string | null {
  const parts = [
    values > 0 ? `${values} value${values === 1 ? '' : 's'}` : null,
    effects > 0 ? `${effects} effect${effects === 1 ? '' : 's'}` : null,
  ].filter(Boolean)
  return parts.length === 0 ? null : `${parts.join(' · ')} held`
}

/** Re-counted on every programmer event; cheap — one pass over the entries. */
export function useHeldOnTarget(target: SheetTarget, effects: readonly ActiveEffect[] | undefined): HeldOnFixture {
  const revision = useProgrammerRevision()
  return useMemo(() => {
    void revision
    return heldOnTarget(target, effects)
  }, [revision, target, effects])
}

/** The toast a Release answers with, in the desk's own count. */
export function releaseMessage(name: string, cleared: TargetCleared): string {
  if (cleared.values === 0 && cleared.effects === 0) return `${name} held nothing to release`
  const parts = [
    cleared.values > 0 ? `${cleared.values} value${cleared.values === 1 ? '' : 's'}` : null,
    cleared.effects > 0 ? `${cleared.effects} effect${cleared.effects === 1 ? '' : 's'}` : null,
  ].filter(Boolean)
  return `Released ${parts.join(' and ')} on ${name}`
}

/**
 * *Release n* (D6, W2): one `programmer.clearTarget` for the fixture and its heads — or the group
 * and its members — at the programmer fade, its values and its local effects in one pass, toasting
 * the desk's reply, including the effects it left running (`partialSweepMessage`'s vocabulary). No
 * confirm, like Clear (call 3).
 */
export function useRelease(target: SheetTarget): () => Promise<void> {
  const type = target.type
  const key = sheetTargetKey(target)
  const name = target.type === 'fixture' ? target.fixture.name : target.group.name
  return useCallback(async () => {
    try {
      const cleared = await lightingApi.programmer.clearTarget(type, key, getProgrammerFadeMs())
      toast.success(releaseMessage(name, cleared))
      if (cleared.partial.length > 0) toast.warning(partialSweepMessage(cleared.partial, type === 'fixture' ? 'this fixture' : 'this group'))
    } catch (e) {
      // A closed socket was already toasted by the gesture send; anything else is the desk's word.
      const message = e instanceof Error ? e.message : String(e)
      if (message !== 'The desk is not connected') toast.error(`Release failed: ${message}`)
    }
  }, [type, key, name])
}

/** One target of a *Release n* over a selection — what `clearTarget` addresses, and its name. */
export interface ReleaseTarget {
  type: 'fixture' | 'group'
  key: string
  name: string
}

/**
 * The toast a Release over several targets answers with — the desk's counts, summed, and
 * [alsoStopped] effects the release stopped over the targets together.
 */
export function releaseAllMessage(targets: readonly ReleaseTarget[], cleared: readonly TargetCleared[], alsoStopped = 0): string {
  if (targets.length === 1 && cleared.length === 1 && alsoStopped === 0) return releaseMessage(targets[0].name, cleared[0])
  const values = cleared.reduce((n, c) => n + c.values, 0)
  const effects = cleared.reduce((n, c) => n + c.effects, 0) + alsoStopped
  const on = `${targets.length} ${targets.length === 1 ? 'target' : 'targets'}`
  return releaseMessage(on, { targetType: 'fixture', targetKey: '', values, effects, partial: [] })
}

/**
 * *Release n* over a selection (fixture-fx-sheets plan D18, W2): **one `programmer.clearTarget`
 * per target**, at the programmer fade — the programmer's row C over a row selection and the busk
 * rig row over the busk selection. Each frame is the fixture sheet's own Release on that target:
 * its values and its local effects in one pass, pad and Look layers untouched (a lit pad still goes
 * off by its own press). One toast for the lot, in the desk's summed count, and the group effects
 * it left running named as the sheet's Release names them. No confirm, like Clear (call 3).
 *
 * A local effect over a selection all of whose items are among the targets is stopped too: each
 * frame alone answers it as partial, since it paints the other targets as well.
 */
export function useReleaseTargets(targets: readonly ReleaseTarget[]): () => Promise<void> {
  const [removeFx] = useRemoveFxMutation()
  return useCallback(async () => {
    if (targets.length === 0) return
    const fade = getProgrammerFadeMs()
    const results = await Promise.allSettled(targets.map((t) => lightingApi.programmer.clearTarget(t.type, t.key, fade)))
    const cleared = results.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : []))
    // A group effect left running on a member is named once — and not at all where the group itself
    // was in the selection, whose own frame stopped it.
    const groups = new Set(targets.filter((t) => t.type === 'group').map((t) => t.key))
    const released = new Set(targets.map((t) => `${t.type}:${t.key}`))
    const reported = [
      ...new Map(
        cleared.flatMap((c) => c.partial).filter((p) => !(p.isGroupTarget && groups.has(p.targetKey))).map((p) => [p.effectId, p]),
      ).values(),
    ]
    const within = reported.filter((p) => {
      const items = parseSelectionKey(p.targetKey)
      return items.length > 0 && items.every((t) => released.has(`${t.type}:${t.key}`))
    })
    const stopped = await Promise.allSettled(within.map((p) => removeFx({ id: p.effectId, fixtureKey: p.targetKey }).unwrap()))
    const stoppedIds = new Set(within.filter((_, i) => stopped[i].status === 'fulfilled').map((p) => p.effectId))
    const partial = reported.filter((p) => !stoppedIds.has(p.effectId))
    if (cleared.length > 0) toast.success(releaseAllMessage(targets, cleared, stoppedIds.size))
    if (partial.length > 0) {
      toast.warning(targets.length === 1 ? partialSweepMessage(partial, `this ${targets[0].type}`) : partialSweepMessage(partial))
    }
    const failed = results.flatMap((r) => (r.status === 'rejected' ? [r.reason] : []))
    // A closed socket was already toasted by the gesture send; anything else is the desk's word.
    const message = failed.map((e) => (e instanceof Error ? e.message : String(e))).find((m) => m !== 'The desk is not connected')
    if (message != null) toast.error(`Release failed: ${message}`)
  }, [targets, removeFx])
}

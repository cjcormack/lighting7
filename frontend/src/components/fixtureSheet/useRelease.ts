import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { useProgrammerRevision } from '@/store/programmer'
import { isLocalEffect, partialSweepMessage } from '../fixtures-list/cellEffects'
import type { TargetCleared } from '@/api/programmerWsApi'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'

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
  const keys = fixtureHeadKeys(fixture)
  const state = lightingApi.programmer.getState()
  let values = 0
  for (const entry of state.entries.values()) {
    if (!keys.has(entry.targetKey)) continue
    const owners = entry.owners?.length ? entry.owners : [entry.owner]
    if (owners.some((o) => o !== 'layers')) values += 1
  }
  const last = fixture.firstChannel + fixture.channelCount - 1
  for (const ch of state.channels) {
    if (ch.universe === fixture.universe && ch.channel >= fixture.firstChannel && ch.channel <= last) values += 1
  }
  const local = (effects ?? []).filter((e) => !e.isGroupTarget && keys.has(e.targetKey) && isLocalEffect(e)).length
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
export function useHeldOnFixture(fixture: Fixture, effects: readonly ActiveEffect[] | undefined): HeldOnFixture {
  const revision = useProgrammerRevision()
  return useMemo(() => {
    void revision
    return heldOnFixture(fixture, effects)
  }, [revision, fixture, effects])
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
 * *Release n* (D6, W2): one `programmer.clearTarget` for the fixture and its heads at the
 * programmer fade — its values and its local effects in one pass — toasting the desk's reply,
 * including the group effects it left running (`partialSweepMessage`'s vocabulary). No confirm,
 * like Clear (call 3).
 */
export function useRelease(fixture: Fixture): () => Promise<void> {
  return useCallback(async () => {
    try {
      const cleared = await lightingApi.programmer.clearTarget('fixture', fixture.key, getProgrammerFadeMs())
      toast.success(releaseMessage(fixture.name, cleared))
      if (cleared.partial.length > 0) toast.warning(partialSweepMessage(cleared.partial, 'this fixture'))
    } catch (e) {
      // A closed socket was already toasted by the gesture send; anything else is the desk's word.
      const message = e instanceof Error ? e.message : String(e)
      if (message !== 'The desk is not connected') toast.error(`Release failed: ${message}`)
    }
  }, [fixture.key, fixture.name])
}

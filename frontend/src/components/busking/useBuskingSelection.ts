import { useCallback, useMemo } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import type { SubselectMode } from '@/api/selectionApi'
import { useFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import { useBuskRigQuery } from '@/store/busk'
import { clearDeskSelection, subselectDeskSelection, toggleDeskSelection, useSelectionPair } from '@/store/selection'
import { effectiveRig } from '@/lib/buskRig'
import { subselectTargets } from '@/lib/cellsSubSelection'
import { getLocalSelection, setLocalSelection, useDeskFollow } from '@/lib/deskFollow'
import { buskingTargetKey, type BuskingTarget } from './buskingTypes'

/**
 * Which targets the pad is aimed at, and under which attribute mask — **the desk's selection**
 * while this tab follows the desk, and the tab's own when it has unlinked (multi-screen plan D8).
 *
 * It was local `useState` until the MIDI surface arrived, and the move is plan D2: one desk, one
 * selection, server-owned. The composition model's argument for one programmer applies verbatim —
 * DMX is one byte per channel, so two selections would force a "whose press wins?" policy nothing
 * expresses — and the practical consequence is that a select button on a control surface, a
 * marquee in the programmer's list and a pad in the target band are three ways to say one thing.
 *
 * **The local arm is the exception, and it is per tab.** Clicking the desk chip to *This window*
 * snapshots the desk's targets and mask into `lib/deskFollow.ts`'s copy and stops following; the
 * three writes then edit that copy and the desk's is untouched, and a press sends the copy as its
 * pair. Re-linking drops the copy and adopts the desk's. Two things the local arm does more simply
 * than the desk does, on purpose: its toggle is add-or-remove by key, without the desk's head-by-
 * head narrowing of a partly covered group (the desk owns that rule and a client copy would drift),
 * and its replace clears the mask exactly as the desk's `set` does (D2).
 *
 * **This is not a thin wrapper over the cache.** The cache holds `{ type, key }`; the band and the
 * pads want a whole `GroupSummary` or `Fixture` (a member count, a name, an icon), so this
 * rehydrates against the two lists. Rehydration is also the *only* place a target is dropped: a
 * target that no longer resolves has already left the desk's own list (`DeskSelection` drops it on
 * fixture reload), so a target missing here means the lists have not arrived yet, and it comes back
 * on their next frame rather than being filtered out by a second client-side rule.
 *
 * A Map keyed by `buskingTargetKey` rather than an array, because every other part of the pad asks
 * "is this one selected" far more often than it iterates, and a group and a fixture can share a
 * name.
 *
 * **The sub-selection is the one write with a rule** (busk-further plan D12), and the split is the
 * same: following, the Cells chip sends `selection.subselect {mode}` and the desk rewrites its
 * targets over its own rig order; unlinked, `lib/cellsSubSelection.ts` rewrites the tab's copy by
 * the same rule over the rig document, which is why this hook reads `useBuskRigQuery` at all — the
 * desk walks the rig for *Next* / *Prev*, so the mirror must too. That module is pinned against the
 * server's fixture; nothing here derives anything.
 */
export function useBuskingSelection(projectId: number) {
  const following = useDeskFollow()
  const { targets, families } = useSelectionPair()
  const { data: groups } = useGroupListQuery()
  const { data: fixtures } = useFixtureListQuery()
  const { data: rig, isError: rigFailed } = useBuskRigQuery(projectId)
  // `RigBand`'s guard, for its reason: `effectiveRig(undefined, …)` is the show-all fallback, and a
  // built rig walked in fallback order until the query lands is a wrong answer with no desk frame
  // to correct it. A failed read falls back as the band does.
  const rigLoaded = rig != null || rigFailed

  const selectedTargets = useMemo(() => {
    const out = new Map<string, BuskingTarget>()
    for (const target of targets) {
      let rich: BuskingTarget | null = null
      if (target.type === 'group') {
        const group = groups?.find((g) => g.name === target.key)
        if (group) rich = { type: 'group', name: group.name, group }
      } else {
        const fixture = fixtures?.find((f) => f.key === target.key)
        if (fixture) rich = { type: 'fixture', key: fixture.key, fixture }
        else {
          // A cell: the key is one of a fixture's own element keys, found by lookup and never by
          // parsing the key — the rig band presses one, and a marquee on the programmer's element
          // rows publishes one.
          for (const parent of fixtures ?? []) {
            const element = parent.elements?.find((e) => e.key === target.key)
            if (element) {
              rich = { type: 'fixture', key: element.key, fixture: parent, element }
              break
            }
          }
        }
      }
      if (rich) out.set(buskingTargetKey(rich), rich)
    }
    return out
  }, [targets, groups, fixtures])

  const clearSelection = useCallback(() => {
    if (following) clearDeskSelection()
    else setLocalSelection({ targets: [], families: null })
  }, [following])

  /**
   * Add or remove one target, keeping the mask — by its `{type, key}`, which is what a rig tile
   * has. There is no replace any more: `selectTarget` existed for the narrow-width picker sheet,
   * which the rig band retired (busk-further plan D15).
   *
   * On the desk's arm the desk decides what "remove" means, and it is not symmetric with "add": a
   * fixture already covered by a selected group is narrowed *out of that group's coverage* rather
   * than removed as an entry. Doing it here would need the group's members, which `GroupSummary`
   * does not carry — which is also why the local arm does not try to.
   */
  const toggleTarget = useCallback(
    (layerTarget: CueTarget) => {
      if (following) {
        toggleDeskSelection(layerTarget)
        return
      }
      const local = getLocalSelection()
      const present = local.targets.some(
        (t) => t.type === layerTarget.type && t.key === layerTarget.key,
      )
      setLocalSelection({
        targets: present
          ? local.targets.filter((t) => !(t.type === layerTarget.type && t.key === layerTarget.key))
          : [...local.targets, layerTarget],
        families: local.families,
      })
    },
    [following],
  )

  /**
   * Rewrite the selection's targets by [mode] — the Cells chip. The mask is untouched on both arms,
   * and on the local arm a rewrite that changes nothing writes nothing, as the desk's would emit no
   * frame. The rig is `effectiveRig`'s: an empty one is every group then every fixture, the desk's
   * own order for an empty rig — and until the rig has answered the local arm does nothing at all
   * rather than walk the fallback for a rig that may be built.
   */
  const subselect = useCallback(
    (mode: SubselectMode) => {
      if (following) {
        subselectDeskSelection(mode)
        return
      }
      if (!rigLoaded) return
      const local = getLocalSelection()
      const next = subselectTargets(local.targets, mode, {
        rows: effectiveRig(rig, groups, fixtures).rows,
        groups: groups ?? [],
        fixtures: fixtures ?? [],
      })
      if (next === local.targets) return
      setLocalSelection({ targets: [...next], families: local.families })
    },
    [following, rigLoaded, rig, groups, fixtures],
  )

  return { selectedTargets, families, toggleTarget, clearSelection, subselect }
}

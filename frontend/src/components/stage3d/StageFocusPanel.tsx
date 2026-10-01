import { useCallback, useEffect, useRef, useState } from 'react'
import type { FixturePatch, PatchPlacement } from '@/api/patchApi'
import { toPlacementInput } from '@/lib/extraPlacements'
import { effectiveLantern, focusFields, FOCUS_KEYS, type LanternFocus, type LanternIndex } from '@/lib/lanterns'
import {
  findFocusProperty,
  findFrostProperty,
  findIrisProperty,
  findZoomProperty,
  resolveFixtureKind,
  type Fixture,
  type FixtureTypeInfo,
} from '@/store/fixtures'
import { patchesApi, useUpdatePatchMutation } from '@/store/patches'
import { useDispatch } from 'react-redux'
import type { store } from '@/store'
import { restApi } from '@/store/restApi'
import { FocusCard, type FocusUnit } from '@/components/lanterns/FocusCard'

/** How long a pause in the edits before they are saved: a drag writes once, after it rests. */
const SAVE_AFTER_MS = 350

type FocusKey = (typeof FOCUS_KEYS)[number]
/** The keys of one unit's focus the operator has moved and the desk has not yet confirmed. */
type Draft = LanternFocus

interface StageFocusPanelProps {
  projectId: number
  patch: FixturePatch
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  lanterns: LanternIndex
}

/**
 * The Stage view's **Focus** tab (stage-view plan session 7): the focus card over a conventional's
 * lanterns — its own and each extra placement's, switched between — written as the operator
 * focuses. It saves one `PUT` after a short pause ([SAVE_AFTER_MS]), carrying only what moved.
 *
 * **What moved is a draft the panel holds**, per unit and per key, and it is drawn over the patch
 * until the desk has confirmed it. The patch list is re-read after every save (the mutation
 * invalidates it), and that read can land while the next edit is still waiting — without the draft
 * it would put the old value back on the card, the next arrow key would step from there, and the
 * save after would send the reverted value. The draft is also written into the patch list's cache
 * as it changes, and again whenever a read replaces it, so the Stage redraws the cut as the blade
 * moves. A key leaves the draft once a save carrying it has landed and nothing has moved it since.
 * A refused save drops the draft and re-reads the list, so the card shows what the desk holds.
 *
 * Only the fixture's own moved keys are sent — an unrelated field (a lantern id this desk's library
 * does not know, say) is never re-sent, and never refused. A placement's focus goes out through the
 * whole `extraPlacements` list, which is the only way the route takes one.
 *
 * A DMX fixture has no focus data: its zoom, focus and iris are its channels, which its looks drive
 * (D14), so its tab says which of them it has instead.
 */
export function StageFocusPanel({ projectId, patch, fixture, fixtureType, lanterns }: StageFocusPanelProps) {
  const dispatch = useDispatch<typeof store.dispatch>()
  const [updatePatch] = useUpdatePatchMutation()
  const [active, setActive] = useState(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Unit key ('fixture', or a placement's uuid) → the keys moved and not yet confirmed.
  const drafts = useRef(new Map<string, Draft>())
  // Bumped when the draft changes, so the card redraws from it.
  const [, setDraftVersion] = useState(0)
  const latest = useRef(patch)
  latest.current = patch

  /** Write every draft into the patch list's cache, so the Stage draws what the card shows. */
  const paintCache = useCallback(() => {
    if (drafts.current.size === 0) return
    const id = latest.current.id
    dispatch(
      patchesApi.util.updateQueryData('patchList', projectId, (list) => {
        const target = list.find((p) => p.id === id)
        if (!target) return
        for (const [unit, draft] of drafts.current) {
          if (unit === 'fixture') paintDraft(target, draft)
          else {
            const placement: PatchPlacement | undefined = target.extraPlacements?.find((p) => p.uuid === unit)
            if (placement) paintDraft(placement, draft)
          }
        }
      }),
    )
  }, [dispatch, projectId])

  const flush = useCallback(() => {
    if (timer.current != null) clearTimeout(timer.current)
    timer.current = null
    if (drafts.current.size === 0) return
    // What this save carries, by identity, so a key moved again while it is in flight stays.
    const sent = new Map([...drafts.current].map(([unit, draft]) => [unit, { ...draft }]))
    const p = latest.current
    const body: Record<string, unknown> = {}
    const own = sent.get('fixture')
    if (own) Object.assign(body, own)
    if ([...sent.keys()].some((unit) => unit !== 'fixture')) {
      body.extraPlacements = (p.extraPlacements ?? []).map((pl) =>
        toPlacementInput({ ...pl, ...(sent.get(pl.uuid) ?? {}) }),
      )
    }
    updatePatch({ projectId, patchId: p.id, ...body })
      .unwrap()
      .then(() => {
        for (const [unit, carried] of sent) {
          const draft = drafts.current.get(unit)
          if (!draft) continue
          for (const k of Object.keys(carried) as FocusKey[]) {
            if (draft[k] === carried[k]) delete draft[k]
          }
          if (Object.keys(draft).length === 0) drafts.current.delete(unit)
        }
      })
      .catch(() => {
        // The desk refused (and said why): drop what it refused and show what it holds.
        drafts.current.clear()
        setDraftVersion((v) => v + 1)
        dispatch(restApi.util.invalidateTags(['Patch']))
      })
  }, [dispatch, projectId, updatePatch])

  // A read of the patch list replaces the cache: lay what is still in the draft back over it.
  useEffect(() => {
    paintCache()
  }, [patch, paintCache])

  // A pending save still lands when the panel goes — another fixture picked, the tab closed.
  useEffect(() => () => flush(), [flush])

  if (fixtureType?.acceptsLantern !== true) {
    return <DmxFocusNote fixture={fixture} />
  }

  const draftOf = (unit: string): Draft => drafts.current.get(unit) ?? {}
  const kind = resolveFixtureKind(patch.kindOverride, fixtureType.kind)
  const ownFocus: LanternFocus = { ...focusFields(patch), ...draftOf('fixture') }
  const own = effectiveLantern(lanterns, ownFocus.lanternType, kind)
  const placements = patch.extraPlacements ?? []
  const units: FocusUnit[] = [
    { key: 'fixture', label: placements.length > 0 ? 'Lantern 1' : 'This lantern', lantern: own, focus: ownFocus },
    ...placements.map((pl, i) => {
      const focus: LanternFocus = { ...focusFields(pl), ...draftOf(pl.uuid) }
      return {
        key: pl.uuid,
        label: `Lantern ${i + 2}${pl.label ? ` · ${pl.label}` : ''}`,
        lantern: effectiveLantern(lanterns, focus.lanternType ?? ownFocus.lanternType, kind),
        focus,
      }
    }),
  ]

  const change = (index: number, next: LanternFocus) => {
    const unit = units[index]
    if (!unit) return
    const before = focusFields(unit.focus)
    const after = focusFields(next)
    const draft = { ...draftOf(unit.key) }
    for (const k of FOCUS_KEYS) {
      if (after[k] !== before[k]) (draft as Record<FocusKey, unknown>)[k] = after[k]
    }
    drafts.current.set(unit.key, draft)
    setDraftVersion((v) => v + 1)
    paintCache()
    if (timer.current != null) clearTimeout(timer.current)
    timer.current = setTimeout(flush, SAVE_AFTER_MS)
  }

  return (
    <div className="space-y-3">
      <FocusCard
        units={units}
        active={active}
        onActiveChange={setActive}
        onChange={change}
        note={
          units.length > 1
            ? "Stored on each lantern's placement, never in a look. The lanterns of a pair are focused separately."
            : 'Stored on the patch, never in a look.'
        }
      />
    </div>
  )
}

/**
 * Lay [draft] over [target] key by key, writing only a key whose **value** differs. RTK applies a
 * cache update as Immer patches, which clones the value into the cache — so the `shutters` array
 * the cache holds after one paint is never the draft's own array. Assigned by reference, every
 * paint would then be a change: a new patch list, a new `patch` prop, the paint effect again,
 * until React gives up with "Maximum update depth exceeded" (found driving the Focus tab on a
 * desk, stage-view session 8's checks). The draft's values are numbers, strings and one array of
 * four small objects, so their JSON is the comparison.
 */
function paintDraft(target: object, draft: Draft) {
  const t = target as Record<string, unknown>
  for (const [k, v] of Object.entries(draft)) {
    if (JSON.stringify(t[k] ?? null) !== JSON.stringify(v ?? null)) t[k] = v
  }
}

/** What a DMX fixture's own channels drive, where a conventional has a spanner. */
function DmxFocusNote({ fixture }: { fixture: Fixture | undefined }) {
  const props = fixture?.properties
  const driven = [
    findZoomProperty(props) && 'zoom',
    findFocusProperty(props) && 'focus',
    findIrisProperty(props) && 'iris',
    findFrostProperty(props) && 'frost',
  ].filter((x): x is string => typeof x === 'string')
  return (
    <p className="text-sm text-muted-foreground">
      {driven.length > 0
        ? `${fixture?.name ?? 'This fixture'} drives its ${joinWords(driven)} from its channels, so its looks set them — there is nothing to focus with a spanner.`
        : `${fixture?.name ?? 'This fixture'} has no focus data: its optics are fixed, or set from its channels.`}
    </p>
  )
}

function joinWords(words: string[]): string {
  if (words.length <= 1) return words.join('')
  return `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`
}

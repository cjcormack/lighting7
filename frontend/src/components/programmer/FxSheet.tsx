import { useCallback, useMemo, useState } from 'react'
import { Layers, Loader2, Search, Sparkles, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { FIXTURE_FILTER_HINT, FIXTURE_FILTER_PLACEHOLDER } from '@/lib/fixtureFilterCopy'
import { usePersistentState } from '../../hooks/usePersistentState'
import { useVisibleFixtureListQuery } from '../../store/fixtures'
import { useGroupListQuery } from '../../store/groups'
import { useActiveEffectsQuery, useRemoveFxMutation } from '../../store/fixtureFx'
import { useSpeedMasterDisplay } from '../../store/speedMasters'
import { useRemoveGroupFxMutation } from '../../store/groups'
import { lightingApi } from '../../api/lightingApi'
import { isHeldBack } from '../../lib/heldBack'
import { useProgrammerRevision } from '../../store/programmer'
import { COLUMN_DEFS, resolutionPropertyNames } from '../fixtures-list/columns'
import { cellEffectKey, membersByGroupOf } from '../fixtures-list/cellEffects'
import { buildRows, resolveTargetCells, rowWriteTargets } from '../fixtures-list/rowModel'
import { ActiveEffectSheet } from '../busking/ActiveEffectSheet'
import type { ActiveEffect } from '../../store/fixtureFx'
import { toEffectContext } from '../busking/buskingTypes'
import type { ColumnKey } from '../fixtures-list/columns'
import type { Row } from '../fixtures-list/rowModel'
import type { Fixture } from '../../store/fixtures'
import type { GroupSummary } from '../../api/groupsApi'

const EMPTY_FIXTURES: Fixture[] = []
const EMPTY_GROUPS: GroupSummary[] = []
const GROUPED_KEY = 'programmer.fx.grouped'
const ROW_HEIGHT_CLASS = 'min-h-9'

const programmerHolds = (headKey: string, propertyName: string) =>
  lightingApi.programmer.getKeyState(headKey, propertyName).entry != null

/** Where one effect lands: which fixtures it covers, and under which column. */
interface PlacedEffect {
  effect: ActiveEffect
  /** Fixture keys the effect paints — a group effect expands to its members. */
  fixtureKeys: string[]
}

/**
 * The FX sheet: the same rows, columns and grouping as the programmer's values sheet, with
 * each cell showing the effects covering that fixture × category as chips.
 *
 * This is the "see applied FX by group and fixture" view. Two things it makes visible that
 * nothing else in the app does:
 *
 * - **Suppression.** A programmer entry on a property suppresses every non-band effect on it
 *   (that is what makes Locate non-destructive). Suppressed chips render struck through, so
 *   "why isn't my effect doing anything" has an answer on screen.
 * - **Programmer-owned FX.** Busking effects sit in the priority band, compose on top of
 *   programmer values, and go when Clear goes. They are badged so that isn't a surprise.
 */
export function FxSheet() {
  // The rows it lists are the rig an operator is offered: never an infrastructure fixture.
  const { data: maybeFixtures, isLoading: fixturesLoading } = useVisibleFixtureListQuery()
  const { data: maybeGroups, isLoading: groupsLoading } = useGroupListQuery()
  const { data: maybeEffects, isLoading: effectsLoading } = useActiveEffectsQuery()
  const [removeFx] = useRemoveFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const fixtures = maybeFixtures ?? EMPTY_FIXTURES
  const groups = maybeGroups ?? EMPTY_GROUPS
  const effects = useMemo(() => maybeEffects ?? [], [maybeEffects])

  const [filter, setFilter] = useState('')
  const [grouped, setGrouped] = usePersistentState<boolean>(GROUPED_KEY, false)
  const [expandedGroups, setExpandedGroups] = useState<ReadonlySet<string>>(new Set())
  const [editing, setEditing] = useState<ActiveEffect | null>(null)
  // Memoised on `editing` rather than rebuilt inline: `ActiveEffectSheet` re-seeds its draft
  // whenever this object's identity changes, and this component re-renders on every
  // `FixtureEffects` invalidation — so a fresh context per render discards an in-progress edit.
  const editingContext = useMemo(() => (editing == null ? null : toEffectContext(editing)), [editing])

  // Suppression is read from live programmer state (see `isSuppressed`), which no query
  // covers — without this subscription a locate would grey the stage but leave every chip
  // here looking healthy.
  useProgrammerRevision()

  // Group membership, so a group-targeted effect can be attributed to its members. Shared with the
  // marquee's effect clear (`cellEffects.ts`), which expands a group target the same way.
  const membersByGroup = useMemo(() => membersByGroupOf(fixtures), [fixtures])

  const placed = useMemo<PlacedEffect[]>(
    () =>
      effects.map((effect) => ({
        effect,
        fixtureKeys: effect.isGroupTarget
          ? (membersByGroup.get(effect.targetKey) ?? [])
          : [effect.targetKey],
      })),
    [effects, membersByGroup],
  )

  /**
   * Index by [cellEffectKey] — `fixtureKey|propertyName`, the format the marquee's effect clear
   * matches on too, so the two cannot drift.
   */
  const byKey = useMemo(() => {
    const map = new Map<string, ActiveEffect[]>()
    for (const { effect, fixtureKeys } of placed) {
      for (const fixtureKey of fixtureKeys) {
        const id = cellEffectKey(fixtureKey, effect.propertyName)
        const list = map.get(id)
        if (list) list.push(effect)
        else map.set(id, [effect])
      }
    }
    return map
  }, [placed])

  const rows = useMemo(
    () =>
      buildRows({
        fixtures,
        groups,
        expandedGroups,
        textFilter: filter,
        groupByGroups: grouped,
      }),
    [fixtures, groups, expandedGroups, filter, grouped],
  )

  // Only columns some effect actually targets — an all-empty Gobo column is noise here, where
  // in the values sheet it is a legitimate "nothing set".
  const visibleColumns = useMemo<ColumnKey[]>(() => {
    const active = new Set<ColumnKey>()
    for (const row of rows) {
      if (row.kind === 'divider') continue
      for (const target of rowWriteTargets(row)) {
        for (const col of COLUMN_DEFS) {
          if (active.has(col.key)) continue
          for (const { target: resolved, resolution } of resolveTargetCells(target, col.key)) {
            const names = resolutionPropertyNames(resolution)
            if (names.some((n) => byKey.has(cellEffectKey(resolved.key, n)))) {
              active.add(col.key)
              break
            }
          }
        }
      }
    }
    return COLUMN_DEFS.filter((c) => active.has(c.key)).map((c) => c.key)
  }, [rows, byKey])

  const effectsForCell = useCallback(
    (row: Row, col: ColumnKey): ActiveEffect[] => {
      if (row.kind === 'divider') return []
      const seen = new Set<number>()
      const out: ActiveEffect[] = []
      for (const target of rowWriteTargets(row)) {
        for (const { target: resolved, resolution } of resolveTargetCells(target, col)) {
          for (const name of resolutionPropertyNames(resolution)) {
            for (const effect of byKey.get(cellEffectKey(resolved.key, name)) ?? []) {
              if (seen.has(effect.id)) continue
              seen.add(effect.id)
              out.push(effect)
            }
          }
        }
      }
      return out
    },
    [byKey],
  )

  /**
   * Whether the programmer holds this effect back on any head of this row — `lib/heldBack.ts`'s
   * rule, the desk's own (`EffectSuppression`): a non-band effect loses to a programmer entry on the
   * key it paints, its own property on that head. The fixture sheet's amber dot reads the same rule.
   */
  const isSuppressed = useCallback((row: Row, col: ColumnKey, effect: ActiveEffect): boolean => {
    if (row.kind === 'divider') return false
    const blind = lightingApi.programmer.isBlind()
    for (const target of rowWriteTargets(row)) {
      for (const { target: resolved } of resolveTargetCells(target, col)) {
        if (isHeldBack(effect, resolved.key, programmerHolds, blind)) return true
      }
    }
    return false
  }, [])

  const stopEffect = useCallback(
    (effect: ActiveEffect) => {
      const request = effect.isGroupTarget
        ? removeGroupFx({ id: effect.id, groupName: effect.targetKey })
        : removeFx({ id: effect.id, fixtureKey: effect.targetKey })
      request.unwrap().catch(() => {
        // Reported by errorToastMiddleware; caught here only to stop the unhandled rejection.
      })
    },
    [removeFx, removeGroupFx],
  )

  if (fixturesLoading || groupsLoading || effectsLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="size-5 animate-spin" />
      </div>
    )
  }

  // Same responsive name column as the values sheet, so the two views line up.
  const gridTemplateColumns = `min(45vw, 260px) repeat(${visibleColumns.length}, minmax(120px, 1fr))`

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-48 flex-1">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder={FIXTURE_FILTER_PLACEHOLDER}
            title={FIXTURE_FILTER_HINT}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="h-8 pl-9"
          />
        </div>
        <Button
          variant={grouped ? 'default' : 'outline'}
          size="sm"
          aria-pressed={grouped}
          onClick={() => setGrouped(!grouped)}
          title="Show group rows with their members"
        >
          <Layers className="size-3.5" />
          Groups
        </Button>
        <span className="text-xs text-muted-foreground tabular-nums">
          {effects.length} running
        </span>
      </div>

      {visibleColumns.length === 0 ? (
        <p className="py-8 text-center text-muted-foreground">
          {effects.length === 0
            ? 'No effects are running'
            : 'No running effects match your filter'}
        </p>
      ) : (
        <div className="overflow-auto rounded-md border">
          <div style={{ minWidth: `calc(min(45vw, 260px) + ${visibleColumns.length * 120}px)` }}>
            <div
              className="sticky top-0 z-20 grid border-b bg-muted/50 backdrop-blur"
              style={{ gridTemplateColumns }}
            >
              <div className="sticky left-0 z-10 bg-muted/50 px-2 py-1.5 text-[11px] font-medium uppercase tracking-wider text-muted-foreground">
                Fixture
              </div>
              {visibleColumns.map((col) => (
                <div
                  key={col}
                  className="px-1.5 py-1.5 text-[11px] font-medium uppercase tracking-wider text-muted-foreground"
                >
                  {COLUMN_DEFS.find((c) => c.key === col)?.label ?? col}
                </div>
              ))}
            </div>

            {rows.map((row) => {
              if (row.kind === 'divider') {
                return (
                  <div
                    key={row.id}
                    className="flex items-center border-b bg-muted/30 px-2 py-1 text-[11px] font-medium uppercase tracking-wider text-muted-foreground"
                  >
                    {row.label}
                  </div>
                )
              }
              const isGroupRow = row.kind === 'group'
              const name = isGroupRow
                ? row.name
                : row.kind === 'element'
                  ? `${row.fixture.name} ${row.element.displayName}`
                  : row.fixture.name
              return (
                <div
                  key={row.id}
                  className={cn('grid border-b text-sm', ROW_HEIGHT_CLASS)}
                  style={{ gridTemplateColumns }}
                >
                  <div className="sticky left-0 z-10 flex items-center gap-1.5 bg-background px-2 py-1">
                    {isGroupRow && (
                      <button
                        type="button"
                        className="text-muted-foreground hover:text-foreground"
                        onClick={() =>
                          setExpandedGroups((prev) => {
                            const next = new Set(prev)
                            if (next.has(row.name)) next.delete(row.name)
                            else next.add(row.name)
                            return next
                          })
                        }
                        aria-label={row.isExpanded ? 'Collapse group' : 'Expand group'}
                      >
                        <Layers className="size-3.5" />
                      </button>
                    )}
                    <span className="truncate" title={name}>
                      {name}
                    </span>
                  </div>
                  {visibleColumns.map((col) => {
                    const cellEffects = effectsForCell(row, col)
                    return (
                      <div key={col} className="flex flex-wrap items-center gap-1 px-1.5 py-1">
                        {cellEffects.map((effect) => (
                          <EffectChip
                            key={effect.id}
                            effect={effect}
                            suppressed={isSuppressed(row, col, effect)}
                            onOpen={() => setEditing(effect)}
                            onStop={() => stopEffect(effect)}
                          />
                        ))}
                      </div>
                    )
                  })}
                </div>
              )
            })}
          </div>
        </div>
      )}

      <ActiveEffectSheet context={editingContext} onClose={() => setEditing(null)} />
    </div>
  )
}

function EffectChip({
  effect,
  suppressed,
  onOpen,
  onStop,
}: {
  effect: ActiveEffect
  suppressed: boolean
  onOpen: () => void
  onStop: () => void
}) {
  const intensityPct = Math.round(effect.intensityMultiplier * 100)
  // Shared hide-at-M1 rule + own-master-only subscription — see useSpeedMasterDisplay.
  const master = useSpeedMasterDisplay(effect.speedMasterUuid)
  const masterLabel = master ? `M${master.index}` : null
  const tip = [
    effect.effectType,
    effect.isGroupTarget ? `group ${effect.targetKey}` : null,
    `${intensityPct}% intensity`,
    effect.blendMode,
    master ? `speed master M${master.index} — ${master.name}` : null,
    effect.programmerOwned ? 'Programmer FX — Clear removes it' : null,
    suppressed ? 'Suppressed: the programmer holds this property' : null,
    !effect.isRunning ? 'Paused' : null,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span
          className={cn(
            'inline-flex max-w-full items-center gap-1 rounded-full border py-0.5 pl-2 pr-0.5 text-[11px]',
            effect.programmerOwned
              ? 'border-primary/60 bg-primary/10 text-primary'
              : 'border-violet-500/50 bg-violet-500/10 text-violet-700 dark:text-violet-300',
            suppressed && 'opacity-50 line-through',
          )}
        >
          {effect.programmerOwned && <Sparkles className="size-3 shrink-0" />}
          <button type="button" onClick={onOpen} className="truncate hover:underline">
            {effect.effectType}
          </button>
          {masterLabel && (
            <span className="shrink-0 font-mono text-[10px] opacity-70">{masterLabel}</span>
          )}
          {intensityPct < 100 && (
            <span className="shrink-0 tabular-nums opacity-70">{intensityPct}%</span>
          )}
          <button
            type="button"
            onClick={onStop}
            aria-label={`Stop ${effect.effectType}`}
            className="shrink-0 rounded-full p-0.5 hover:bg-foreground/10"
          >
            <X className="size-3" />
          </button>
        </span>
      </TooltipTrigger>
      <TooltipContent>{tip}</TooltipContent>
    </Tooltip>
  )
}

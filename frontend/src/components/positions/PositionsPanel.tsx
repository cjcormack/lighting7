import { Suspense, lazy, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { Box, Loader2, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { cn } from '@/lib/utils'
import { perceptualBrightness } from '@/lib/colourMath'
import { useViewedProject } from '../../ProjectSwitcher'
import { usePatchGroupListQuery, useVisiblePatchListQuery } from '../../store/patches'
import { useRiggingListQuery } from '../../store/riggings'
import { useFixtureLookup } from '../../hooks/useFixtureLookup'
import { StageChannelSourceProvider } from '../../hooks/useChannelSource'
import { FixtureAppearanceSource, type FixtureAppearance } from '../fixtures/fixtureAppearance'
import { chipButtonClassName } from '../patches/chipButton'
import { CollapsiblePanel } from '../CollapsiblePanel'
import { clearDeskSelection, setDeskSelection, toggleDeskSelection, useDeskSelection } from '../../store/selection'
import { setStageViewpoint } from '../../lib/stageViewpoint'
import type { CueTarget } from '../../api/cuesApi'
import type { FixturePatch } from '../../api/patchApi'
import {
  findColourSource,
  findDimmerProperty,
  findGroupColourSource,
  type Fixture,
  type FixtureTypeInfo,
} from '../../store/fixtures'
import { positionRows, stageEdgeIndex, type PositionChip, type PositionRow } from './positionRows'

const PositionsPlan = lazy(() => import('./PositionsPlan'))

/** The Plan tab's height — the old overview's box, so the header toggle opens to the same size. */
const PLAN_HEIGHT = 'h-[420px]'

type PositionsTab = 'positions' | 'plan'

/**
 * **Positions** (stage-view plan session 1; `Positions.dc.html` is the layout authority), behind the
 * header's stage toggle on every route. It replaced `StageOverviewPanel`, which placed every
 * fixture's name by percentage in a 420 px box with no decluttering — unreadable at 45 fixtures,
 * and no labelling scheme fixes a true-scale plan of three bars a metre apart (the design record's
 * §"Both 2D surfaces lose to label density"). Rows cannot collide, and read on a phone as the same
 * rows stacked.
 *
 * - **Rows are positions, upstage first**, the stage edge marked, derived from the rig on every
 *   render (`positionRows.ts`, `FU-BUSK-RIG-PLOT`'s rule).
 * - **A chip is the desk selection** (`selection.set`): a tap selects that unit on every window
 *   following the desk — the programmer, the busk band, the Stage view's *Frame the selection* —
 *   and ⇧ or ⌘ adds or takes it away. The swatch is the unit's live colour at level.
 * - **A group chip filters by dimming**: units outside the group fade and every row keeps its place,
 *   so the geometry never jumps.
 * - **Plan is a camera**, the Stage view's plan section in the panel's second tab, loaded only while
 *   that tab is showing.
 *
 * The tab and the group filter live out here, above the collapse boundary, so they survive a close
 * and reopen; everything below it — every query and every channel subscription — unmounts with the
 * body, so a closed panel costs the rig nothing on any route (`CollapsiblePanel`).
 */
export function PositionsPanel({ isVisible }: { isVisible: boolean }) {
  const [tab, setTab] = useState<PositionsTab>('positions')
  // The filter names the project it was set in: group ids are the desk's, not the project's, so a
  // filter carried into another project would match nothing and dim every chip on the sheet.
  const [groupFilter, setGroupFilter] = useState<GroupFilter | null>(null)
  return (
    <CollapsiblePanel isVisible={isVisible}>
      <PositionsPanelBody tab={tab} onTabChange={setTab} groupFilter={groupFilter} onGroupFilterChange={setGroupFilter} />
    </CollapsiblePanel>
  )
}

interface GroupFilter {
  projectId: number
  groupId: number
}

interface BodyProps {
  tab: PositionsTab
  onTabChange: (tab: PositionsTab) => void
  groupFilter: GroupFilter | null
  onGroupFilterChange: (filter: GroupFilter | null) => void
}

function PositionsPanelBody({ tab, onTabChange, groupFilter: storedFilter, onGroupFilterChange }: BodyProps) {
  const project = useViewedProject()
  const projectId = project?.id
  const groupFilter = storedFilter != null && storedFilter.projectId === projectId ? storedFilter.groupId : null
  const setGroupFilter = (groupId: number | null) =>
    onGroupFilterChange(groupId == null || projectId == null ? null : { projectId, groupId })
  const navigate = useNavigate()
  const skip = projectId == null
  // Infrastructure never reaches the sheet, as it never reaches the Stage view.
  const { data: patches, isLoading } = useVisiblePatchListQuery(projectId ?? 0, { skip })
  const { data: riggings } = useRiggingListQuery(projectId ?? 0, { skip })
  const { data: groups } = usePatchGroupListQuery(projectId ?? 0, { skip })
  const { fixtureByKey, typeByKey } = useFixtureLookup()
  const selection = useDeskSelection()

  const rows = useMemo(() => positionRows(patches ?? [], riggings ?? []), [patches, riggings])
  const edgeAt = stageEdgeIndex(rows)
  const placedCount = useMemo(
    () => new Set(rows.flatMap((row) => row.chips.map((chip) => chip.patch.key))).size,
    [rows],
  )
  const visibleGroups = (groups ?? []).filter((g) => g.memberCount > 0)
  const selected = useMemo(() => selectedMatcher(selection), [selection])
  const firstSelectedKey = selection.find((t) => t.type === 'fixture')?.key ?? null

  const press = (patch: FixturePatch, extend: boolean) => {
    const target: CueTarget = { type: 'fixture', key: patch.key }
    if (extend) {
      toggleDeskSelection(target)
    } else if (selection.length === 1 && selection[0].type === 'fixture' && selection[0].key === patch.key) {
      clearDeskSelection()
    } else {
      setDeskSelection([target])
    }
  }

  const openInStage = () => {
    if (projectId == null) return
    // "The same camera": from the Plan tab the Stage view opens on its plan section.
    if (tab === 'plan') setStageViewpoint('plan')
    void navigate(`/projects/${projectId}/stage`)
  }

  return (
    <div className="border-b bg-background">
      <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2">
        <span className="size-2 rounded-full bg-primary" style={{ boxShadow: '0 0 8px currentColor' }} />
        <span className="text-sm font-semibold">Stage</span>
        <span className="border-l pl-2 font-mono text-xs text-muted-foreground">
          {placedCount} fixture{placedCount === 1 ? '' : 's'}
        </span>
        <ToggleGroup
          type="single"
          size="sm"
          value={tab}
          aria-label="Positions view"
          onValueChange={(v) => {
            if (v === 'positions' || v === 'plan') onTabChange(v)
          }}
        >
          <ToggleGroupItem value="positions" className="px-2.5">
            Positions
          </ToggleGroupItem>
          <ToggleGroupItem value="plan" className="px-2.5">
            Plan
          </ToggleGroupItem>
        </ToggleGroup>
        <div className="flex-1" />
        <span className="hidden text-xs text-muted-foreground lg:inline">
          Upstage at the top · units in rig order as seen from the desk
        </span>
        {groupFilter != null && (
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" onClick={() => setGroupFilter(null)}>
            <RotateCcw className="size-3.5" />
            Reset
          </Button>
        )}
        <Button variant="outline" size="sm" className="h-7 px-2 text-xs" onClick={openInStage} disabled={projectId == null}>
          <Box className="size-3.5" />
          Open in Stage
        </Button>
      </div>

      {visibleGroups.length > 0 && (
        <div className="flex flex-wrap gap-1.5 border-b px-4 py-2">
          <ChipButton active={groupFilter == null} onClick={() => setGroupFilter(null)}>
            All <span className="ml-1 font-mono text-[10px] opacity-70">{placedCount}</span>
          </ChipButton>
          {visibleGroups.map((g) => (
            <ChipButton
              key={g.id}
              active={groupFilter === g.id}
              onClick={() => setGroupFilter(groupFilter === g.id ? null : g.id)}
            >
              {g.name}
              <span className="ml-1 font-mono text-[10px] opacity-70">{g.memberCount}</span>
            </ChipButton>
          ))}
        </div>
      )}

      {isLoading ? (
        <div className={cn('flex items-center justify-center', PLAN_HEIGHT)}>
          <Loader2 className="size-4 animate-spin text-muted-foreground" />
        </div>
      ) : placedCount === 0 ? (
        <EmptyState projectId={projectId} />
      ) : tab === 'plan' && projectId != null ? (
        <div className={cn('relative', PLAN_HEIGHT)}>
          <Suspense
            fallback={
              <div className="flex h-full items-center justify-center">
                <Loader2 className="size-4 animate-spin text-muted-foreground" />
              </div>
            }
          >
            <PositionsPlan
              projectId={projectId}
              selectedKey={firstSelectedKey}
              soleSelected={selection.length === 1 && selection[0].type === 'fixture'}
            />
          </Suspense>
        </div>
      ) : (
        // Follows the same vis source the Stage view's View menu sets, so the two agree whenever
        // they are on screen together.
        <StageChannelSourceProvider>
          <div className="max-h-[60vh] overflow-y-auto px-4 py-2" role="list" aria-label="Positions">
            {rows.map((row, i) => (
              <div key={row.id} role="listitem">
                {i === edgeAt && <StageEdge />}
                <Row
                  row={row}
                  fixtureByKey={fixtureByKey}
                  typeByKey={typeByKey}
                  isSelected={selected}
                  isDimmed={(patch) => groupFilter != null && !patch.groups.some((g) => g.id === groupFilter)}
                  onPress={press}
                />
              </div>
            ))}
            {edgeAt === rows.length && <StageEdge />}
          </div>
        </StageChannelSourceProvider>
      )}
    </div>
  )
}

/** Which patches the desk selection covers: a fixture target by key, a group target by name. */
function selectedMatcher(targets: readonly CueTarget[]): (patch: FixturePatch) => boolean {
  const keys = new Set<string>()
  const groupNames = new Set<string>()
  for (const t of targets) {
    if (t.type === 'fixture') keys.add(t.key)
    else groupNames.add(t.key)
  }
  return (patch) => keys.has(patch.key) || patch.groups.some((g) => groupNames.has(g.name))
}

function StageEdge() {
  return (
    <div className="my-1 flex items-center gap-2" aria-label="Stage edge">
      <div className="flex-1 border-t border-dashed border-muted-foreground/40" />
      <span className="font-mono text-[10px] tracking-[0.2em] text-muted-foreground">STAGE EDGE</span>
      <div className="flex-1 border-t border-dashed border-muted-foreground/40" />
    </div>
  )
}

function formatDepth(depthM: number): string {
  // A true minus, as the board draws it; and no "−0.0".
  const rounded = Math.round(depthM * 10) / 10
  return `${rounded < 0 ? '−' : ''}${Math.abs(rounded).toFixed(1)} m`
}

const PLAIN_KINDS = new Set(['TRUSS', 'PIPE', 'BAR', 'OTHER'])

function Row({
  row,
  fixtureByKey,
  typeByKey,
  isSelected,
  isDimmed,
  onPress,
}: {
  row: PositionRow
  fixtureByKey: Map<string, Fixture>
  typeByKey: Map<string, FixtureTypeInfo>
  isSelected: (patch: FixturePatch) => boolean
  isDimmed: (patch: FixturePatch) => boolean
  onPress: (patch: FixturePatch, extend: boolean) => void
}) {
  // A kind that says how the units are held rather than that they hang (a boom, a floor stand, the
  // balcony ledge session 2 adds) is worth a word under the name; a bar is the default, and *other*
  // says nothing.
  const kind = row.kind && !PLAIN_KINDS.has(row.kind) ? row.kind.toLowerCase().replace(/_/g, ' ') : null
  return (
    <div className="flex flex-col gap-1 border-b border-border/60 py-1.5 last:border-b-0 sm:flex-row sm:items-center sm:gap-3">
      <div className="flex shrink-0 items-baseline gap-2 sm:w-24 sm:flex-col sm:items-start sm:gap-0">
        <span className="truncate font-mono text-xs font-bold text-amber-500 dark:text-amber-400" title={row.name}>
          {row.name}
        </span>
        <span className="font-mono text-[10px] text-muted-foreground">
          {formatDepth(row.depthM)}
          {kind && ` · ${kind}`}
        </span>
      </div>
      <div className="flex min-w-0 flex-wrap gap-1.5">
        {row.chips.map((chip) => {
          const fixture = fixtureByKey.get(chip.patch.key)
          const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined
          return (
            <UnitChip
              key={chip.id}
              chip={chip}
              fixture={fixture}
              fixtureType={fixtureType}
              selected={isSelected(chip.patch)}
              dimmed={isDimmed(chip.patch)}
              onPress={onPress}
            />
          )
        })}
      </div>
    </div>
  )
}

function UnitChip({
  chip,
  fixture,
  fixtureType,
  selected,
  dimmed,
  onPress,
}: {
  chip: PositionChip
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  selected: boolean
  dimmed: boolean
  onPress: (patch: FixturePatch, extend: boolean) => void
}) {
  // A unit with neither a dimmer nor a colour — a hazer, the Twin Shot, a laser — has no level to
  // show: its appearance reads as full, and a chip saying "100" all night would be a claim about
  // it. Its swatch stays dark and says nothing (session 9 gives the cannon its own face).
  const hasLevel = useMemo(
    () =>
      fixture != null &&
      (findDimmerProperty(fixture.properties) != null ||
        (fixture.properties != null && findColourSource(fixture.properties) != null) ||
        findGroupColourSource(fixture) != null),
    [fixture],
  )
  return (
    <button
      type="button"
      aria-pressed={selected}
      aria-label={chip.count > 1 ? `${chip.patch.displayName} ×${chip.count}` : chip.patch.displayName}
      title={chip.patch.displayName}
      onClick={(e) => onPress(chip.patch, e.shiftKey || e.metaKey || e.ctrlKey)}
      className={cn(
        'inline-flex h-7 items-center gap-1.5 rounded-md border px-1.5 text-xs transition-[opacity,colors]',
        selected
          ? 'border-primary bg-primary/15 text-foreground ring-1 ring-primary'
          : 'border-input bg-background hover:border-muted-foreground/60',
        dimmed && 'opacity-30',
      )}
    >
      {/* One source per chip: the swatch and the level read the same subscription. */}
      <FixtureAppearanceSource patch={chip.patch} fixture={fixture} fixtureType={fixtureType}>
        {(appearance) => (
          <>
            <ChipLive appearance={hasLevel ? appearance : UNLIT} />
            <span className="max-w-[10rem] truncate">{chip.label}</span>
            {chip.count > 1 && <span className="font-mono text-[10px] text-muted-foreground">×{chip.count}</span>}
            {hasLevel && <ChipLevel intensity={appearance.intensity} />}
          </>
        )}
      </FixtureAppearanceSource>
    </button>
  )
}

const UNLIT: FixtureAppearance = { color: '#000', intensity: 0 }

/**
 * The swatch: the unit's colour at its level, dark at zero like the Stage view's lenses. A
 * multi-cell unit (a batten, a pixel bar) splits it per cell.
 */
function ChipLive({ appearance }: { appearance: FixtureAppearance }) {
  const { color, intensity, segments } = appearance
  if (segments && segments.length > 1) {
    return (
      <span className="flex h-3.5 w-3.5 shrink-0 overflow-hidden rounded-[3px] border border-white/15 bg-black">
        {segments.map((seg, i) => (
          <span key={i} className="h-full flex-1" style={{ backgroundColor: seg.css, opacity: perceptualBrightness(seg.intensity) }} />
        ))}
      </span>
    )
  }
  return (
    <span className="relative h-3.5 w-3.5 shrink-0 overflow-hidden rounded-[3px] border border-white/15 bg-black">
      <span className="absolute inset-0" style={{ backgroundColor: color, opacity: perceptualBrightness(intensity) }} />
    </span>
  )
}

function ChipLevel({ intensity }: { intensity: number }) {
  const level = Math.round(intensity * 100)
  if (level <= 0) return null
  return <span className="font-mono text-[10px] tabular-nums text-muted-foreground">{level}</span>
}

function ChipButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs', chipButtonClassName(active))}
    >
      {children}
    </button>
  )
}

function EmptyState({ projectId }: { projectId: number | undefined }) {
  const navigate = useNavigate()
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-4 py-10 text-center">
      <p className="max-w-md text-sm text-muted-foreground">
        No fixtures placed yet. Open a patch and set its stage position.
      </p>
      {projectId != null && (
        <Button variant="outline" size="sm" onClick={() => void navigate(`/projects/${projectId}/patches`)}>
          Open patches
        </Button>
      )}
    </div>
  )
}

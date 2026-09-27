import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { Loader2, RotateCcw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { useViewedProject } from '../ProjectSwitcher'
import { usePatchListQuery, usePatchGroupListQuery } from '../store/patches'
import { useRiggingListQuery } from '../store/riggings'
import { useFixtureLookup } from '../hooks/useFixtureLookup'
import { useProjectedPatches, type DrawnPoint } from '../hooks/useProjectedPatches'
import { StageChannelSourceProvider } from '../hooks/useChannelSource'
import { StageMarker } from './stage/StageMarker'
import { StageBackdrop } from './stage/StageBackdrop'
import { chipButtonClassName } from './patches/chipButton'
import { CollapsiblePanel } from './CollapsiblePanel'

const STAGE_CANVAS_HEIGHT = 'h-[420px]'

interface StageOverviewPanelProps {
  isVisible: boolean
  selectedFixtureKey: string | null
  onFixtureClick: (fixtureKey: string) => void
}

/**
 * The group filter lives out here rather than in the body: the body unmounts on collapse, and an
 * operator who filters to a group, closes the panel and reopens it should find their filter still
 * on. Everything below the boundary is a live subscription and stays there.
 */
export function StageOverviewPanel({
  isVisible,
  selectedFixtureKey,
  onFixtureClick,
}: StageOverviewPanelProps) {
  const [groupFilter, setGroupFilter] = useState<number | null>(null)

  return (
    <CollapsiblePanel isVisible={isVisible}>
      <StageOverviewPanelBody
        selectedFixtureKey={selectedFixtureKey}
        onFixtureClick={onFixtureClick}
        groupFilter={groupFilter}
        onGroupFilterChange={setGroupFilter}
      />
    </CollapsiblePanel>
  )
}

interface StageOverviewPanelBodyProps {
  selectedFixtureKey: string | null
  onFixtureClick: (fixtureKey: string) => void
  groupFilter: number | null
  onGroupFilterChange: (groupId: number | null) => void
}

function StageOverviewPanelBody({
  selectedFixtureKey,
  onFixtureClick,
  groupFilter,
  onGroupFilterChange,
}: StageOverviewPanelBodyProps) {
  const project = useViewedProject()
  const projectId = project?.id

  const { isLoading: patchesLoading } = usePatchListQuery(projectId!, {
    skip: projectId == null,
  })
  const { fixtureByKey, typeByKey } = useFixtureLookup()
  const { data: groups } = usePatchGroupListQuery(projectId!, {
    skip: projectId == null,
  })
  // The marker badge names the rigging a patch hangs on, and a patch only carries its uuid. Read
  // rather than derived per marker: the list is one cached query the Stage route already holds, so
  // this shares it rather than adding a fetch.
  const { data: riggings } = useRiggingListQuery(projectId!, { skip: projectId == null })
  const riggingNameByUuid = useMemo(
    () => new Map((riggings ?? []).map((rig) => [rig.uuid, rig.name])),
    [riggings],
  )

  // The selected patch survives the `stageHidden` cut: this panel shares its
  // selection with Stage3D (see routes/Stage.tsx), which draws the selected
  // hidden patch so the operator can see what they're about to un-hide. Dropping
  // it here would blank the highlight on one surface while it shows on the other.
  const { points: placedPatches, extraPoints } = useProjectedPatches(projectId, {
    includeKey: selectedFixtureKey,
  })
  // A paired dimmer is one fixture drawn more than once, so the count is of fixtures on the
  // plot, not of markers.
  const drawnPoints = useMemo<DrawnPoint[]>(
    () => [...placedPatches, ...extraPoints],
    [placedPatches, extraPoints],
  )
  const placedCount = useMemo(
    () => new Set(drawnPoints.map(({ patch }) => patch.key)).size,
    [drawnPoints],
  )

  const visibleGroups = (groups ?? []).filter((g) => g.memberCount > 0)
  const showChips = visibleGroups.length > 0

  return (
    <div className="border-b bg-background">
      <div className="flex items-center gap-2 px-4 py-2 border-b">
        <span
          className="size-2 rounded-full bg-primary"
          style={{ boxShadow: '0 0 8px currentColor' }}
        />
        <span className="text-sm font-semibold">Stage</span>
        <span className="text-xs font-mono text-muted-foreground border-l pl-2">
          {placedCount} fixture{placedCount === 1 ? '' : 's'}
        </span>
        <div className="flex-1" />
        {groupFilter != null && (
          <Button
            variant="ghost"
            size="sm"
            className="h-7 px-2 text-xs"
            onClick={() => onGroupFilterChange(null)}
            title="Reset filter"
          >
            <RotateCcw className="size-3.5" />
            Reset
          </Button>
        )}
      </div>

      {showChips && (
        <div className="flex flex-wrap gap-1.5 px-4 py-2 border-b">
          <ChipButton
            active={groupFilter == null}
            onClick={() => onGroupFilterChange(null)}
          >
            All <span className="ml-1 font-mono text-[10px] opacity-70">{placedCount}</span>
          </ChipButton>
          {visibleGroups.map((g) => (
            <ChipButton
              key={g.id}
              active={groupFilter === g.id}
              onClick={() => onGroupFilterChange(groupFilter === g.id ? null : g.id)}
            >
              {g.name}
              <span className="ml-1 font-mono text-[10px] opacity-70">{g.memberCount}</span>
            </ChipButton>
          ))}
        </div>
      )}

      <div className="p-4">
        {patchesLoading ? (
          <div className={cn('flex items-center justify-center', STAGE_CANVAS_HEIGHT)}>
            <Loader2 className="size-4 animate-spin text-muted-foreground" />
          </div>
        ) : placedCount === 0 ? (
          <EmptyState projectId={projectId} />
        ) : (
          <StageChannelSourceProvider>
            {/* Follows the same vis source the Stage route's View menu sets, so the two
                pictures agree whenever they are on screen together. */}
            <StageBackdrop className={STAGE_CANVAS_HEIGHT}>
              {drawnPoints.map(({ patch, leftPct, topPct, placement }) => {
                // An extra placement is the same fixture: it lights, filters and selects as the
                // patch, and only its position, rigging and label are its own.
                const riggingUuid = placement ? placement.riggingUuid : patch.riggingUuid
                const fixture = fixtureByKey.get(patch.key)
                const fixtureType = fixture
                  ? typeByKey.get(fixture.typeKey)
                  : undefined
                const matchesFilter =
                  groupFilter == null ||
                  patch.groups.some((g) => g.id === groupFilter)
                return (
                  <button
                    key={placement ? `${patch.id}:${placement.uuid}` : patch.id}
                    type="button"
                    onClick={() => onFixtureClick(patch.key)}
                    className="absolute -translate-x-1/2 -translate-y-1/2 cursor-pointer focus:outline-none"
                    style={{
                      left: `${leftPct}%`,
                      top: `${topPct}%`,
                    }}
                  >
                    <StageMarker
                      patch={patch}
                      fixture={fixture}
                      fixtureType={fixtureType}
                      selected={selectedFixtureKey === patch.key}
                      dimmed={!matchesFilter}
                      riggingName={
                        riggingUuid ? riggingNameByUuid.get(riggingUuid) : undefined
                      }
                      placementLabel={placement ? placement.label : undefined}
                    />
                  </button>
                )
              })}
            </StageBackdrop>
          </StageChannelSourceProvider>
        )}
      </div>
    </div>
  )
}

function ChipButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs',
        chipButtonClassName(active),
      )}
    >
      {children}
    </button>
  )
}

function EmptyState({ projectId }: { projectId: number | undefined }) {
  const navigate = useNavigate()
  return (
    <div className={cn('flex flex-col items-center justify-center gap-3 text-center', STAGE_CANVAS_HEIGHT)}>
      <p className="text-sm text-muted-foreground max-w-md">
        No fixtures placed yet. Open a patch and set its stage position.
      </p>
      {projectId != null && (
        <Button
          variant="outline"
          size="sm"
          onClick={() => navigate(`/projects/${projectId}/patches`)}
        >
          Open patches
        </Button>
      )}
    </div>
  )
}

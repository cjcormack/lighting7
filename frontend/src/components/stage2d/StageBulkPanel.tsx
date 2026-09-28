import { useMemo, useState } from 'react'
import {
  AlignHorizontalJustifyCenter,
  AlignVerticalJustifyCenter,
  FlipHorizontal,
  Ruler,
  Trash2,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import {
  alignTargets,
  arrayAlongRigging,
  distributeTargets,
  mirrorTargets,
  resolveBulkTargets,
  setDepthTargets,
  unplaceTargets,
  type AlignEdge,
} from '../../lib/stageBulkOps'
import type { StageProjection } from '../../lib/stageProjection'
import type { PlacementChange } from '../../store/stagePlacement'

interface StageBulkPanelProps {
  patches: FixturePatch[]
  riggings: RiggingDto[]
  projection: StageProjection
  /** Non-patch objects in the selection, for the composition banner. */
  regionCount: number
  riggingCount: number
  onApply: (changes: PlacementChange[], label: string, warnings?: string[]) => void
  onDismiss: () => void
}

/**
 * Actions for a multi-object selection.
 *
 * Deliberately a **count plus a list of actions**, not a tri-state field grid.
 * Mixed-value editing of gel and beam angle isn't what makes rigging slow, and
 * `startChannel` can't be bulk-edited at all — overlap validation is per-patch and
 * server-side. Align, distribute and array-along-truss are the operations that
 * actually collapse forty form visits into a handful of clicks.
 */
export function StageBulkPanel({
  patches,
  riggings,
  projection,
  regionCount,
  riggingCount,
  onApply,
  onDismiss,
}: StageBulkPanelProps) {
  const targets = useMemo(() => resolveBulkTargets(patches, riggings), [patches, riggings])
  // Hanging on a truss assigns position outright rather than transforming an
  // existing one, so it works on the whole selection — including fixtures with no
  // position yet. That's the case that matters: "Hang all on truss…" in the tray
  // selects precisely the unplaced fixtures, and gating it on `targets` (which
  // drops them) left the one action that would help them permanently disabled.
  const hangTargets = useMemo(() => patches.map((patch) => ({ patch })), [patches])
  const [hangRigUuid, setHangRigUuid] = useState<string>('')
  const [depthValue, setDepthValue] = useState('')

  const mounted = targets.filter((t) => t.rig != null).length
  const free = targets.length - mounted
  // Enablement counts FREE fixtures: align/distribute skip rig-mounted ones, so
  // offering them for a truss-only selection would be a button that does nothing.
  const canDistribute = free >= 3
  const canAlign = free >= 2
  // Only where the horizontal screen axis IS the stage's X — see the note below.
  const canMirror = projection.h.axis === 'x'

  const align = (edge: AlignEdge) => {
    const { changes, warnings } = alignTargets(targets, projection, edge)
    onApply(changes, 'Align', warnings)
  }
  const distribute = (axis: 'h' | 'v') => {
    const { changes, warnings } = distributeTargets(targets, projection, axis)
    onApply(changes, 'Distribute', warnings)
  }

  const hangOnRig = () => {
    const rig = riggings.find((r) => r.uuid === hangRigUuid)
    if (!rig) return
    const { changes, warnings } = arrayAlongRigging(hangTargets, { rig, spacing: { mode: 'even' } })
    onApply(changes, `Hang on ${rig.name}`, warnings)
  }

  const mirror = () => {
    const { changes, warnings } = mirrorTargets(targets, projection)
    onApply(changes, 'Mirror', warnings)
  }

  const setDepth = () => {
    const v = Number(depthValue)
    if (!Number.isFinite(v)) return
    const { changes, warnings } = setDepthTargets(targets, projection, v)
    onApply(changes, `Set ${projection.vAxisLabel}`, warnings)
  }

  const unplace = () =>
    onApply(unplaceTargets(targets.map((t) => t.patch.id)), 'Unplace')

  const nonPatches = regionCount + riggingCount

  return (
    <aside className="w-72 shrink-0 border-l bg-background flex flex-col">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <h2 className="text-sm font-semibold">{patches.length + nonPatches} selected</h2>
        <div className="flex-1" />
        <Button size="icon" variant="ghost" className="size-7" onClick={onDismiss} aria-label="Clear selection">
          <X className="size-4" />
        </Button>
      </div>

      <div className="flex-1 overflow-y-auto space-y-4 px-4 py-3">
        <p className="text-xs text-muted-foreground">
          {patches.length} fixture{patches.length === 1 ? '' : 's'}
          {mounted > 0 && ` · ${free} free, ${mounted} on rigging`}
          {nonPatches > 0 && ` · ${nonPatches} other object${nonPatches === 1 ? '' : 's'}`}
        </p>
        {targets.length < patches.length && (
          <p className="text-xs text-amber-600 dark:text-amber-400">
            {patches.length - targets.length} fixture
            {patches.length - targets.length === 1 ? '' : 's'} not on the stage yet — align and
            distribute need a starting position, but hanging on a truss doesn&apos;t.
          </p>
        )}

        <section className="space-y-2">
          <Label className="text-xs uppercase tracking-wide text-muted-foreground">Align</Label>
          <div className="grid grid-cols-3 gap-1">
            <Button size="sm" variant="outline" disabled={!canAlign} onClick={() => align('min-h')}>
              Left
            </Button>
            <Button size="sm" variant="outline" disabled={!canAlign} onClick={() => align('centre-h')}>
              <AlignHorizontalJustifyCenter className="size-3.5" />
            </Button>
            <Button size="sm" variant="outline" disabled={!canAlign} onClick={() => align('max-h')}>
              Right
            </Button>
            <Button size="sm" variant="outline" disabled={!canAlign} onClick={() => align('min-v')}>
              Top
            </Button>
            <Button size="sm" variant="outline" disabled={!canAlign} onClick={() => align('centre-v')}>
              <AlignVerticalJustifyCenter className="size-3.5" />
            </Button>
            <Button size="sm" variant="outline" disabled={!canAlign} onClick={() => align('max-v')}>
              Bottom
            </Button>
          </div>
        </section>

        <section className="space-y-2">
          <Label className="text-xs uppercase tracking-wide text-muted-foreground">
            Distribute
          </Label>
          <div className="grid grid-cols-2 gap-1">
            <Button size="sm" variant="outline" disabled={!canDistribute} onClick={() => distribute('h')}>
              Horizontally
            </Button>
            <Button size="sm" variant="outline" disabled={!canDistribute} onClick={() => distribute('v')}>
              Vertically
            </Button>
          </div>
          {!canDistribute && (
            <p className="text-[11px] text-muted-foreground">
              Needs three or more free fixtures.
            </p>
          )}
        </section>

        {riggings.length > 0 && (
          <section className="space-y-2">
            <Label className="text-xs uppercase tracking-wide text-muted-foreground">
              Hang on rigging
            </Label>
            <Select value={hangRigUuid} onValueChange={setHangRigUuid}>
              <SelectTrigger size="sm" className="w-full" aria-label="Rigging to hang on">
                <SelectValue placeholder="Choose a truss…" />
              </SelectTrigger>
              <SelectContent>
                {riggings.map((r) => (
                  <SelectItem key={r.uuid} value={r.uuid}>
                    {r.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button
              size="sm"
              variant="outline"
              className="w-full"
              disabled={!hangRigUuid || hangTargets.length === 0}
              onClick={hangOnRig}
            >
              Space evenly along truss
            </Button>
            {/* This is the one action that throws away existing positions, so say
                so before the user runs it rather than after. */}
            <p className="text-[11px] text-muted-foreground">
              Replaces current positions for all {hangTargets.length} fixture
              {hangTargets.length === 1 ? '' : 's'}.
            </p>
          </section>
        )}

        <section className="space-y-2">
          <Label className="text-xs uppercase tracking-wide text-muted-foreground">
            {projection.vAxisLabel}
          </Label>
          <div className="flex gap-1">
            <input
              type="number"
              step="0.25"
              value={depthValue}
              onChange={(e) => setDepthValue(e.target.value)}
              placeholder="metres"
              className="h-8 w-full rounded-md border bg-transparent px-2 text-sm"
              aria-label={`Set ${projection.vAxisLabel}`}
            />
            <Button size="sm" variant="outline" disabled={depthValue === ''} onClick={setDepth}>
              <Ruler className="size-3.5" />
            </Button>
          </div>
        </section>

        {/* Plan and front only. Both put h on ±X, so h = 0 really is the stage
            centre line. The side elevation puts h on +Y, where h = 0 is the
            downstage/FOH edge — "mirroring about the centre line" there would
            reflect every fixture to a negative Y, i.e. out into the auditorium. */}
        {canMirror && (
          <section className="space-y-1">
            <Button size="sm" variant="outline" className="w-full" onClick={mirror}>
              <FlipHorizontal className="size-3.5 mr-1" />
              Mirror about centre line
            </Button>
          </section>
        )}
      </div>

      <div className="border-t p-4">
        {/* Unplace, not delete: a patch is real DMX with channels, groups and cue
            references. Clearing its position sends it back to the tray, losing
            nothing. */}
        <Button size="sm" variant="outline" className="w-full" onClick={unplace}>
          <Trash2 className="size-3.5 mr-1" />
          Remove from stage
        </Button>
      </div>
    </aside>
  )
}

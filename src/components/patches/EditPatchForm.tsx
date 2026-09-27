import { forwardRef, useImperativeHandle, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { SheetHeader, SheetBody, SheetFooter } from '@/components/ui/sheet'
import { Trash2, X } from 'lucide-react'
import { useUpdatePatchMutation, useDeletePatchMutation, usePatchGroupListQuery } from '@/store/patches'
import { useFixtureTypeListQuery } from '@/store/fixtures'
import { KindOverrideField } from './KindOverrideField'
import { GroupComboInput } from './GroupComboInput'
import { PatchPlacementFields, type PatchPlacementValue } from './PatchPlacementFields'
import { BeamAngleField } from './BeamAngleField'
import { GelPickerField } from './GelPickerField'
import type { FixturePatch } from '@/api/patchApi'
import { ignoreReportedError } from '@/store/errorToastMiddleware'

export interface EditPatchFormHandle {
  setPlacement: (next: PatchPlacementValue) => void
}

interface EditPatchFormProps {
  patch: FixturePatch
  projectId: number
  existingPatches: FixturePatch[]
  onClose: () => void
  /**
   * Focus the name field on mount.
   *
   * True for the sheet, where the user deliberately opened the form to edit it.
   * **False for the docked stage panel**: there the form appears because the user
   * clicked an object on the canvas, and moving focus into a text field silently
   * disables every keyboard shortcut — arrow-key nudge, Delete, ⌘D — since they all
   * (correctly) stand down while the user is typing.
   */
  autoFocusName?: boolean
}

export const EditPatchForm = forwardRef<EditPatchFormHandle, EditPatchFormProps>(function EditPatchForm(
  { patch, projectId, existingPatches, onClose, autoFocusName = true },
  ref,
) {
  // Callers (sheet wrapper + docked panel) re-key on patch.id so this form
  // mounts fresh for each patch — seed once from props, no resync effect.
  const [displayName, setDisplayName] = useState(patch.displayName)
  const [key, setKey] = useState(patch.key)
  const [startChannel, setStartChannel] = useState(patch.startChannel)

  const [placement, setPlacement] = useState<PatchPlacementValue>({
    riggingUuid: patch.riggingUuid,
    stageX: patch.stageX,
    stageY: patch.stageY,
    stageZ: patch.stageZ,
    baseYawDeg: patch.baseYawDeg,
    basePitchDeg: patch.basePitchDeg,
  })
  const [beamAngleDeg, setBeamAngleDeg] = useState<number | null>(patch.beamAngleDeg)
  const [gelCode, setGelCode] = useState<string | null>(patch.gelCode)
  const [kindOverride, setKindOverride] = useState<string | null>(patch.kindOverride)
  const [stageHidden, setStageHidden] = useState(patch.stageHidden)
  const [infrastructure, setInfrastructure] = useState(patch.infrastructure ?? false)

  const [updatePatch, { isLoading: isUpdating }] = useUpdatePatchMutation()
  const [deletePatch, { isLoading: isDeleting }] = useDeletePatchMutation()
  const { data: patchGroups } = usePatchGroupListQuery(projectId)
  const { data: fixtureTypes } = useFixtureTypeListQuery()

  useImperativeHandle(ref, () => ({
    setPlacement: (next: PatchPlacementValue) => setPlacement(next),
  }), [])

  const channelCount = patch.channelCount ?? 1
  const lastChannel = startChannel + channelCount - 1
  const channelOverflow = lastChannel > 512

  const keyConflict = existingPatches.some(p => p.key === key && p.id !== patch.id)

  const fixtureType = fixtureTypes?.find((t) => t.typeKey === patch.fixtureTypeKey)
  const acceptsBeamAngle = fixtureType?.acceptsBeamAngle ?? false
  const acceptsGel = fixtureType?.acceptsGel ?? false
  // Only expose the override picker for fixture types whose declared kind is
  // GENERIC — those are the ones (generic dimmers, UV) that ship without a
  // shape hint. Other types already render distinctly per kind.
  const allowsKindOverride = (fixtureType?.kind ?? 'GENERIC') === 'GENERIC'
  const beamGelTitle =
    acceptsBeamAngle && acceptsGel ? 'Beam & Gel' : acceptsBeamAngle ? 'Beam' : 'Gel'

  const isValid = displayName.trim().length > 0 && key.trim().length > 0 && !channelOverflow && !keyConflict && startChannel >= 1
  const hasChanges =
    displayName !== patch.displayName ||
    key !== patch.key ||
    startChannel !== patch.startChannel ||
    placement.riggingUuid !== patch.riggingUuid ||
    placement.stageX !== patch.stageX ||
    placement.stageY !== patch.stageY ||
    placement.stageZ !== patch.stageZ ||
    placement.baseYawDeg !== patch.baseYawDeg ||
    placement.basePitchDeg !== patch.basePitchDeg ||
    beamAngleDeg !== patch.beamAngleDeg ||
    gelCode !== patch.gelCode ||
    kindOverride !== patch.kindOverride ||
    stageHidden !== patch.stageHidden ||
    infrastructure !== (patch.infrastructure ?? false)

  const handleSave = async () => {
    const body: Record<string, unknown> = {}
    if (displayName !== patch.displayName) body.displayName = displayName
    if (key !== patch.key) body.key = key
    if (startChannel !== patch.startChannel) body.startChannel = startChannel
    if (placement.riggingUuid !== patch.riggingUuid) body.riggingUuid = placement.riggingUuid
    if (placement.stageX !== patch.stageX) body.stageX = placement.stageX
    if (placement.stageY !== patch.stageY) body.stageY = placement.stageY
    if (placement.stageZ !== patch.stageZ) body.stageZ = placement.stageZ
    if (placement.baseYawDeg !== patch.baseYawDeg) body.baseYawDeg = placement.baseYawDeg
    if (placement.basePitchDeg !== patch.basePitchDeg) body.basePitchDeg = placement.basePitchDeg
    if (beamAngleDeg !== patch.beamAngleDeg) body.beamAngleDeg = beamAngleDeg
    if (gelCode !== patch.gelCode) body.gelCode = gelCode
    if (kindOverride !== patch.kindOverride) body.kindOverride = kindOverride
    if (stageHidden !== patch.stageHidden) body.stageHidden = stageHidden
    if (infrastructure !== (patch.infrastructure ?? false)) body.infrastructure = infrastructure
    // Errors are reported by errorToastMiddleware; don't close over a save that failed, or the
    // operator loses their edits with no indication the form still holds unsaved changes.
    try {
      await updatePatch({ projectId, patchId: patch.id, ...body }).unwrap()
    } catch {
      return
    }
    onClose()
  }

  const handleAddToGroup = async (name: string) => {
    if (!name) return
    await updatePatch({ projectId, patchId: patch.id, addToGroup: name })
      .unwrap()
      .catch(ignoreReportedError)
  }

  const handleRemoveFromGroup = async (groupId: number) => {
    await updatePatch({ projectId, patchId: patch.id, removeFromGroupId: groupId })
      .unwrap()
      .catch(ignoreReportedError)
  }

  const handleDelete = async () => {
    try {
      await deletePatch({ projectId, patchId: patch.id }).unwrap()
    } catch {
      return
    }
    onClose()
  }

  const typeLabel = [patch.manufacturer, patch.model, patch.modeName ? `(${patch.modeName})` : null]
    .filter(Boolean).join(' ')

  const currentGroupNames = new Set(patch.groups.map(g => g.name))
  const availableGroups = (patchGroups ?? []).filter(g => !currentGroupNames.has(g.name))

  return (
    <div className="flex flex-1 min-h-0 flex-col">
      <SheetHeader>
        <h2 className="text-foreground font-semibold">Edit Fixture</h2>
        {typeLabel && <p className="text-xs text-muted-foreground">{typeLabel} &middot; {channelCount}ch</p>}
      </SheetHeader>

      <SheetBody>
        <div className="space-y-1.5">
          <Label htmlFor="edit-name">Display Name</Label>
          <Input
            id="edit-name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            autoFocus={autoFocusName}
          />
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="edit-key">Key</Label>
          <Input
            id="edit-key"
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
          {keyConflict && (
            <p className="text-xs text-destructive">Key already exists</p>
          )}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="edit-start">Start Channel</Label>
          <Input
            id="edit-start"
            type="number"
            min={1}
            max={512}
            value={startChannel}
            onChange={(e) => setStartChannel(Math.max(1, Number(e.target.value) || 1))}
            onFocus={(e) => e.target.select()}
          />
          <p className="text-xs text-muted-foreground">
            Channels {startChannel}-{Math.min(lastChannel, 512)} on universe {patch.universe}
          </p>
          {channelOverflow && (
            <p className="text-xs text-destructive">
              Extends to channel {lastChannel} — max start: {512 - channelCount + 1}
            </p>
          )}
        </div>

        <PatchPlacementFields
          projectId={projectId}
          value={placement}
          onChange={setPlacement}
        />

        {(acceptsBeamAngle || acceptsGel) && (
          <div className="space-y-2.5 rounded-md border border-border p-3">
            <p className="text-xs font-medium text-muted-foreground">{beamGelTitle}</p>
            {acceptsBeamAngle && (
              <BeamAngleField
                id="edit-beam"
                value={beamAngleDeg}
                onChange={setBeamAngleDeg}
              />
            )}
            {acceptsGel && (
              <GelPickerField
                id="edit-gel"
                value={gelCode}
                onChange={setGelCode}
              />
            )}
          </div>
        )}

        {allowsKindOverride && (
          <KindOverrideField
            id="edit-kind-override"
            value={kindOverride}
            onChange={setKindOverride}
          />
        )}

        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <input
              id="edit-stage-hidden"
              type="checkbox"
              checked={stageHidden}
              onChange={(e) => setStageHidden(e.target.checked)}
            />
            <Label htmlFor="edit-stage-hidden">Hide from Stage view</Label>
          </div>
          <p className="text-xs text-muted-foreground">
            For fixtures that aren&apos;t stage objects — a dimmer driving hard power. Still
            patched, still outputs, still runs in cues and FX.
          </p>
        </div>

        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <input
              id="edit-infrastructure"
              type="checkbox"
              checked={infrastructure}
              onChange={(e) => setInfrastructure(e.target.checked)}
            />
            <Label htmlFor="edit-infrastructure">Infrastructure</Label>
          </div>
          <p className="text-xs text-muted-foreground">
            Not a lighting fixture — a relay or a dimmer switching a hazer&apos;s power. Hidden from
            every view but Patches and Channels (the Stage included), and never offered as a
            target. Anything that already uses it keeps working.
          </p>
        </div>

        <div className="space-y-1.5">
          <Label>Groups</Label>
          {patch.groups.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {patch.groups.map((g) => (
                <Badge key={g.id} variant="secondary" className="text-xs pl-2 pr-1 py-0.5 gap-1">
                  {g.name}
                  <button
                    type="button"
                    onClick={() => handleRemoveFromGroup(g.id)}
                    className="text-muted-foreground hover:text-foreground ml-0.5"
                  >
                    <X className="size-3" />
                  </button>
                </Badge>
              ))}
            </div>
          )}
          <GroupComboInput
            // Stays empty: `clearOnSelect` resets the field after each add.
            value=""
            onChange={(name) => {
              if (name) handleAddToGroup(name)
            }}
            groups={availableGroups}
            placeholder="Add to group..."
            clearOnSelect
          />
        </div>
      </SheetBody>

      <SheetFooter className="flex-row justify-between">
        <Button
          variant="destructive"
          size="sm"
          onClick={handleDelete}
          disabled={isDeleting}
        >
          <Trash2 className="size-3.5 mr-1.5" />
          {isDeleting ? 'Deleting...' : 'Delete'}
        </Button>
        <div className="flex gap-2">
          <Button variant="outline" onClick={onClose} disabled={isUpdating}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={!isValid || !hasChanges || isUpdating}>
            {isUpdating ? 'Saving...' : 'Save'}
          </Button>
        </div>
      </SheetFooter>
    </div>
  )
})

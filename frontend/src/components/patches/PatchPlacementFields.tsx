import { memo, useMemo } from 'react'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { FieldGroup, NumberField } from '@/components/ui/form-fields'
import { useRiggingListQuery } from '@/store/riggings'
import { bySortOrder } from '@/lib/utils'

const FREE = 'free'

export interface PatchPlacementValue {
  riggingUuid: string | null
  stageX: number | null
  stageY: number | null
  stageZ: number | null
  baseYawDeg: number | null
  basePitchDeg: number | null
  /** Roll about the body's local Z. Optional: the Stage view's drag writes yaw and pitch only. */
  baseRollDeg?: number | null
}

interface Props {
  projectId: number
  value: PatchPlacementValue
  onChange: (next: PatchPlacementValue) => void
  /**
   * Prefix for the fields' element ids, so one form can hold several sets — the fixture's own
   * placement and each of a paired dimmer's other lanterns — without two inputs sharing an id.
   */
  idPrefix?: string
}

function PatchPlacementFieldsImpl({ projectId, value, onChange, idPrefix = 'patch' }: Props) {
  const { data: riggings } = useRiggingListQuery(projectId)
  // Drag-driven onChange re-renders this on every frame; keep the sort off the hot path.
  const sortedRiggings = useMemo(() => (riggings ?? []).slice().sort(bySortOrder), [riggings])

  const mountValue = value.riggingUuid ?? FREE
  const isMounted = value.riggingUuid != null

  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor={`${idPrefix}-mount`}>Mounting</Label>
        <Select
          value={mountValue}
          onValueChange={(v) =>
            onChange({ ...value, riggingUuid: v === FREE ? null : v })
          }
        >
          <SelectTrigger id={`${idPrefix}-mount`} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={FREE}>Free</SelectItem>
            {sortedRiggings.map((r) => (
              <SelectItem key={r.uuid} value={r.uuid}>
                {r.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="space-y-1.5">
        <FieldGroup label="Position (m)">
          <NumberField
            id={`${idPrefix}-stage-x`}
            label="X"
            value={value.stageX}
            onChange={(v) => onChange({ ...value, stageX: v })}
          />
          <NumberField
            id={`${idPrefix}-stage-y`}
            label="Y"
            value={value.stageY}
            onChange={(v) => onChange({ ...value, stageY: v })}
          />
          <NumberField
            id={`${idPrefix}-stage-z`}
            label="Z"
            value={value.stageZ}
            onChange={(v) => onChange({ ...value, stageZ: v })}
          />
        </FieldGroup>
        <p className="text-xs text-muted-foreground">
          {isMounted
            ? 'Offset from rigging origin (X = along truss, Y = out from truss, Z = drop).'
            : 'World coordinates (X = stage right, Y = upstage, Z = height).'}
        </p>
      </div>

      <details className="group">
        <summary className="cursor-pointer text-sm text-muted-foreground hover:text-foreground select-none">
          Base orientation (advanced)
        </summary>
        <div className="pt-3">
          <FieldGroup label="Base orientation (°)">
            <NumberField
              id={`${idPrefix}-base-yaw`}
              label="Yaw"
              value={value.baseYawDeg}
              onChange={(v) => onChange({ ...value, baseYawDeg: v })}
            />
            <NumberField
              id={`${idPrefix}-base-pitch`}
              label="Pitch"
              value={value.basePitchDeg}
              onChange={(v) => onChange({ ...value, basePitchDeg: v })}
            />
            <NumberField
              id={`${idPrefix}-base-roll`}
              label="Roll"
              value={value.baseRollDeg ?? null}
              onChange={(v) => onChange({ ...value, baseRollDeg: v })}
            />
          </FieldGroup>
          <p className="pt-1.5 text-xs text-muted-foreground">
            Roll tips the body sideways, face on — 90 stands a strip on end.
          </p>
        </div>
      </details>
    </div>
  )
}

export const PatchPlacementFields = memo(PatchPlacementFieldsImpl)

import { useMemo } from 'react'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { WindowViewPickerProps } from '@/lib/windowViews'
import { STAGE_CAMERAS, STAGE_CAMERA_LABELS } from '@/lib/stageViewpoint'
import { useStageViewpointListQuery } from '@/store/stageViewpoints'
import { useStageElementListQuery } from '@/store/stageElements'
import { resolveSavedViewpoint, savedViewNote } from './savedViewpoints'

/**
 * A Stage row's *Viewpoint* on the Screens sheet (`Screens.dc.html` §1, stage-view plan session 2):
 * the five cameras, then the target project's saved views, then its seats. The value is written as
 * the `viewpoint` view option — a camera's name or a saved view's uuid — which the target applies.
 *
 * The Stage view's control, handed to the sheet by the app shell and loaded when a Stage row first
 * draws it, so neither the sheet nor the shell pulls the scene queries until one is on screen.
 * A saved view that cannot be landed (its seat is gone) is listed disabled, as the header's picker
 * lists it.
 */
export default function StageViewpointRowPicker({ value, onSet, label, rowName, projectId }: WindowViewPickerProps) {
  const { data: saved } = useStageViewpointListQuery(projectId ?? 0, { skip: projectId == null })
  const { data: elements } = useStageElementListQuery(projectId ?? 0, { skip: projectId == null })
  const views = useMemo(() => (saved ?? []).filter((row) => row.kind !== 'SEAT'), [saved])
  const seats = useMemo(() => (saved ?? []).filter((row) => row.kind === 'SEAT'), [saved])
  const known = STAGE_CAMERAS.some((c) => c === value) || (saved ?? []).some((row) => row.uuid === value)

  const item = (row: NonNullable<typeof saved>[number]) => {
    const ok = resolveSavedViewpoint(row, elements ?? []) != null
    return (
      <SelectItem key={row.uuid} value={row.uuid} disabled={!ok}>
        {row.name}
        <span className="ml-2 text-muted-foreground">{ok ? savedViewNote(row) : 'seat gone'}</span>
      </SelectItem>
    )
  }

  return (
    <Select value={known ? value : ''} onValueChange={onSet}>
      <SelectTrigger className="h-7 min-w-[9rem]" aria-label={`${label} on ${rowName}`}>
        <SelectValue placeholder={value === '' ? '—' : 'Saved view'} />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          <SelectLabel>Cameras</SelectLabel>
          {STAGE_CAMERAS.map((c) => (
            <SelectItem key={c} value={c}>
              {STAGE_CAMERA_LABELS[c]}
            </SelectItem>
          ))}
        </SelectGroup>
        {views.length > 0 && (
          <>
            <SelectSeparator />
            <SelectGroup>
              <SelectLabel>Saved views</SelectLabel>
              {views.map(item)}
            </SelectGroup>
          </>
        )}
        {seats.length > 0 && (
          <>
            <SelectSeparator />
            <SelectGroup>
              <SelectLabel>Seats</SelectLabel>
              {seats.map(item)}
            </SelectGroup>
          </>
        )}
      </SelectContent>
    </Select>
  )
}

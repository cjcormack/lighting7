import { Armchair, Bookmark, Camera, ChevronDown, Crosshair, Eye, Plus, Rotate3d, Square } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { StageViewpointDto } from '@/api/stageViewpointApi'
import {
  STAGE_CAMERA_LABELS,
  isOrthoCamera,
  type StageCamera,
  type StageViewpoint,
} from '@/lib/stageViewpoint'
import { savedViewNote } from './savedViewpoints'

/** The built-ins the picker lists: Eye is a camera, but a place to stand is a saved view. */
const BUILT_INS: readonly { id: StageCamera; note: string; shortcut?: string }[] = [
  { id: 'orbit', note: 'turntable', shortcut: 'O' },
  { id: 'plan', note: 'section' },
  { id: 'front', note: 'section' },
  { id: 'side', note: 'section' },
]

function CameraIcon({ camera, className }: { camera: StageCamera; className?: string }) {
  if (camera === 'orbit') return <Rotate3d className={className} />
  if (camera === 'eye') return <Eye className={className} />
  if (isOrthoCamera(camera)) return <Square className={className} />
  return <Camera className={className} />
}

function SavedIcon({ row, className }: { row: StageViewpointDto; className?: string }) {
  if (row.kind === 'SEAT') return <Armchair className={className} />
  if (row.kind === 'ORBIT') return <Rotate3d className={className} />
  return <Bookmark className={className} />
}

const SECTION_LABEL = 'text-[10px] uppercase tracking-wide text-muted-foreground'

/**
 * The viewpoint picker (`Stage.dc.html` §4): the current viewpoint by name, and a menu of the
 * built-ins, the project's **saved views** and **seats** (session 2's `stage_viewpoints` rows),
 * *Frame the selection* and *Save this view…*. The camera segment beside it still switches the
 * camera; this is where a place to look from is chosen.
 *
 * A saved view the window cannot land — a seat whose seating has gone, or no longer has that seat —
 * is listed disabled rather than hidden, so the operator can see what went. *Sit in a seat…*
 * (picking one on the seating) is session 3's.
 */
export function StageViewpointPicker({
  viewpoint,
  camera,
  saved,
  landable,
  onPick,
  onFrame,
  canFrame,
  onSave,
  canSave,
}: {
  viewpoint: StageViewpoint
  /** The camera the viewpoint draws through — its own, or a saved view's. */
  camera: StageCamera
  saved: readonly StageViewpointDto[]
  /** Whether a saved row can be landed now. */
  landable: (row: StageViewpointDto) => boolean
  onPick: (viewpoint: StageViewpoint) => void
  onFrame: () => void
  /** Whether there is anything to frame: the Stage's own selection or the desk's. */
  canFrame: boolean
  onSave: () => void
  /** Whether the current camera can be saved: an orbit or an eye, never a built-in section. */
  canSave: boolean
}) {
  const current = saved.find((row) => row.uuid === viewpoint)
  const label = current?.name ?? (viewpoint === camera ? STAGE_CAMERA_LABELS[camera] : 'Saved view')
  const views = saved.filter((row) => row.kind !== 'SEAT')
  const seats = saved.filter((row) => row.kind === 'SEAT')

  const savedItem = (row: StageViewpointDto) => {
    const ok = landable(row)
    return (
      <DropdownMenuItem
        key={row.uuid}
        onSelect={() => onPick(row.uuid as StageViewpoint)}
        disabled={!ok}
        aria-current={viewpoint === row.uuid ? 'true' : undefined}
        title={ok ? undefined : 'Its seat is gone: the seating was moved, resized or deleted'}
      >
        <SavedIcon row={row} className="size-3.5" />
        <span className={`truncate ${viewpoint === row.uuid ? 'font-semibold' : ''}`}>{row.name}</span>
        <DropdownMenuShortcut>{ok ? savedViewNote(row) : 'seat gone'}</DropdownMenuShortcut>
      </DropdownMenuItem>
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" variant="outline" aria-label={`Viewpoint: ${label}`} className="max-w-[14rem]">
          {current ? (
            <SavedIcon row={current} className="size-3.5 sm:mr-1" />
          ) : (
            <CameraIcon camera={camera} className="size-3.5 sm:mr-1" />
          )}
          <span className="hidden truncate sm:inline">{label}</span>
          <ChevronDown className="ml-1 size-3.5 opacity-60" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64">
        <DropdownMenuLabel className={SECTION_LABEL}>Viewpoints</DropdownMenuLabel>
        {BUILT_INS.map(({ id, note, shortcut }) => (
          <DropdownMenuItem key={id} onSelect={() => onPick(id)} aria-current={viewpoint === id ? 'true' : undefined}>
            <CameraIcon camera={id} className="size-3.5" />
            <span className={viewpoint === id ? 'font-semibold' : undefined}>{STAGE_CAMERA_LABELS[id]}</span>
            <DropdownMenuShortcut>{shortcut ?? note}</DropdownMenuShortcut>
          </DropdownMenuItem>
        ))}
        {views.length > 0 && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuLabel className={SECTION_LABEL}>Saved views</DropdownMenuLabel>
            {views.map(savedItem)}
          </>
        )}
        {seats.length > 0 && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuLabel className={SECTION_LABEL}>Seats</DropdownMenuLabel>
            {seats.map(savedItem)}
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onFrame} disabled={!canFrame}>
          <Crosshair className="size-3.5" />
          Frame the selection
          <DropdownMenuShortcut>F</DropdownMenuShortcut>
        </DropdownMenuItem>
        <DropdownMenuItem
          onSelect={onSave}
          disabled={!canSave}
          title={canSave ? undefined : 'Plan, Front and Side are built in: save from Orbit or Eye'}
        >
          <Plus className="size-3.5" />
          Save this view…
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

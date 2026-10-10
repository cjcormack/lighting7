import { Armchair, Bookmark, Camera, ChevronDown, Crosshair, Eye, Plus, Rotate3d, Square, TriangleAlert } from 'lucide-react'
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
  isSeatViewpointRef,
  type StageCamera,
  type StageViewpoint,
} from '@/lib/stageViewpoint'
import { savedViewNote, seatViewpointName } from './savedViewpoints'
import type { SeatScrimHint } from './seatScrimHint'

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

/** A seat's scrim warning (scrim plan D13), on a line of its own under the seat's name. */
function SeatHintLine({ hint }: { hint: SeatScrimHint }) {
  const more = hint.others.length
  return (
    <span className="flex items-start gap-1 whitespace-normal text-[11px] leading-tight text-amber-600 dark:text-amber-400" data-seat-scrim-hint>
      <TriangleAlert className="mt-px size-3 shrink-0" aria-hidden />
      <span>
        {hint.text}
        {more > 0 && ` · ${more} more`}
      </span>
    </span>
  )
}

/**
 * The viewpoint picker (`Stage.dc.html` §4): the current viewpoint by name, and a menu of the
 * built-ins, the project's **saved views** and **seats** (session 2's `stage_viewpoints` rows),
 * *Frame the selection* and *Save this view…*. The camera segment beside it still switches the
 * camera; this is where a place to look from is chosen.
 *
 * A saved view the window cannot land — a seat whose seating has gone, or no longer has that seat —
 * is listed disabled rather than hidden, so the operator can see what went. ***Sit in a seat…***
 * (session 3, S) arms a pick on the seating mesh: the seat clicked is the viewpoint, unsaved, named
 * on the trigger as *Row F, seat 6* until *Save this view…* makes it a row, and is listed under
 * *Seats* as *unsaved* while the window sits in it.
 *
 * A seat that sees a shown gauze as near-solid says so under its name (scrim plan D13,
 * `seatScrimHint.ts`), its hover naming every scrim that crosses the line.
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
  onSit,
  canSit = false,
  sitting = false,
  seatHints,
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
  /** *Sit in a seat…*: arm the pick on the seating. */
  onSit?: () => void
  /** Whether there are seats to sit in. */
  canSit?: boolean
  /** Whether the pick is armed now. */
  sitting?: boolean
  /** Each seat's scrim warning, by its saved row's uuid or a picked seat's `seat:` key; absent, none. */
  seatHints?: ReadonlyMap<string, SeatScrimHint>
}) {
  const current = saved.find((row) => row.uuid === viewpoint)
  const pickedSeat = isSeatViewpointRef(viewpoint) ? viewpoint : null
  const label =
    current?.name ??
    (pickedSeat != null
      ? seatViewpointName(pickedSeat)
      : viewpoint === camera
        ? STAGE_CAMERA_LABELS[camera]
        : 'Saved view')
  const views = saved.filter((row) => row.kind !== 'SEAT')
  const seats = saved.filter((row) => row.kind === 'SEAT')

  const savedItem = (row: StageViewpointDto) => {
    const ok = landable(row)
    const hint = ok ? seatHints?.get(row.uuid) : undefined
    return (
      <DropdownMenuItem
        key={row.uuid}
        onSelect={() => onPick(row.uuid as StageViewpoint)}
        disabled={!ok}
        aria-current={viewpoint === row.uuid ? 'true' : undefined}
        title={ok ? hint?.detail : 'Its seat is gone: the seating was moved, resized or deleted'}
        className={hint != null ? 'items-start' : undefined}
      >
        <SavedIcon row={row} className={hint != null ? 'mt-0.5 size-3.5' : 'size-3.5'} />
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className={`truncate ${viewpoint === row.uuid ? 'font-semibold' : ''}`}>{row.name}</span>
          {hint != null && <SeatHintLine hint={hint} />}
        </span>
        <DropdownMenuShortcut>{ok ? savedViewNote(row) : 'seat gone'}</DropdownMenuShortcut>
      </DropdownMenuItem>
    )
  }
  const pickedHint = pickedSeat != null ? seatHints?.get(pickedSeat) : undefined

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" variant="outline" aria-label={`Viewpoint: ${label}`} className="max-w-[14rem]">
          {current ? (
            <SavedIcon row={current} className="size-3.5 sm:mr-1" />
          ) : pickedSeat != null ? (
            <Armchair className="size-3.5 sm:mr-1" />
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
        {(seats.length > 0 || pickedSeat != null) && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuLabel className={SECTION_LABEL}>Seats</DropdownMenuLabel>
            {pickedSeat != null && (
              <DropdownMenuItem
                onSelect={() => onPick(pickedSeat)}
                aria-current="true"
                title={pickedHint?.detail}
                className={pickedHint != null ? 'items-start' : undefined}
              >
                <Armchair className={pickedHint != null ? 'mt-0.5 size-3.5' : 'size-3.5'} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="truncate font-semibold">{seatViewpointName(pickedSeat)}</span>
                  {pickedHint != null && <SeatHintLine hint={pickedHint} />}
                </span>
                <DropdownMenuShortcut>unsaved</DropdownMenuShortcut>
              </DropdownMenuItem>
            )}
            {seats.map(savedItem)}
          </>
        )}
        <DropdownMenuSeparator />
        {onSit && (
          <DropdownMenuItem
            onSelect={onSit}
            disabled={!canSit}
            title={canSit ? 'Click a seat on the seating to sit in it' : 'There is no seating to sit in here'}
          >
            <Armchair className="size-3.5" />
            <span className={sitting ? 'font-semibold' : undefined}>Sit in a seat…</span>
            <DropdownMenuShortcut>S</DropdownMenuShortcut>
          </DropdownMenuItem>
        )}
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

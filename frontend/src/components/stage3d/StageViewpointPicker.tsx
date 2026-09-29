import { Camera, ChevronDown, Crosshair, Eye, Rotate3d, Square } from 'lucide-react'
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
import {
  STAGE_VIEWPOINT_LABELS,
  isOrthoViewpoint,
  type StageViewpoint,
} from '@/lib/stageViewpoint'

/** The built-ins the picker lists (plan session 1): Eye is a camera, but a point to stand at is session 2's. */
const BUILT_INS: readonly { id: StageViewpoint; note: string; shortcut?: string }[] = [
  { id: 'orbit', note: 'turntable', shortcut: 'O' },
  { id: 'plan', note: 'section' },
  { id: 'front', note: 'section' },
  { id: 'side', note: 'section' },
]

function ViewpointIcon({ viewpoint, className }: { viewpoint: StageViewpoint; className?: string }) {
  if (viewpoint === 'orbit') return <Rotate3d className={className} />
  if (viewpoint === 'eye') return <Eye className={className} />
  if (isOrthoViewpoint(viewpoint)) return <Square className={className} />
  return <Camera className={className} />
}

/**
 * The viewpoint picker (`Stage.dc.html` §4): the current viewpoint by name, and a menu of the
 * built-ins and *Frame the selection*. It replaces nothing — the camera segment beside it still
 * switches the camera — and it is where session 2's saved views, seats and *Save this view…* go.
 */
export function StageViewpointPicker({
  viewpoint,
  onPick,
  onFrame,
  canFrame,
}: {
  viewpoint: StageViewpoint
  onPick: (viewpoint: StageViewpoint) => void
  onFrame: () => void
  /** Whether there is anything to frame: the Stage's own selection or the desk's. */
  canFrame: boolean
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" variant="outline" aria-label={`Viewpoint: ${STAGE_VIEWPOINT_LABELS[viewpoint]}`}>
          <ViewpointIcon viewpoint={viewpoint} className="size-3.5 sm:mr-1" />
          <span className="hidden sm:inline">{STAGE_VIEWPOINT_LABELS[viewpoint]}</span>
          <ChevronDown className="ml-1 size-3.5 opacity-60" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-60">
        <DropdownMenuLabel className="text-[10px] uppercase tracking-wide text-muted-foreground">
          Viewpoints
        </DropdownMenuLabel>
        {BUILT_INS.map(({ id, note, shortcut }) => (
          <DropdownMenuItem key={id} onSelect={() => onPick(id)} aria-current={viewpoint === id ? 'true' : undefined}>
            <ViewpointIcon viewpoint={id} className="size-3.5" />
            <span className={viewpoint === id ? 'font-semibold' : undefined}>{STAGE_VIEWPOINT_LABELS[id]}</span>
            <DropdownMenuShortcut>{shortcut ?? note}</DropdownMenuShortcut>
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={onFrame} disabled={!canFrame}>
          <Crosshair className="size-3.5" />
          Frame the selection
          <DropdownMenuShortcut>F</DropdownMenuShortcut>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

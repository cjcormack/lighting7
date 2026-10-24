import { useEffect, useMemo, useRef, useState } from 'react'
import { Crosshair, Loader2, Map as MapIcon, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { FieldGroup, NumberField } from '@/components/ui/form-fields'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { annotatesDegrees } from '@/lib/axisDegrees'
import { bySortOrder } from '@/lib/utils'
import { findPanAttribute, findTiltAttribute, type Fixture } from '@/store/fixtures'
import { useAimMutation, type AimResponse, type SpreadSkip } from '@/store/programmerOps'
import { useStageRegionListQuery } from '@/store/stageRegions'
import type { StageRegionDto } from '@/api/stageRegionApi'
import {
  HEAD_HEIGHT_M,
  planAimPoint,
  round3,
  skipsByReason,
  type AimHeight,
  type AimPoint,
  type PlanAimClicks,
  type PlanPoint,
} from './planAim'

export { HEAD_HEIGHT_M }

/**
 * Whether the desk can aim this fixture at a point: both axes annotate a degree range. The desk
 * decides for itself (and skips by name), so this only keeps the control off heads it would
 * refuse — a par, a mover whose type declares no range.
 */
export function isAimable(fixture: Fixture | undefined): boolean {
  if (!fixture) return false
  return annotatesDegrees(findPanAttribute(fixture.attributes)) && annotatesDegrees(findTiltAttribute(fixture.attributes))
}

/**
 * A performer standing in [region]: its centre, at head height above its top surface (`centerZ`
 * is the platform's top, 0 on the deck). Null for a region that is not on the stage.
 */
export function regionAimPoint(region: StageRegionDto): { x: number; y: number; z: number } | null {
  if (region.centerX == null || region.centerY == null) return null
  return {
    x: region.centerX,
    y: region.centerY,
    z: round3((region.centerZ ?? 0) + HEAD_HEIGHT_M),
  }
}

/** What the controls hold — handed on when arming, so the docked panel starts where this left off. */
export interface AimDraft {
  x: number | null
  y: number | null
  z: number | null
  region: string
  height: AimHeight
}

/**
 * The Stage view's door onto the floor plan (venue-visit plan S13): while armed, a click on the Plan
 * is a point these controls aim at.
 */
export interface PlanAimDoor {
  /** The Plan is taking clicks for these controls. */
  armed: boolean
  /** Arm it with what these controls hold; the host puts this window on the Plan. */
  onArm: (draft: AimDraft) => void
  onDisarm?: () => void
  /** The Plan's clicks, while armed. */
  clicks?: PlanAimClicks
  /** The floor off every region — the deck on the stage, else the house floor. Absent: the deck. */
  floorAt?: (point: PlanPoint) => number
  /** Where an aim was sent, for the Plan's marker. */
  onAimed?: (point: AimPoint) => void
}

interface StageAimControlsProps {
  projectId: number
  /** The fixtures to aim — patch keys, which are fixture keys. */
  fixtureKeys: readonly string[]
  /** The floor plan's door, where the host has a Plan to click. */
  plan?: PlanAimDoor
  /** Where to start: what another host held when it armed the Plan. */
  seed?: AimDraft | null
}

/**
 * "Aim at point": a stage coordinate (metres — x audience-right, y upstage, z up, the frame the
 * Stage view and the patch form use) in, `POST /programmer/aim` out. The desk works out each
 * head's pan and tilt from its placement, mount and travel, and writes them into the programmer
 * as Local entries, so the view follows through the ordinary programmer feed and Record keeps
 * them. A region fills the point with its centre at head height.
 *
 * With a [plan] door armed, a click on the Plan fills X and Y and aims at once, its height head
 * height above the region under it (or the deck), or the typed Z. The fields stay the numeric entry
 * for a point off the drawing.
 */
export function StageAimControls({ projectId, fixtureKeys, plan, seed }: StageAimControlsProps) {
  const [x, setX] = useState<number | null>(seed?.x ?? null)
  const [y, setY] = useState<number | null>(seed?.y ?? null)
  const [z, setZ] = useState<number | null>(seed != null ? seed.z : HEAD_HEIGHT_M)
  const [region, setRegion] = useState<string>(seed?.region ?? '')
  const [height, setHeight] = useState<AimHeight>(seed?.height ?? 'surface')
  // What the last Plan click stood on: a region's name, or null for the deck or a typed Z.
  const [standing, setStanding] = useState<{ on: string | null } | null>(null)
  const [result, setResult] = useState<AimResponse | null>(null)
  const [aim, { isLoading }] = useAimMutation()
  const { fixtureByKey } = useFixtureLookup()
  const { data: regions } = useStageRegionListQuery(projectId)

  const placedRegions = useMemo(
    () => (regions ?? []).filter((r) => r.centerX != null && r.centerY != null).slice().sort(bySortOrder),
    [regions],
  )

  const ready = x != null && y != null && z != null && fixtureKeys.length > 0
  const nameOf = (key: string) => fixtureByKey.get(key)?.name ?? key

  const pickRegion = (uuid: string) => {
    setRegion(uuid)
    const r = placedRegions.find((candidate) => candidate.uuid === uuid)
    const point = r ? regionAimPoint(r) : null
    if (!point) return
    setX(point.x)
    setY(point.y)
    setZ(point.z)
    setStanding(null)
    setResult(null)
  }

  const edit = (set: (v: number | null) => void) => (v: number | null) => {
    set(v)
    setRegion('')
    setStanding(null)
    setResult(null)
  }

  // One aim in flight at a time, and only the newest waiting: Plan clicks come faster than the desk
  // answers, and two requests racing could land the heads, or the answer, on the older point.
  const target = useRef({ projectId, fixtureKeys })
  target.current = { projectId, fixtureKeys }
  const inFlight = useRef(false)
  const queued = useRef<AimPoint | null>(null)
  const send = async (point: AimPoint) => {
    if (fixtureKeys.length === 0) return
    plan?.onAimed?.(point)
    queued.current = point
    if (inFlight.current) return
    inFlight.current = true
    while (queued.current != null) {
      const next = queued.current
      queued.current = null
      const { projectId: id, fixtureKeys: keys } = target.current
      try {
        const answer = await aim({ projectId: id, targets: keys.map((key) => ({ type: 'fixture', key })), ...next }).unwrap()
        if (queued.current == null) setResult(answer)
      } catch {
        // Refusals are toasted by the error middleware under the endpoint's own id.
        if (queued.current == null) setResult(null)
      }
    }
    inFlight.current = false
  }

  const submit = () => {
    if (x == null || y == null || z == null) return
    void send({ x, y, z })
  }

  // A Plan click: the point, its height by the height rule, and the aim. Read through a ref so the
  // subscription is made once per arming, not once per keystroke in a field.
  const onPlanClick = (at: PlanPoint) => {
    const where = { x: round3(at.x), y: round3(at.y) }
    const { point, region: on } = planAimPoint(where, height, z, placedRegions, plan?.floorAt?.(where) ?? 0)
    setX(point.x)
    setY(point.y)
    setZ(point.z)
    setRegion('')
    setStanding(height === 'surface' || z == null ? { on: on?.name ?? null } : null)
    setResult(null)
    void send(point)
  }
  const onPlanClickRef = useRef(onPlanClick)
  onPlanClickRef.current = onPlanClick
  const clicks = plan?.armed ? plan.clicks : undefined
  useEffect(() => clicks?.subscribe((at) => onPlanClickRef.current(at)), [clicks])

  const written = result?.written ?? []
  const skipped = result?.skipped ?? []

  return (
    <section className="space-y-3" aria-label="Aim at point">
      <div className="flex items-center gap-2 text-sm font-medium">
        <Crosshair className="size-4 text-muted-foreground" />
        Aim at point
      </div>
      {plan != null && !plan.armed && (
        <Button size="sm" variant="outline" className="w-full" onClick={() => plan.onArm({ x, y, z, region, height })}>
          <MapIcon className="mr-1 size-3.5" />
          Aim on the plan
        </Button>
      )}
      {plan?.armed && (
        <div className="space-y-2 rounded-md border border-primary/40 bg-primary/5 p-2" role="group" aria-label="Aiming on the plan">
          <div className="flex items-center gap-2">
            <p className="min-w-0 flex-1 text-xs">Click the plan to aim · drag to pan · Esc to stop</p>
            <Button size="sm" variant="outline" className="h-7 shrink-0 text-xs" onClick={plan.onDisarm}>
              Done
            </Button>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-muted-foreground">Height</span>
            <ToggleGroup
              type="single"
              size="sm"
              value={height}
              onValueChange={(v) => {
                if (v === 'surface' || v === 'z') setHeight(v)
              }}
              aria-label="Height of a plan click"
            >
              <ToggleGroupItem value="surface" className="px-2 text-xs">
                Head height
              </ToggleGroupItem>
              <ToggleGroupItem value="z" className="px-2 text-xs">
                At Z
              </ToggleGroupItem>
            </ToggleGroup>
          </div>
          <p className="text-[10px] text-muted-foreground">
            {height === 'surface'
              ? `${HEAD_HEIGHT_M} m above the region under the click, or the floor.`
              : 'At the Z below, wherever the click lands.'}
          </p>
        </div>
      )}
      {placedRegions.length > 0 && (
        <Select value={region} onValueChange={pickRegion}>
          <SelectTrigger className="w-full" aria-label="Aim at a region">
            <SelectValue placeholder="A region, at head height…" />
          </SelectTrigger>
          <SelectContent>
            {placedRegions.map((r) => (
              <SelectItem key={r.uuid} value={r.uuid}>
                {r.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
      {/* A form, so Enter in a field aims. */}
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault()
          if (ready && !isLoading) submit()
        }}
      >
      <FieldGroup label="Point (m)">
        <NumberField id="aim-x" label="X" value={x} onChange={edit(setX)} />
        <NumberField id="aim-y" label="Y" value={y} onChange={edit(setY)} />
        <NumberField
          id="aim-z"
          label="Z"
          value={z}
          onChange={(v) => {
            edit(setZ)(v)
            // A typed height is the height a Plan click takes from now on.
            if (plan != null) setHeight('z')
          }}
        />
      </FieldGroup>
      <p className="text-xs text-muted-foreground">
        Stage coordinates: X audience-right, Y upstage, Z height above the deck.
      </p>
      <Button type="submit" size="sm" className="w-full" disabled={!ready || isLoading}>
        {isLoading ? <Loader2 className="mr-1 size-3.5 animate-spin" /> : <Crosshair className="mr-1 size-3.5" />}
        {fixtureKeys.length === 1 ? 'Aim' : `Aim ${fixtureKeys.length} fixtures`}
      </Button>
      </form>
      {(result || standing) && (
        <div className="space-y-1 text-xs" role="status">
          {standing && (
            <p className="text-muted-foreground">Head height on {standing.on ?? 'the floor'}</p>
          )}
          {written.length > 0 && (
            <p className="text-muted-foreground">
              {written.length === 1
                ? `Aimed ${nameOf(written[0].target.key)}: pan ${written[0].panDeg}°, tilt ${written[0].tiltDeg}°`
                : `Aimed ${written.length} fixtures`}
            </p>
          )}
          <AimSkips skipped={skipped} nameOf={nameOf} />
        </div>
      )}
    </section>
  )
}

/** The desk's skips, one line per reason: *Par 1, Par 2 — fixed head — no pan or tilt*. */
export function AimSkips({ skipped, nameOf }: { skipped: readonly SpreadSkip[]; nameOf: (key: string) => string }) {
  return (
    <>
      {skipsByReason(skipped, nameOf).map(({ reason, names }) => (
        <p key={reason} data-aim-skip className="text-amber-600 dark:text-amber-400">
          {names.join(', ')} — {reason}
        </p>
      ))}
    </>
  )
}

interface StageAimPanelProps {
  projectId: number
  fixtureKeys: readonly string[]
  /** The floor plan's door — see [StageAimControls]. */
  plan?: PlanAimDoor
  seed?: AimDraft | null
  onClose: () => void
}

/**
 * The docked aim panel for a multi-selection in view mode, and for any selection while aiming on the
 * plan — the single-fixture panel carries the same controls in its *Aim…* popover, which a click on
 * the canvas would close.
 */
export function StageAimPanel({ projectId, fixtureKeys, plan, seed, onClose }: StageAimPanelProps) {
  return (
    <aside className="relative flex w-full flex-col gap-4 border-l bg-background p-4 shadow-lg sm:w-[320px]">
      <button
        type="button"
        onClick={onClose}
        aria-label="Close aim panel"
        className="absolute right-4 top-4 z-10 rounded-xs opacity-70 transition-opacity hover:opacity-100"
      >
        <X className="size-4" />
      </button>
      <p className="pr-8 text-sm text-muted-foreground">
        {fixtureKeys.length} moving {fixtureKeys.length === 1 ? 'head' : 'heads'} selected
      </p>
      <StageAimControls projectId={projectId} fixtureKeys={fixtureKeys} plan={plan} seed={seed} />
    </aside>
  )
}

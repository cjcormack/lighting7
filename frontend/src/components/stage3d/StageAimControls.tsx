import { useMemo, useState } from 'react'
import { Crosshair, Loader2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { FieldGroup, NumberField } from '@/components/ui/form-fields'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { annotatesDegrees } from '@/lib/axisDegrees'
import { bySortOrder } from '@/lib/utils'
import { findPanProperty, findTiltProperty, type Fixture } from '@/store/fixtures'
import { useAimMutation, type AimResponse } from '@/store/programmerOps'
import { useStageRegionListQuery } from '@/store/stageRegions'
import type { StageRegionDto } from '@/api/stageRegionApi'

/** Where a performer's face is, above whatever they stand on — what "aim at a region" means. */
export const HEAD_HEIGHT_M = 1.7

/**
 * Whether the desk can aim this fixture at a point: both axes annotate a degree range. The desk
 * decides for itself (and skips by name), so this only keeps the control off heads it would
 * refuse — a par, a mover whose type declares no range.
 */
export function isAimable(fixture: Fixture | undefined): boolean {
  if (!fixture) return false
  return annotatesDegrees(findPanProperty(fixture.properties)) && annotatesDegrees(findTiltProperty(fixture.properties))
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
    z: Math.round(((region.centerZ ?? 0) + HEAD_HEIGHT_M) * 1000) / 1000,
  }
}

interface StageAimControlsProps {
  projectId: number
  /** The fixtures to aim — patch keys, which are fixture keys. */
  fixtureKeys: readonly string[]
}

/**
 * "Aim at point": a stage coordinate (metres — x audience-right, y upstage, z up, the frame the
 * Stage view and the patch form use) in, `POST /programmer/aim` out. The desk works out each
 * head's pan and tilt from its placement, mount and travel, and writes them into the programmer
 * as Local entries, so the view follows through the ordinary programmer feed and Record keeps
 * them. A region fills the point with its centre at head height.
 */
export function StageAimControls({ projectId, fixtureKeys }: StageAimControlsProps) {
  const [x, setX] = useState<number | null>(null)
  const [y, setY] = useState<number | null>(null)
  const [z, setZ] = useState<number | null>(HEAD_HEIGHT_M)
  const [region, setRegion] = useState<string>('')
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
    setResult(null)
  }

  const edit = (set: (v: number | null) => void) => (v: number | null) => {
    set(v)
    setRegion('')
    setResult(null)
  }

  const submit = async () => {
    if (!ready) return
    try {
      const answer = await aim({
        projectId,
        targets: fixtureKeys.map((key) => ({ type: 'fixture', key })),
        x,
        y,
        z,
      }).unwrap()
      setResult(answer)
    } catch {
      // Refusals are toasted by the error middleware under the endpoint's id.
      setResult(null)
    }
  }

  const written = result?.written ?? []
  const skipped = result?.skipped ?? []

  return (
    <section className="space-y-3" aria-label="Aim at point">
      <div className="flex items-center gap-2 text-sm font-medium">
        <Crosshair className="size-4 text-muted-foreground" />
        Aim at point
      </div>
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
      <FieldGroup label="Point (m)">
        <NumberField id="aim-x" label="X" value={x} onChange={edit(setX)} />
        <NumberField id="aim-y" label="Y" value={y} onChange={edit(setY)} />
        <NumberField id="aim-z" label="Z" value={z} onChange={edit(setZ)} />
      </FieldGroup>
      <p className="text-xs text-muted-foreground">
        Stage coordinates: X audience-right, Y upstage, Z height above the deck.
      </p>
      <Button size="sm" className="w-full" disabled={!ready || isLoading} onClick={submit}>
        {isLoading ? <Loader2 className="mr-1 size-3.5 animate-spin" /> : <Crosshair className="mr-1 size-3.5" />}
        {fixtureKeys.length === 1 ? 'Aim' : `Aim ${fixtureKeys.length} fixtures`}
      </Button>
      {result && (
        <div className="space-y-1 text-xs" role="status">
          {written.length > 0 && (
            <p className="text-muted-foreground">
              {written.length === 1
                ? `Aimed ${nameOf(written[0].target.key)}: pan ${written[0].panDeg}°, tilt ${written[0].tiltDeg}°`
                : `Aimed ${written.length} fixtures`}
            </p>
          )}
          {skipped.map((s) => (
            <p key={s.target.key} className="text-amber-600 dark:text-amber-400">
              {nameOf(s.target.key)} — {s.reason}
            </p>
          ))}
        </div>
      )}
    </section>
  )
}

interface StageAimPanelProps {
  projectId: number
  fixtureKeys: readonly string[]
  onClose: () => void
}

/**
 * The docked aim panel for a multi-selection in view mode — the single-fixture panel carries the
 * same controls under the fixture's own controls.
 */
export function StageAimPanel({ projectId, fixtureKeys, onClose }: StageAimPanelProps) {
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
      <StageAimControls projectId={projectId} fixtureKeys={fixtureKeys} />
    </aside>
  )
}

import { useMemo } from 'react'
import { cn } from '@/lib/utils'
import { findGel } from '@/data/gels'
import { DEFAULT_FIXTURE_COLOUR } from '@/components/fixtures/fixtureAppearance'
import { StageBackdrop } from '@/components/stage/StageBackdrop'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useProjectedPatches, type DrawnPoint } from '@/hooks/useProjectedPatches'
import type { CueTarget } from '@/api/cuesApi'
import { elementParents } from './targetUtils'
import { withoutInfrastructure } from '@/lib/infrastructure'

interface MiniStageProps {
  projectId: number
  targets: CueTarget[]
  /**
   * Optional pixel height. The default matches the inline stage view's compact
   * variant; callers in cards may want a smaller value.
   */
  heightClass?: string
}

/**
 * Compact stage map matching the look of the old global `StageOverviewPanel` (Positions since the
 * stage-view plan's session 1) —
 * grid backdrop, UPSTAGE/DOWNSTAGE labels, crosshair axes — but rendering the
 * **simulated cue state** (targeted fixtures glow; everything else is dimmed)
 * rather than the live fixture state.
 *
 * Falls back to a wrapped row of dots when no fixtures have stage coordinates,
 * so a freshly-patched project still gets a hint of which fixtures are lit.
 */
export function MiniStage({
  projectId,
  targets,
  heightClass = 'h-32',
}: MiniStageProps) {
  const { points: primaryPoints, extraPoints } = useProjectedPatches(projectId || undefined)
  // A paired dimmer's other lanterns light with it, so they are drawn as markers like any other.
  const placedPatches = useMemo<DrawnPoint[]>(
    () => [...primaryPoints, ...extraPoints],
    [primaryPoints, extraPoints],
  )
  const { fixtures, fixtureByKey, typeByKey } = useFixtureLookup()

  const groupTargetNames = useMemo(
    () => new Set(targets.filter((t) => t.type === 'group').map((t) => t.key)),
    [targets],
  )
  // A cell-target (one head of a multi-head fixture) lights the fixture it belongs to: the stage
  // draws whole fixtures, and a head's element key is no patch's key.
  const fixtureTargetKeys = useMemo(() => {
    const parentOf = elementParents(fixtures)
    return new Set(
      targets.filter((t) => t.type === 'fixture').map((t) => parentOf.get(t.key) ?? t.key),
    )
  }, [targets, fixtures])

  const isTargeted = (patch: { key: string; groups: { name: string }[] }) =>
    fixtureTargetKeys.has(patch.key) ||
    patch.groups.some((g) => groupTargetNames.has(g.name))

  // Fallback layout: when nothing is placed yet, show a wrapped row of dots so
  // the operator at least sees which fixtures are lit. Skip entirely if there
  // are zero fixtures in the project.
  if (placedPatches.length === 0) {
    // The same cut the plot makes (`useProjectedPatches`): infrastructure is never a dot.
    const shown = withoutInfrastructure(fixtures)
    if (!shown || shown.length === 0) return null
    return (
      <div className="rounded-md border bg-muted/40 p-2 relative overflow-hidden">
        <div className="flex flex-wrap gap-1.5 items-center">
          {shown.map((f) => {
            const lit =
              fixtureTargetKeys.has(f.key) ||
              f.groups.some((g) => groupTargetNames.has(g))
            const isBar = f.name.toLowerCase().includes('bar')
            return (
              <span
                key={f.key}
                className={cn(
                  'transition-colors',
                  isBar ? 'rounded-sm' : 'rounded-full',
                  lit
                    ? 'bg-foreground/90 shadow-[0_0_6px_rgba(255,248,213,0.7)]'
                    : 'bg-muted-foreground/30 border border-border/40',
                )}
                style={{ width: isBar ? 18 : 8, height: isBar ? 4 : 8 }}
                title={f.name}
              />
            )
          })}
        </div>
        <span className="absolute bottom-1 right-2 font-mono text-[8px] uppercase tracking-widest text-muted-foreground/60">
          Stage
        </span>
      </div>
    )
  }

  return (
    <StageBackdrop className={cn('w-full', heightClass)}>
      {placedPatches.map(({ patch, leftPct, topPct, placement }) => {
        const lit = isTargeted(patch)
        const fixture = fixtureByKey.get(patch.key)
        const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined
        const showCone = !!fixtureType?.acceptsBeamAngle
        const beamDeg = patch.beamAngleDeg ?? 30
        const colour = pickColour(patch.gelCode, !!fixtureType?.acceptsGel)
        return (
          <div
            key={placement ? `${patch.id}:${placement.uuid}` : patch.id}
            className="absolute -translate-x-1/2 -translate-y-1/2 pointer-events-none"
            style={{ left: `${leftPct}%`, top: `${topPct}%` }}
          >
            <SimulatedMarker
              colour={colour}
              lit={lit}
              showCone={showCone}
              beamDeg={beamDeg}
            />
          </div>
        )
      })}
    </StageBackdrop>
  )
}

/**
 * The gel arm of `FixtureAppearanceSource`, minus its live-colour branches: this surface
 * deliberately draws simulated cue targeting rather than the wire, so a fixture's own colour
 * properties are not consulted. The `acceptsGel` gate is the shared version's, though — a gel code
 * on a fixture whose type doesn't take gel is stale data, and honouring it here would tint a
 * colour-mixing LED that every other surface leaves at the default warm tungsten.
 */
function pickColour(gelCode: string | null, acceptsGel: boolean): string {
  if (acceptsGel && gelCode) {
    const gel = findGel(gelCode)
    if (gel) return gel.color
  }
  return DEFAULT_FIXTURE_COLOUR
}

function SimulatedMarker({
  colour,
  lit,
  showCone,
  beamDeg,
}: {
  colour: string
  lit: boolean
  showCone: boolean
  beamDeg: number
}) {
  const intensity = lit ? 1 : 0
  const glowSize = 12
  const coneWidth = beamDeg * 1.2
  const opacity = lit ? 1 : 0.25
  return (
    <div
      className="relative flex flex-col items-center"
      style={{ opacity }}
    >
      {showCone && lit && (
        <div
          className="pointer-events-none absolute"
          style={{
            left: '50%',
            top: '50%',
            transform: 'translateX(-50%)',
            width: `${coneWidth}px`,
            height: '60px',
            background: `radial-gradient(ellipse at 50% 0%, ${colour}cc 0%, ${colour}55 25%, transparent 65%)`,
            filter: 'blur(4px)',
            opacity: intensity,
            zIndex: -1,
          }}
        />
      )}
      <div
        className="rounded-full"
        style={{
          width: `${glowSize}px`,
          height: `${glowSize}px`,
          backgroundColor: colour,
          border: '1px solid rgba(255,255,255,0.2)',
          boxShadow: lit
            ? `0 0 ${4 + intensity * 12}px ${colour}, 0 0 ${8 + intensity * 20}px ${colour}aa`
            : 'none',
        }}
      />
    </div>
  )
}

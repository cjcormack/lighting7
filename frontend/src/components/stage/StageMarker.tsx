import type { Fixture, FixtureTypeInfo } from '../../store/fixtures'
import {
  FixtureAppearanceSource,
  type PixelSegment,
} from '../fixtures/fixtureAppearance'
import { parseCssRgb, perceptualBrightness } from '@/lib/colourMath'
import type { FixturePatch } from '../../api/patchApi'
import { cn } from '@/lib/utils'

const DEFAULT_BEAM_DEG = 30

interface StageMarkerProps {
  patch: FixturePatch
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  selected: boolean
  dimmed: boolean
  beamScale?: number
  /**
   * The name of the rigging this patch hangs on, for the amber badge beside the label.
   *
   * Resolved by the caller from `patch.riggingUuid` rather than read off the patch: the badge used
   * to render a free-text `riggingPosition` field, which the backend deleted when rigging became
   * first-class rows — so the marker had been drawing from a phantom. Undefined both for a
   * free-standing patch and for one whose rigging list hasn't arrived, and the badge is absent in
   * both cases: a placeholder would be a claim about the rig.
   */
  riggingName?: string
  /**
   * Set when this marker draws one of the patch's extra placements (a paired dimmer's other
   * lantern): its label, e.g. "SR", drawn after the fixture's name. `null` for a placement with no
   * label; absent for the patch's own placement.
   */
  placementLabel?: string | null
}

export function StageMarker(props: StageMarkerProps) {
  const { patch, fixture, fixtureType, selected, dimmed, beamScale = 1, riggingName, placementLabel } = props

  const showCone = !!fixtureType?.acceptsBeamAngle
  const beamDeg = patch.beamAngleDeg ?? DEFAULT_BEAM_DEG

  const labelClass = cn(
    'mt-1 flex items-center gap-1 whitespace-nowrap text-[10px] font-medium',
    selected ? 'text-foreground' : 'text-muted-foreground',
  )

  const wrapperStyle = {
    opacity: dimmed ? 0.25 : 1,
  }

  const renderLabel = (
    <div className={labelClass} style={{ textShadow: '0 0 4px rgba(0,0,0,0.7)' }}>
      <span>{patch.displayName}</span>
      {placementLabel && <span className="opacity-80">· {placementLabel}</span>}
      {riggingName && (
        <span
          className="rounded-sm px-1 py-px text-[9px] font-mono"
          style={{
            backgroundColor: 'rgba(240,196,111,0.12)',
            border: '1px solid rgba(240,196,111,0.3)',
            color: '#f0c46f',
          }}
        >
          {riggingName}
        </span>
      )}
    </div>
  )

  const commonProps = {
    selected,
    showCone,
    beamDeg,
    beamScale,
    label: renderLabel,
    wrapperStyle,
  }

  // The colour-source dispatch that used to live here is now shared with the 2D plot; see
  // `fixtureAppearance.tsx` for why it has to be a render prop rather than a hook.
  return (
    <FixtureAppearanceSource patch={patch} fixture={fixture} fixtureType={fixtureType}>
      {({ color, intensity, segments }) => (
        <MarkerVisual
          {...commonProps}
          color={color}
          intensity={intensity}
          segments={segments}
        />
      )}
    </FixtureAppearanceSource>
  )
}

interface MarkerChromeProps {
  selected: boolean
  showCone: boolean
  beamDeg: number
  beamScale: number
  label: React.ReactNode
  wrapperStyle: React.CSSProperties
}

/**
 * Apply an alpha to a marker colour. `FixtureAppearance.color` may be a hex (`#fff8d5`, `#666`)
 * from the gel / setting / placeholder branches or an `rgb(r, g, b)` string from
 * computeNormalizedHueCss — so a bare hex suffix like `${color}aa` silently
 * produces an invalid token (`rgb(255, 26, 0)aa`) and drops the whole box-shadow
 * / gradient for rgb() markers. Returns an `rgba(...)` string valid for both.
 */
function withAlpha(color: string, alpha: number): string {
  const rgb = parseCssRgb(color)
  return rgb ? `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${alpha})` : color
}

function MarkerVisual({
  color,
  intensity,
  selected,
  showCone,
  beamDeg,
  beamScale,
  label,
  wrapperStyle,
  segments,
}: MarkerChromeProps & { color: string; intensity: number; segments?: PixelSegment[] }) {
  const glowSize = 16 * beamScale
  const coneWidth = beamDeg * 1.6 * beamScale
  // Beam-visibility gate stays on the raw level, so a near-off fixture still
  // throws no beam even though the curve lifts how bright the dot looks.
  const showBeam = showCone && intensity > 0.05
  // Perceptual display brightness: linear opacity crushes dim fixtures to
  // invisible even though the real light is clearly lit.
  const lit = perceptualBrightness(intensity)

  // Border + glow + opacity are shared by the single dot and the pixel strip;
  // only the shape/size differs.
  const frameStyle: React.CSSProperties = {
    border: selected ? '2px solid #fff' : '1px solid rgba(255,255,255,0.2)',
    boxShadow: `0 0 ${4 + lit * 18}px ${color}, 0 0 ${8 + lit * 30}px ${withAlpha(color, 0.67)}`,
    opacity: 0.3 + lit * 0.7,
  }

  return (
    <div className="relative flex flex-col items-center" style={wrapperStyle}>
      {showBeam && (
        <div
          className="pointer-events-none absolute"
          style={{
            left: '50%',
            top: '50%',
            transform: 'translateX(-50%)',
            width: `${coneWidth}px`,
            height: '100px',
            background: `radial-gradient(ellipse at 50% 0%, ${withAlpha(color, 0.8)} 0%, ${withAlpha(color, 0.33)} 25%, transparent 65%)`,
            filter: 'blur(6px)',
            opacity: lit,
            zIndex: -1,
          }}
        />
      )}
      {segments ? (
        <div
          className="flex overflow-hidden rounded-sm"
          style={{
            ...frameStyle,
            width: `${Math.max(glowSize, segments.length * 4)}px`,
            height: `${Math.max(7, glowSize * 0.55)}px`,
          }}
        >
          {segments.map((seg, i) => (
            <div
              key={i}
              style={{ flex: 1, backgroundColor: seg.css, opacity: 0.25 + perceptualBrightness(seg.intensity) * 0.75 }}
            />
          ))}
        </div>
      ) : (
        <div
          className="rounded-full"
          style={{ ...frameStyle, width: `${glowSize}px`, height: `${glowSize}px`, backgroundColor: color }}
        />
      )}
      {label}
    </div>
  )
}

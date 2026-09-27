import { memo, useMemo } from 'react'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { FixturePatch, PatchPlacement } from '../../api/patchApi'
import { findGroupColourSource, type Fixture, type FixtureTypeInfo } from '../../store/fixtures'
import {
  FixtureAppearanceSource,
  type PixelSegment,
} from '../fixtures/fixtureAppearance'
import { dimCssColour, perceptualBrightness } from '../../lib/colourMath'
import { worldCornersFor, worldEndpointsFor } from '../../lib/stageGeometry'
import { project, type ScreenPoint, type StageProjection } from '../../lib/stageProjection'

/** Rendered bar thickness. Mirrors RIGGING_THICKNESS_M in RiggingMeshes. */
const RIGGING_THICKNESS_M = 0.18
/** A rigging whose projected length is under this is edge-on to the viewer. */
export const DEGENERATE_LENGTH_M = 1e-6

const FIXTURE_DOT_PX = 7
const FIXTURE_HIT_PX = 14
const LABEL_PX = 11
/** Per-pixel cell width on a pixel-bar strip. Mirrors the DOM marker's segment sizing. */
const SEGMENT_PX = 4
/** A pixel-bar strip is this fraction of the dot's diameter tall — a bar reads as a bar. */
const STRIP_HEIGHT_RATIO = 0.55
/**
 * Brightness floors, so an unlit fixture is still a visible object.
 *
 * Without them a dark rig — the normal state while patching, which is what this plot is mostly
 * for — draws every dot pure black, leaving only a 40%-opacity outline. The DOM marker has the
 * same floors as `0.3 + lit * 0.7` on its opacity and `0.25 + …` per segment; these are the same
 * numbers folded into the fill instead.
 */
const BODY_FLOOR = 0.3
const SEGMENT_FLOOR = 0.25

// — regions ————————————————————————————————————————————————————————

/**
 * A region's outline in the given projection.
 *
 * In plan the four floor corners already form the visible rectangle (yawed, so a
 * polygon rather than a rect). In an elevation a yawed box has no axis-aligned
 * outline, so the bounding rectangle of all eight projected corners is both
 * honest about the space it occupies and cheap.
 */
export function regionOutline(
  region: StageRegionDto,
  projection: StageProjection,
): { kind: 'polygon'; points: ScreenPoint[] } | { kind: 'rect'; points: ScreenPoint[] } {
  const corners = worldCornersFor(region).map(([x, y, z]) => project({ x, y, z }, projection))
  if (projection.id === 'plan') {
    return { kind: 'polygon', points: corners.slice(0, 4) }
  }
  return { kind: 'rect', points: corners }
}

function boundsOf(points: ScreenPoint[]) {
  const hs = points.map((p) => p.h)
  const vs = points.map((p) => p.v)
  const hMin = Math.min(...hs)
  const vMin = Math.min(...vs)
  return { h: hMin, v: vMin, w: Math.max(...hs) - hMin, hgt: Math.max(...vs) - vMin }
}

/**
 * Per-region hue, derived from the uuid. Mirrors the palette rule in
 * StageRegionMeshes so a region is the same colour in 2D as in 3D.
 */
function regionHue(uuid: string): number {
  let hash = 0
  for (let i = 0; i < uuid.length; i++) hash = (hash * 31 + uuid.charCodeAt(i)) | 0
  return Math.abs(hash) % 360
}

/**
 * Cursor for a shape the user might move.
 *
 * Matches the 3D rule that a shape must be selected before its body becomes a
 * drag handle — click to select, then drag to move. Showing `grab` only once
 * selected is what makes that discoverable.
 */
function bodyCursor(selected: boolean, editMode: boolean): 'grab' | 'pointer' {
  return editMode && selected ? 'grab' : 'pointer'
}

interface RegionShapesProps {
  regions: StageRegionDto[]
  projection: StageProjection
  selectedUuid: string | null
  interactive: boolean
  /** Enables the move cursor on the selected shape. */
  editMode?: boolean
  onPick?: (region: StageRegionDto, e: React.PointerEvent) => void
}

export const RegionShapes = memo(function RegionShapes({
  regions,
  projection,
  selectedUuid,
  interactive,
  editMode = false,
  onPick,
}: RegionShapesProps) {
  return (
    <g>
      {regions.map((region) => {
        const outline = regionOutline(region, projection)
        const selected = region.uuid === selectedUuid
        const hue = regionHue(region.uuid)
        const stroke = `hsl(${hue} 60% ${selected ? 72 : 55}%)`
        const fill = `hsl(${hue} 55% 50% / ${selected ? 0.28 : 0.14})`
        const common = {
          fill,
          stroke,
          strokeWidth: selected ? 2 : 1,
          vectorEffect: 'non-scaling-stroke' as const,
          pointerEvents: (interactive ? 'all' : 'none') as 'all' | 'none',
          onPointerDown: interactive && onPick ? (e: React.PointerEvent) => onPick(region, e) : undefined,
          style: interactive ? { cursor: bodyCursor(selected, editMode) } : undefined,
        }
        if (outline.kind === 'polygon') {
          return (
            <polygon
              key={region.uuid}
              points={outline.points.map((p) => `${p.h},${p.v}`).join(' ')}
              {...common}
            />
          )
        }
        const b = boundsOf(outline.points)
        return <rect key={region.uuid} x={b.h} y={b.v} width={b.w} height={b.hgt} {...common} />
      })}
    </g>
  )
})

// — riggings ————————————————————————————————————————————————————————

export interface ProjectedRigging {
  rig: RiggingDto
  a: ScreenPoint
  b: ScreenPoint
  /** Projected length. Near zero means the bar points at the viewer. */
  lengthOnScreen: number
  degenerate: boolean
}

export function projectRigging(rig: RiggingDto, projection: StageProjection): ProjectedRigging {
  const [wa, wb] = worldEndpointsFor(rig)
  const a = project(wa, projection)
  const b = project(wb, projection)
  const lengthOnScreen = Math.hypot(b.h - a.h, b.v - a.v)
  return { rig, a, b, lengthOnScreen, degenerate: lengthOnScreen < DEGENERATE_LENGTH_M }
}

interface RiggingShapesProps {
  riggings: RiggingDto[]
  projection: StageProjection
  selectedUuid: string | null
  /** Bar a fixture is being dragged over — highlighted as the drop target. */
  dropTargetUuid?: string | null
  interactive: boolean
  editMode?: boolean
  showLabels: boolean
  mPerPx: number
  onPick?: (rig: RiggingDto, e: React.PointerEvent) => void
}

export const RiggingShapes = memo(function RiggingShapes({
  riggings,
  projection,
  selectedUuid,
  dropTargetUuid,
  interactive,
  editMode = false,
  showLabels,
  mPerPx,
  onPick,
}: RiggingShapesProps) {
  return (
    <g>
      {riggings.map((rig) => {
        const { a, b, degenerate } = projectRigging(rig, projection)
        const selected = rig.uuid === selectedUuid
        const isDropTarget = rig.uuid === dropTargetUuid
        // Green rather than the selection yellow: this is "release here to hang
        // on this bar", which is a different thing from "this is selected".
        const colour = isDropTarget ? '#7fe0a0' : selected ? '#c8d3e2' : '#9aa5b4'
        const handlers = {
          pointerEvents: (interactive ? 'all' : 'none') as 'all' | 'none',
          onPointerDown:
            interactive && onPick ? (e: React.PointerEvent) => onPick(rig, e) : undefined,
          // An edge-on bar can still be selected, but not dragged in this view —
          // don't offer a grab cursor for a gesture that will be refused.
          style: interactive
            ? { cursor: degenerate ? 'pointer' : bodyCursor(selected, editMode) }
            : undefined,
        }

        // Edge-on: the bar runs along this projection's depth axis, so it has no
        // extent to draw. A ring marks it as present-but-not-adjustable here
        // rather than collapsing to an invisible zero-length line.
        if (degenerate) {
          return (
            <circle
              key={rig.uuid}
              cx={a.h}
              cy={a.v}
              r={RIGGING_THICKNESS_M}
              fill="none"
              stroke={colour}
              strokeWidth={selected ? 2.5 : 1.5}
              vectorEffect="non-scaling-stroke"
              {...handlers}
            />
          )
        }

        const mid = { h: (a.h + b.h) / 2, v: (a.v + b.v) / 2 }
        return (
          <g key={rig.uuid}>
            <line
              x1={a.h}
              y1={a.v}
              x2={b.h}
              y2={b.v}
              stroke={colour}
              strokeWidth={RIGGING_THICKNESS_M}
              strokeLinecap="butt"
              {...handlers}
            />
            {(selected || isDropTarget) && (
              <line
                x1={a.h}
                y1={a.v}
                x2={b.h}
                y2={b.v}
                stroke={isDropTarget ? '#2fbf6a' : '#ffe082'}
                strokeWidth={isDropTarget ? 2.5 : 1.5}
                vectorEffect="non-scaling-stroke"
                pointerEvents="none"
              />
            )}
            {showLabels && (
              <text
                x={mid.h}
                y={mid.v - RIGGING_THICKNESS_M}
                fontSize={LABEL_PX * mPerPx}
                textAnchor="middle"
                className="fill-muted-foreground"
                pointerEvents="none"
              >
                {rig.name}
              </text>
            )}
          </g>
        )
      })}
    </g>
  )
})

// — fixtures ————————————————————————————————————————————————————————

export interface ProjectedFixture {
  patch: FixturePatch
  screen: ScreenPoint
  /**
   * Set when this is one of the patch's extra placements — a paired dimmer's other lantern. It
   * draws, lights and selects as `patch`; only its position and label are its own.
   */
  placement?: PatchPlacement
  /**
   * For a fixture whose length is set per install (a lightstrip), its two projected ends: it is
   * drawn as a bar between them, at its real length, rather than as a dot. See `useProjectedPatches`.
   */
  span?: readonly [ScreenPoint, ScreenPoint]
}

/** A drawn fixture's identity: the patch, or the patch and one of its extra placements. */
function drawnKey({ patch, placement }: ProjectedFixture): string {
  return placement ? `${patch.key}\u0000${placement.uuid}` : patch.key
}

/** The text drawn beside a fixture: its name, and a lantern's own label after it. */
function labelFor({ patch, placement }: ProjectedFixture): string {
  const name = patch.displayName || patch.key
  return placement?.label ? `${name} · ${placement.label}` : name
}

interface FixtureShapesProps {
  fixtures: ProjectedFixture[]
  /** The anchor — drawn with the strongest highlight. */
  selectedKey: string | null
  /** Every selected object's key (`patch:<key>` form), for multi-select. */
  selectedKeys?: ReadonlySet<string>
  dimmedKeys?: ReadonlySet<string> | null
  showLabels: boolean
  mPerPx: number
  interactive: boolean
  editMode?: boolean
  /** `placement` is set when the press landed on one of the patch's extra placements. */
  onPick?: (patch: FixturePatch, e: React.PointerEvent, placement?: PatchPlacement) => void
  /**
   * Fixture and type lookups, for live colour. Each fixture's colour comes from its own
   * [FixtureShape] rather than from a `colourFor(patch)` callback: reading live values needs
   * per-fixture hooks, and a callback whose identity changed with the values would defeat this
   * component's `memo` and re-run the O(n²) label declutter on every DMX frame.
   */
  fixtureByKey: ReadonlyMap<string, Fixture>
  typeByKey: ReadonlyMap<string, FixtureTypeInfo>
}

/**
 * Chooses which fixture labels to draw.
 *
 * A real rig puts many fixtures on the same bar or the same downstage line, so
 * drawing every label produces an unreadable smear — a dozen names overprinted
 * on one pixel row. Greedily keep a label only when its box doesn't overlap one
 * already kept, working outward from the top-left so the choice is stable as the
 * user pans. O(n²), but n is fixtures-on-a-stage, and only when labels are on.
 *
 * The selected fixture always wins: its label is the one the user is looking for.
 */
function labelledKeys(
  fixtures: ProjectedFixture[],
  selectedKey: string | null,
  mPerPx: number,
): Set<string> {
  const lineH = LABEL_PX * mPerPx
  // ~0.55em average glyph advance is close enough for collision purposes.
  const widthOf = (text: string) => text.length * 0.55 * LABEL_PX * mPerPx

  const ordered = [...fixtures].sort((a, b) => {
    // The selected fixture's own placement first, then its other lanterns, then the rest.
    const rank = (f: ProjectedFixture) => (f.patch.key !== selectedKey ? 2 : f.placement ? 1 : 0)
    return rank(a) - rank(b) || a.screen.v - b.screen.v || a.screen.h - b.screen.h
  })

  const placed: Array<{ h0: number; h1: number; v0: number; v1: number }> = []
  const keep = new Set<string>()
  for (const fixture of ordered) {
    const { screen } = fixture
    const w = widthOf(labelFor(fixture))
    const box = {
      h0: screen.h - w / 2,
      h1: screen.h + w / 2,
      v0: screen.v - lineH * 2,
      v1: screen.v - lineH * 0.5,
    }
    const clashes = placed.some(
      (p) => box.h0 < p.h1 && box.h1 > p.h0 && box.v0 < p.v1 && box.v1 > p.v0,
    )
    if (clashes) continue
    placed.push(box)
    keep.add(drawnKey(fixture))
  }
  return keep
}

export const FixtureShapes = memo(function FixtureShapes({
  fixtures,
  selectedKey,
  selectedKeys,
  dimmedKeys,
  showLabels,
  mPerPx,
  interactive,
  editMode = false,
  onPick,
  fixtureByKey,
  typeByKey,
}: FixtureShapesProps) {
  const labelled = useMemo(
    () => (showLabels ? labelledKeys(fixtures, selectedKey, mPerPx) : null),
    [showLabels, fixtures, selectedKey, mPerPx],
  )
  return (
    <g>
      {fixtures.map((drawn) => {
        const { patch, screen, placement, span } = drawn
        // The anchor gets the full highlight; other members of a multi-selection
        // get a lighter ring, so it's clear which one the side panel is editing.
        // A paired lantern shares its patch's selection: selecting the fixture lights both.
        const selected = patch.key === selectedKey
        const fixture = fixtureByKey.get(patch.key)
        const key = drawnKey(drawn)
        return (
          <FixtureShape
            key={key}
            patch={patch}
            placement={placement}
            label={labelFor(drawn)}
            screen={screen}
            span={span}
            fixture={fixture}
            fixtureType={fixture ? typeByKey.get(fixture.typeKey) : undefined}
            selected={selected}
            inSelection={selected || (selectedKeys?.has(`patch:${patch.key}`) ?? false)}
            dimmed={dimmedKeys?.size ? !dimmedKeys.has(patch.key) : false}
            labelled={labelled?.has(key) ?? false}
            mPerPx={mPerPx}
            interactive={interactive}
            editMode={editMode}
            onPick={onPick}
          />
        )
      })}
    </g>
  )
})

interface FixtureShapeProps {
  patch: FixturePatch
  placement: PatchPlacement | undefined
  label: string
  screen: ScreenPoint
  span: readonly [ScreenPoint, ScreenPoint] | undefined
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  selected: boolean
  inSelection: boolean
  dimmed: boolean
  labelled: boolean
  mPerPx: number
  interactive: boolean
  editMode: boolean
  onPick?: (patch: FixturePatch, e: React.PointerEvent, placement?: PatchPlacement) => void
}

/**
 * One fixture on the plot.
 *
 * Its own component so it can hold the per-fixture hooks live colour needs — `FixtureShapes`
 * draws them in a loop, and hooks cannot go in a loop body. It also keeps the live values out of
 * `FixtureShapes`, whose `memo` and O(n²) label declutter must not re-run per DMX frame.
 */
function FixtureShape({
  patch,
  placement,
  label,
  screen,
  span,
  fixture,
  fixtureType,
  selected,
  inSelection,
  dimmed,
  labelled,
  mPerPx,
  interactive,
  editMode,
  onPick,
}: FixtureShapeProps) {
  const r = FIXTURE_DOT_PX * mPerPx
  // Strip geometry comes from the *descriptor*, not from the live appearance, so the hit target
  // can be sized to match the body. A pixel bar is drawn several times wider than a dot, and
  // sizing the hit area off the dot alone left the ends of a long bar unclickable.
  const strip = stripGeometry(fixture, screen, r, mPerPx)
  // A variable-length body is drawn to scale along its span, unless it is a pixel bar (whose
  // cells need the segmented strip) or its span projects to a point (a run seen end-on, in an
  // elevation), where a dot says more than a zero-length line.
  const bar = !strip && span && spanLength(span) > DEGENERATE_LENGTH_M ? span : null
  const cursor = placement ? 'pointer' : bodyCursor(selected, editMode)
  const pick = onPick ? (e: React.PointerEvent) => onPick(patch, e, placement) : undefined
  return (
    <g opacity={dimmed ? 0.3 : 1}>
      {/* Invisible hit target: a 7px dot is far below the ~44px a finger
          needs, and editing is enabled on tablets. */}
      {interactive && (
        bar ? (
          <line
            x1={bar[0].h}
            y1={bar[0].v}
            x2={bar[1].h}
            y2={bar[1].v}
            stroke="transparent"
            strokeWidth={2 * FIXTURE_HIT_PX * mPerPx}
            strokeLinecap="round"
            pointerEvents="stroke"
            style={{ cursor }}
            onPointerDown={pick}
          />
        ) : strip ? (
          <rect
            x={strip.left - (FIXTURE_HIT_PX - FIXTURE_DOT_PX) * mPerPx}
            y={strip.top - (FIXTURE_HIT_PX - FIXTURE_DOT_PX) * mPerPx}
            width={strip.width + 2 * (FIXTURE_HIT_PX - FIXTURE_DOT_PX) * mPerPx}
            height={strip.height + 2 * (FIXTURE_HIT_PX - FIXTURE_DOT_PX) * mPerPx}
            fill="transparent"
            pointerEvents="all"
            style={{ cursor }}
            onPointerDown={pick}
          />
        ) : (
          <circle
            cx={screen.h}
            cy={screen.v}
            r={FIXTURE_HIT_PX * mPerPx}
            fill="transparent"
            pointerEvents="all"
            style={{ cursor }}
            onPointerDown={pick}
          />
        )
      )}
      <FixtureAppearanceSource patch={patch} fixture={fixture} fixtureType={fixtureType}>
        {({ color, intensity, segments }) => (
          <FixtureBody
            screen={screen}
            r={r}
            strip={strip}
            bar={bar}
            color={color}
            intensity={intensity}
            segments={segments}
            selected={selected}
            inSelection={inSelection}
          />
        )}
      </FixtureAppearanceSource>
      {patch.stageHidden && (
        <line
          x1={screen.h - r}
          y1={screen.v + r}
          x2={screen.h + r}
          y2={screen.v - r}
          stroke="var(--color-background)"
          strokeWidth={2}
          vectorEffect="non-scaling-stroke"
          pointerEvents="none"
        />
      )}
      {labelled && (
        <text
          x={screen.h}
          y={screen.v - r - 2 * mPerPx}
          fontSize={LABEL_PX * mPerPx}
          textAnchor="middle"
          className={selected ? 'fill-foreground' : 'fill-muted-foreground'}
          pointerEvents="none"
        >
          {label}
        </text>
      )}
    </g>
  )
}

export interface StripGeometry {
  left: number
  top: number
  width: number
  height: number
  count: number
}

/**
 * Where a pixel bar's strip sits, or null for a fixture that draws as a dot.
 *
 * Derived from the fixture's element-group descriptor rather than from a live appearance, so the
 * hit target and the body agree on the shape without the hit target having to wait on DMX.
 *
 * The `count > 1` gate must stay in step with `FixtureAppearanceSource`'s, which is what decides
 * whether an appearance carries `segments` at all — a disagreement would draw a strip-shaped hit
 * target over a dot, or vice versa. `Stage2DShapes.test.ts` pins the two together.
 */
export function stripGeometry(
  fixture: Fixture | undefined,
  screen: ScreenPoint,
  r: number,
  mPerPx: number,
): StripGeometry | null {
  const count = findGroupColourSource(fixture)?.memberColourChannels.length ?? 0
  if (count <= 1) return null
  const width = Math.max(2 * r, count * SEGMENT_PX * mPerPx)
  const height = 2 * r * STRIP_HEIGHT_RATIO
  return { left: screen.h - width / 2, top: screen.v - height / 2, width, height, count }
}

/**
 * The lit body: a dot, or a segmented strip for a pixel bar.
 *
 * Brightness is baked into the fill rather than applied as `fill-opacity`, so it composes with
 * the group-filter dimming and the selection ring instead of fighting them.
 */
function FixtureBody({
  screen,
  r,
  strip,
  bar,
  color,
  intensity,
  segments,
  selected,
  inSelection,
}: {
  screen: ScreenPoint
  r: number
  strip: StripGeometry | null
  bar: readonly [ScreenPoint, ScreenPoint] | null
  color: string
  intensity: number
  segments?: PixelSegment[]
  selected: boolean
  inSelection: boolean
}) {
  // Outline via a Tailwind class rather than a literal, so a pale tungsten dot stays visible
  // against a light background. CSS beats the presentation attribute, hence the attribute is
  // set *only* for the selection highlight — giving unselected fixtures a `stroke` here would
  // silently kill that theme-aware outline.
  const outline = {
    className: inSelection ? undefined : 'stroke-foreground/40',
    stroke: selected ? '#ffe082' : inSelection ? '#b08d2e' : undefined,
    strokeWidth: selected ? 2.5 : inSelection ? 2 : 1,
    vectorEffect: 'non-scaling-stroke' as const,
    pointerEvents: 'none' as const,
  }

  if (strip && segments && segments.length > 1) {
    const segW = strip.width / segments.length
    return (
      <>
        {segments.map((seg, i) => (
          <rect
            key={i}
            x={strip.left + i * segW}
            y={strip.top}
            // A sliver of overlap, so antialiasing doesn't draw a hairline of
            // background between neighbouring pixels.
            width={segW * 1.02}
            height={strip.height}
            fill={dimCssColour(seg.css, perceptualBrightness(seg.intensity, SEGMENT_FLOOR))}
            pointerEvents="none"
          />
        ))}
        <rect
          x={strip.left}
          y={strip.top}
          width={strip.width}
          height={strip.height}
          fill="none"
          {...outline}
        />
      </>
    )
  }

  if (bar) {
    // The outline is a wider stroke laid under the lit one, so it reads as a border round the bar
    // at any angle; a stroke has no border of its own.
    const thickness = 2 * r * STRIP_HEIGHT_RATIO
    // `outline.strokeWidth` is in screen pixels (non-scaling); this stroke scales, so convert —
    // and double it, since half of a stroke under the bar is hidden by the lit line on top.
    const border = outline.strokeWidth * 2 * (r / FIXTURE_DOT_PX)
    const ends = { x1: bar[0].h, y1: bar[0].v, x2: bar[1].h, y2: bar[1].v }
    return (
      <>
        <line
          {...ends}
          className={outline.className}
          stroke={outline.stroke}
          strokeWidth={thickness + border}
          strokeLinecap="round"
          pointerEvents="none"
        />
        <line
          {...ends}
          stroke={dimCssColour(color, perceptualBrightness(intensity, BODY_FLOOR))}
          strokeWidth={thickness}
          strokeLinecap="round"
          pointerEvents="none"
        />
      </>
    )
  }

  return (
    <circle
      cx={screen.h}
      cy={screen.v}
      r={r}
      fill={dimCssColour(color, perceptualBrightness(intensity, BODY_FLOOR))}
      {...outline}
    />
  )
}

/** A span's projected length, in screen-metres. */
function spanLength([a, b]: readonly [ScreenPoint, ScreenPoint]): number {
  return Math.hypot(b.h - a.h, b.v - a.v)
}

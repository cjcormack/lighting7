import { memo, useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { ChevronDown, ChevronRight, Crosshair, X } from 'lucide-react'
import { Slider } from '@/components/ui/slider'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import { SWATCH_FLOOR } from '@/lib/colourMath'
import { dmxToDegrees } from '@/lib/axisDegrees'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { useChannelValue, useSettingValue, useSliderValue } from '@/hooks/usePropertyValues'
import { useColourAppearance } from '@/hooks/useColourAppearance'
import { useVirtualDimmer } from '@/hooks/useVirtualDimmer'
import { usePropertyParkStatus } from '@/hooks/usePropertyParkStatus'
import type { SliderPropertyDescriptor } from '@/store/fixtures'
import { EditorLabel } from '../editor/EditorLabel'
import { ColourEditor } from '../editor/ColourEditor'
import { SettingCell } from '../fixtures-list/cells/SettingCell'
import { fromPct, toPct } from '../fixtures-list/cells/SliderCell'
import type { CellCommit } from '../fixtures-list/rowModel'
import { SheetField } from './SheetField'
import { SOURCE_EDGE_CLASS, SourceChip } from './SourceChip'
import { FINGER_BUTTON_CLASS, FINGER_HEIGHT_CLASS, useFingerSized, useFixtureSheet } from './sheetContext'
import { useRowSource } from './useRowSource'
import { clearSheetRow, writePickColour, writePickVirtualDimmer, writeSheetLevel, writeSheetPosition } from './sheetWrites'
import type { RowSource } from './rowSource'
import type { SheetRow } from './sheetRows'

/**
 * One property of the sheet (D4–D8): its name, its source chip, an × while the programmer holds it,
 * and an editor-kit control in the row's unit. A row is live while the desk is connected and is
 * read-only only offline, or while one of its channels is parked (D2).
 */
export const PropertyRow = memo(function PropertyRow({
  row,
  headKey,
  headName,
  sourceGroup,
}: {
  row: SheetRow
  headKey: string
  /** The head's name, for the stack's section line. */
  headName?: string
  /** A group sheet's one picked member: its writes carry the group (D13). */
  sourceGroup?: string
}) {
  const head = { headKey, headName: headName ?? headKey, sourceGroup }
  switch (row.kind) {
    case 'slider':
      return <SliderRow row={row} {...head} />
    case 'virtual-dimmer':
      return <VirtualDimmerRow row={row} {...head} />
    case 'setting':
      return <SettingRow row={row} {...head} />
    case 'colour':
      return <ColourRow row={row} {...head} />
    case 'position':
      return <PositionRow row={row} {...head} />
  }
})

/** Which head a row is on, and the group a member's write carries. */
interface RowHead {
  headKey: string
  headName: string
  sourceGroup?: string
}

/** The frame every row shares: the edge, the name line, and the control beneath it. */
function RowFrame({
  row,
  headKey,
  headName,
  source,
  readOnly,
  parked,
  trailing,
  children,
}: {
  row: SheetRow
  headKey: string
  headName: string
  source: RowSource
  readOnly: boolean
  parked: boolean
  trailing?: ReactNode
  children: ReactNode
}) {
  const { openRowId, connected } = useFixtureSheet()
  const finger = useFingerSized()
  const name = row.label
  return (
    <div
      data-row={row.id}
      data-kind={row.kind}
      data-source={source.kind}
      data-read-only={readOnly || undefined}
      data-parked={parked || undefined}
      className={cn(
        'relative flex flex-col gap-1.5 py-[7px] pr-3 pl-4',
        "before:absolute before:top-[7px] before:bottom-[7px] before:left-0 before:w-[3px] before:rounded-r-sm before:content-['']",
        SOURCE_EDGE_CLASS[source.kind],
        openRowId === openKey(headKey, row) && 'bg-primary/[0.07]',
      )}
    >
      <div className="flex min-h-5 min-w-0 items-center gap-1.5">
        <span className={cn('shrink-0 text-[12.5px] font-medium', source.kind === 'base' && 'opacity-60')}>{name}</span>
        <SourceChip source={source} heads={[{ key: headKey, name: headName }]} propertyName={row.keys[0]} label={name} />
        <span className="flex-1" />
        {trailing}
        {source.holds && (
          <button
            type="button"
            data-row-clear
            aria-label={`Clear your ${name.toLowerCase()}`}
            title={`Clear your ${name.toLowerCase()} — it falls to what is underneath`}
            disabled={!connected}
            onClick={() => clearSheetRow(headKey, row.keys, getProgrammerFadeMs())}
            className={cn(
              'grid size-[22px] shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50',
              finger && FINGER_BUTTON_CLASS,
            )}
          >
            <X className={finger ? 'size-4' : 'size-3'} />
          </button>
        )}
      </div>
      <div className={cn('flex min-w-0 items-center gap-2', source.kind === 'base' && 'opacity-60 focus-within:opacity-100 hover:opacity-100')}>
        {children}
      </div>
    </div>
  )
}

function useRowReadOnly(parked: boolean): boolean {
  const { connected } = useFixtureSheet()
  return !connected || parked
}

// ─── Slider ────────────────────────────────────────────────────────────────

function SliderRow({ row, headKey, headName, sourceGroup }: { row: Extract<SheetRow, { kind: 'slider' }> } & RowHead) {
  const { property } = row
  const source = useRowSource(headKey, row.keys)
  const { isAnyParked } = usePropertyParkStatus(property)
  const readOnly = useRowReadOnly(isAnyParked)
  const value = useSliderValue(property)
  return (
    <RowFrame row={row} headKey={headKey} headName={headName} source={source} readOnly={readOnly} parked={isAnyParked}>
      <LevelControl
        label={row.label}
        value={value}
        min={property.min}
        max={property.max}
        readOnly={readOnly}
        // A drag follows the hand; a typed value takes the programmer fade.
        onDrag={(v) => writeSheetLevel(headKey, property.name, v, undefined, sourceGroup)}
        onTyped={(v) => writeSheetLevel(headKey, property.name, v, getProgrammerFadeMs(), sourceGroup)}
      />
    </RowFrame>
  )
}

function VirtualDimmerRow({ row, headKey, headName, sourceGroup }: { row: Extract<SheetRow, { kind: 'virtual-dimmer' }> } & RowHead) {
  const source = useRowSource(headKey, row.keys)
  const { isAnyParked } = usePropertyParkStatus(row.property)
  const readOnly = useRowReadOnly(isAnyParked)
  const { value, setValue } = useVirtualDimmer(row.property, headKey)
  // A group's member carries the group, which `useVirtualDimmer` cannot say: the pick's write is the
  // same scaling with `sourceGroup` on it.
  const write = (v: number, fadeMs?: number) =>
    sourceGroup == null
      ? setValue(v, fadeMs)
      : writePickVirtualDimmer({ kind: 'heads', keys: [headKey], sourceGroup }, [{ key: headKey, property: row.property }], v, fadeMs)
  return (
    <RowFrame row={row} headKey={headKey} headName={headName} source={source} readOnly={readOnly} parked={isAnyParked}>
      <LevelControl
        label={row.label}
        value={value}
        min={0}
        max={255}
        readOnly={readOnly}
        onDrag={(v) => write(v)}
        onTyped={(v) => write(v, getProgrammerFadeMs())}
      />
    </RowFrame>
  )
}

/**
 * A slider in bytes beside a **percent** field — the programmer grid's unit (editor-kit D13), so
 * the sheet and the grid read one number. Enter commits the field; the slider writes as it moves.
 */
export function LevelControl({
  label,
  value,
  min,
  max,
  readOnly,
  onDrag,
  onTyped,
  lowest,
}: {
  label: string
  /** The value — over a pick of heads (D13), the highest, where the thumb sits. */
  value: number
  min: number
  max: number
  readOnly: boolean
  onDrag: (v: number) => void
  onTyped: (v: number) => void
  /**
   * Over a pick whose heads differ: the lowest, so the track draws the range between the two (the
   * group visualisers' reading) and the field says `40–80`.
   */
  lowest?: number
}) {
  const mixed = lowest != null && lowest !== value
  const span = Math.max(1, max - min)
  return (
    <>
      <Slider
        aria-label={label}
        value={[value]}
        min={min}
        max={max}
        step={1}
        disabled={readOnly}
        onValueChange={([v]) => v !== undefined && onDrag(v)}
        className={cn(
          'min-w-[60px] flex-1',
          // The range: the fill runs to the highest head, and the track's own colour masks it below
          // the lowest, so what is drawn is the band between the two with the thumb at its top —
          // the group visualisers' reading (HeadsGroups board, note 4).
          mixed &&
            "after:pointer-events-none after:absolute after:top-1/2 after:left-0 after:h-1.5 after:w-[var(--range-from)] after:-translate-y-1/2 after:rounded-l-full after:bg-muted after:content-[''] [&_[data-slot=slider-thumb]]:z-10",
        )}
        style={mixed ? ({ '--range-from': `${((lowest - min) / span) * 100}%` } as React.CSSProperties) : undefined}
        data-range={mixed ? `${lowest}-${value}` : undefined}
      />
      <SheetField
        label={`${label} percent`}
        unit="%"
        mixed={mixed ? `${toPct(lowest)}–${toPct(value)}` : undefined}
        // Room for `100–100` and its unit while the heads differ.
        className={mixed ? 'w-[96px]' : undefined}
        value={toPct(value)}
        min={0}
        max={100}
        disabled={readOnly}
        onEnter={(pct) => onTyped(Math.max(min, Math.min(max, fromPct(Math.max(0, Math.min(100, pct))))))}
      />
    </>
  )
}

// ─── Setting ───────────────────────────────────────────────────────────────

function SettingRow({ row, headKey, headName, sourceGroup }: { row: Extract<SheetRow, { kind: 'setting' }> } & RowHead) {
  const { property } = row
  const source = useRowSource(headKey, row.keys)
  const { isAnyParked } = usePropertyParkStatus(property)
  const readOnly = useRowReadOnly(isAnyParked)
  const { level, option } = useSettingValue(property)
  const cellValue = useMemo(() => ({ kind: 'setting' as const, isUniform: true, level, option }), [level, option])
  const resolutions = useMemo(() => [{ kind: 'setting' as const, property }], [property])
  const index = option ? property.options.findIndex((o) => o.name === option.name) : -1
  const onCommit = useCallback(
    (commit: CellCommit) => {
      if (commit.kind === 'setting') writeSheetLevel(headKey, property.name, commit.level, getProgrammerFadeMs(), sourceGroup)
    },
    [headKey, property.name, sourceGroup],
  )
  const noop = useCallback(() => {}, [])
  const finger = useFingerSized()
  return (
    <RowFrame row={row} headKey={headKey} headName={headName} source={source} readOnly={readOnly} parked={isAnyParked}>
      {/* The programmer grid's setting cell, type-ahead and all (D7): one list for a step on both
          surfaces. Its trigger is the row's field here. */}
      <div className={cn('flex h-7 min-w-0 flex-1 items-center rounded-md border bg-background', finger && FINGER_HEIGHT_CLASS)}>
        <SettingCell value={cellValue} resolutions={resolutions} label={row.label} disabled={readOnly} onCommit={onCommit} onBeginEdit={noop} />
      </div>
      {index >= 0 && property.options.length > 1 && (
        <span className="shrink-0 font-mono text-[11px] text-muted-foreground">
          {index + 1} of {property.options.length}
        </span>
      )}
    </RowFrame>
  )
}

// ─── Colour ────────────────────────────────────────────────────────────────

/** A row's identity for "the one open row": the row on its head, since every head has a Position. */
export function openKey(headKey: string, row: SheetRow): string {
  return `${headKey}\u0000${row.id}`
}

export function OpenToggle({ row, headKey, label }: { row: SheetRow; headKey: string; label: string }) {
  const { openRowId, setOpenRowId } = useFixtureSheet()
  const finger = useFingerSized()
  const key = openKey(headKey, row)
  const open = openRowId === key
  return (
    <button
      type="button"
      aria-expanded={open}
      aria-label={open ? `Close the ${label.toLowerCase()} editor` : `Open the ${label.toLowerCase()} editor`}
      onClick={() => setOpenRowId(open ? null : key)}
      className={cn(
        'grid size-[22px] shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground',
        finger && FINGER_BUTTON_CLASS,
      )}
    >
      {open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
    </button>
  )
}

function ColourRow({ row, headKey, headName, sourceGroup }: { row: Extract<SheetRow, { kind: 'colour' }> } & RowHead) {
  const { property } = row
  const { openRowId } = useFixtureSheet()
  const open = openRowId === openKey(headKey, row)
  const source = useRowSource(headKey, row.keys)
  const { isAnyParked } = usePropertyParkStatus(property)
  const readOnly = useRowReadOnly(isAnyParked)
  // The swatch is how the head looks — colour × its dimmer, the old visualiser's reading — and the
  // text beside it is the value (Chris's call, session 2 review).
  const colour = useColourAppearance(property, row.dimmer, SWATCH_FLOOR)
  const uvLit = colour.uv !== undefined && colour.uv > 0
  // One entry for the whole colour, only the extended components the head has a channel for.
  const update = useCallback(
    (r: number, g: number, b: number, w?: number, a?: number, uv?: number) =>
      writePickColour({ kind: 'heads', keys: [headKey], sourceGroup }, [{ key: headKey, property }], { r, g, b, w, a, uv }),
    [headKey, property, sourceGroup],
  )
  const extended = [
    colour.w !== undefined && `W ${colour.w}`,
    colour.a !== undefined && `A ${colour.a}`,
    colour.uv !== undefined && `UV ${colour.uv}`,
  ].filter(Boolean)
  return (
    <RowFrame row={row} headKey={headKey} headName={headName} source={source} readOnly={readOnly} parked={isAnyParked} trailing={<OpenToggle row={row} headKey={headKey} label={row.label} />}>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex h-7 min-w-0 items-center gap-2 rounded-md border bg-background px-2 text-xs">
          <span
            data-testid="colour-swatch"
            className={cn('relative size-3.5 shrink-0 rounded border border-white/15', uvLit && 'ring-2 ring-purple-500/50')}
            style={{ backgroundColor: colour.appearanceCss }}
          >
            {uvLit && (
              <span
                data-testid="uv-dot"
                title={`UV: ${colour.uv}`}
                className="absolute -top-1 -right-1 size-2 rounded-full border border-background bg-purple-500"
              />
            )}
          </span>
          <span className="truncate font-mono">{colour.combinedCss}</span>
          <span className="flex-1" />
          <span className="truncate font-mono text-[11px] text-muted-foreground">
            R {colour.r} · G {colour.g} · B {colour.b}
            {extended.length > 0 && ` · ${extended.join(' · ')}`}
          </span>
        </div>
        {open && (
          // The editor kit's ColourEditor, docked in the row (D7, call 5): the picker and the typed
          // channels, no Spread or Recent — the sheet is one fixture.
          <fieldset disabled={readOnly} className="min-w-0" data-testid="colour-editor">
            <ColourEditor
              r={colour.r}
              g={colour.g}
              b={colour.b}
              w={colour.w}
              a={colour.a}
              uv={colour.uv}
              combinedCss={colour.combinedCss}
              hasWhiteChannel={!!property.whiteChannel}
              hasAmberChannel={!!property.amberChannel}
              hasUvChannel={!!property.uvChannel}
              onColourChange={update}
              channelFields
              footer={false}
              counts={false}
              readOnly={readOnly}
              open
            />
          </fieldset>
        )}
      </div>
    </RowFrame>
  )
}

// ─── Position ──────────────────────────────────────────────────────────────

export function axisText(byte: number, slider: SliderPropertyDescriptor | undefined, degrees: boolean): string {
  if (degrees && slider) {
    const deg = dmxToDegrees(byte, slider)
    if (deg != null) return `${Math.round(deg)}°`
  }
  return String(byte)
}

function PositionRow({ row, headKey, headName, sourceGroup }: { row: Extract<SheetRow, { kind: 'position' }> } & RowHead) {
  const { resolution, degrees } = row
  const { openRowId, aim, target } = useFixtureSheet()
  const open = openRowId === openKey(headKey, row)
  const source = useRowSource(headKey, row.keys)
  const panPark = usePropertyParkStatus(resolution.property ?? resolution.panProperty!)
  const tiltPark = usePropertyParkStatus(resolution.property ?? resolution.tiltProperty!)
  const parked = panPark.isAnyParked || tiltPark.isAnyParked
  const readOnly = useRowReadOnly(parked)
  const pan = useChannelValue(resolution.pan)
  const tilt = useChannelValue(resolution.tilt)
  const panDeg = degrees && resolution.panProperty ? dmxToDegrees(pan, resolution.panProperty) : null
  const tiltDeg = degrees && resolution.tiltProperty ? dmxToDegrees(tilt, resolution.tiltProperty) : null

  const write = useCallback(
    (commit: Extract<CellCommit, { kind: 'position' }>, typed: boolean) =>
      writeSheetPosition(headKey, resolution, commit, typed ? getProgrammerFadeMs() : undefined, sourceGroup),
    [headKey, resolution, sourceGroup],
  )

  const panNorm = (pan - resolution.panMin) / Math.max(1, resolution.panMax - resolution.panMin)
  const tiltNorm = (tilt - resolution.tiltMin) / Math.max(1, resolution.tiltMax - resolution.tiltMin)

  const aimButton =
    // Aim is the fixture's (`StageAimControls` over its key), so only the fixture's own Position row
    // offers it — a head's row would aim the whole fixture.
    aim != null && degrees && target.type === 'fixture' && headKey === target.fixture.key ? (
      <Popover>
        <PopoverTrigger asChild>
          <Button variant="outline" size="sm" className="h-6 gap-1 px-2 text-[11px]" disabled={readOnly}>
            <Crosshair className="size-3" />
            Aim…
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-80" data-testid="aim-popover">
          {aim}
        </PopoverContent>
      </Popover>
    ) : null

  return (
    <RowFrame row={row} headKey={headKey} headName={headName} source={source} readOnly={readOnly} parked={parked} trailing={<OpenToggle row={row} headKey={headKey} label={row.label} />}>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex min-w-0 items-start gap-2">
          <PositionPad
            size={open ? 120 : 64}
            pan={panNorm}
            tilt={tiltNorm}
            readOnly={readOnly}
            onMove={(x, y) =>
              write(
                {
                  kind: 'position',
                  pan: Math.round(resolution.panMin + x * (resolution.panMax - resolution.panMin)),
                  tilt: Math.round(resolution.tiltMin + y * (resolution.tiltMax - resolution.tiltMin)),
                },
                false,
              )
            }
          />
          {open ? (
            <div className="flex min-w-0 flex-1 flex-col gap-1.5">
              <AxisControl
                label="Pan"
                byte={pan}
                deg={panDeg}
                min={resolution.panMin}
                max={resolution.panMax}
                slider={resolution.panProperty}
                readOnly={readOnly}
                onCommit={(c, typed) => write(c, typed)}
                axis="pan"
              />
              <AxisControl
                label="Tilt"
                byte={tilt}
                deg={tiltDeg}
                min={resolution.tiltMin}
                max={resolution.tiltMax}
                slider={resolution.tiltProperty}
                readOnly={readOnly}
                onCommit={(c, typed) => write(c, typed)}
                axis="tilt"
              />
            </div>
          ) : (
            <div className="flex min-w-0 flex-1 flex-col gap-0.5 pt-1 font-mono text-xs">
              <span>
                <span className="mr-1.5 text-[9px] font-bold tracking-wider text-muted-foreground uppercase">Pan</span>
                {axisText(pan, resolution.panProperty, degrees)}
              </span>
              <span>
                <span className="mr-1.5 text-[9px] font-bold tracking-wider text-muted-foreground uppercase">Tilt</span>
                {axisText(tilt, resolution.tiltProperty, degrees)}
              </span>
            </div>
          )}
        </div>
        {aimButton && <div className="flex justify-end">{aimButton}</div>}
      </div>
    </RowFrame>
  )
}

export function AxisControl({
  label,
  axis,
  byte,
  deg,
  min,
  max,
  slider,
  readOnly,
  onCommit,
}: {
  label: string
  axis: 'pan' | 'tilt'
  byte: number
  deg: number | null
  min: number
  max: number
  slider: SliderPropertyDescriptor | undefined
  readOnly: boolean
  onCommit: (commit: Extract<CellCommit, { kind: 'position' }>, typed: boolean) => void
}) {
  // Degrees where the head annotates travel (D14 of the editor kit, the grid's PositionCell rule),
  // resolved to this head's own byte by `clampCommitToResolution`; bytes elsewhere.
  const inDegrees = deg != null && slider?.degMin != null && slider.degMax != null
  const lo = inDegrees ? Math.min(slider.degMin!, slider.degMax!) : min
  const hi = inDegrees ? Math.max(slider.degMin!, slider.degMax!) : max
  return (
    <div className="flex min-w-0 items-center gap-2">
      <EditorLabel className="w-7 shrink-0">{label}</EditorLabel>
      <Slider
        aria-label={label}
        value={[byte]}
        min={min}
        max={max}
        step={1}
        disabled={readOnly}
        onValueChange={([v]) => v !== undefined && onCommit({ kind: 'position', [axis]: v }, false)}
        className="min-w-[40px] flex-1"
      />
      <SheetField
        label={`${label} ${inDegrees ? 'degrees' : 'value'}`}
        unit={inDegrees ? '°' : undefined}
        value={inDegrees ? Math.round(deg) : byte}
        min={lo}
        max={hi}
        disabled={readOnly}
        onEnter={(n) => {
          const v = Math.max(lo, Math.min(hi, n))
          onCommit(inDegrees ? { kind: 'position', [`${axis}Deg`]: v } : { kind: 'position', [axis]: Math.round(v) }, true)
        }}
      />
    </div>
  )
}

/** The XY pad: a press or a drag writes both axes in one `setPosition`. */
export function PositionPad({
  size,
  pan,
  tilt,
  readOnly,
  onMove,
}: {
  size: number
  pan: number
  tilt: number
  readOnly: boolean
  onMove: (x: number, y: number) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [dragging, setDragging] = useState(false)
  const at = (e: React.PointerEvent) => {
    const rect = ref.current?.getBoundingClientRect()
    if (!rect || rect.width === 0) return
    onMove(
      Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)),
      Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height)),
    )
  }
  return (
    <div
      ref={ref}
      role="presentation"
      data-testid="position-pad"
      className={cn(
        'relative shrink-0 touch-none rounded-md border bg-background',
        'bg-[linear-gradient(var(--color-border)_1px,transparent_1px),linear-gradient(90deg,var(--color-border)_1px,transparent_1px)] bg-[size:25%_25%]',
        !readOnly && 'cursor-crosshair',
      )}
      style={{ width: size, height: size }}
      onPointerDown={(e) => {
        if (readOnly) return
        e.currentTarget.setPointerCapture?.(e.pointerId)
        setDragging(true)
        at(e)
      }}
      onPointerMove={(e) => dragging && at(e)}
      onPointerUp={() => setDragging(false)}
      onPointerCancel={() => setDragging(false)}
    >
      <span
        className="absolute size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-[0_0_0_2px_var(--color-primary)]"
        // Inset by the dot's radius, so a head at an end of its travel is drawn inside the pad.
        style={{
          left: `calc(5px + (100% - 10px) * ${Math.max(0, Math.min(1, pan))})`,
          top: `calc(5px + (100% - 10px) * ${Math.max(0, Math.min(1, tilt))})`,
        }}
      />
    </div>
  )
}

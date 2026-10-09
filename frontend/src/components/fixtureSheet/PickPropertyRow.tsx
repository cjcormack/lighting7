import { memo, useCallback, useMemo, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { computeAppearanceCss, computeCombinedCss, perceptualBrightness, SWATCH_FLOOR } from '@/lib/colourMath'
import { dmxToDegrees } from '@/lib/axisDegrees'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { colourFactor } from '@/hooks/useNormalizedIntensity'
import { resolveSettingOption } from '@/hooks/usePropertyValues'
import { useGetParkStateListQuery } from '@/store/park'
import type { ChannelRef, ColourPropertyDescriptor, SliderPropertyDescriptor } from '@/store/fixtures'
import { ColourEditor } from '../editor/ColourEditor'
import { SettingCell } from '../fixtures-list/cells/SettingCell'
import type { CellCommit } from '../fixtures-list/rowModel'
import { AxisControl, LevelControl, OpenToggle, openKey, PositionPad } from './PropertyRow'
import { MIXED_EDGE_CLASS, SOURCE_EDGE_CLASS, SourceChip } from './SourceChip'
import { FINGER_BUTTON_CLASS, FINGER_HEIGHT_CLASS, useFingerSized, useFixtureSheet } from './sheetContext'
import { usePickSource } from './useRowSource'
import { useChannelValues } from './useChannelValues'
import {
  clearPickRow,
  writePickColour,
  writePickLevel,
  writePickPosition,
  writePickVirtualDimmer,
} from './sheetWrites'
import type { PickSource } from './rowSource'
import type { PickRow, PickWrite } from './sheetPick'
import type { PositionResolution, SheetRow } from './sheetRows'

type RowOf<K extends SheetRow['kind']> = Extract<SheetRow, { kind: K }>
type HeadsOf<K extends SheetRow['kind']> = { key: string; name: string; row: RowOf<K> }[]

/**
 * One property over a pick of heads or members (fixture-fx-sheets plan D13; HeadsGroups board): the
 * row a single head draws (`PropertyRow`), read over every picked head and written to all of them.
 *
 * - **Mixed values** read as a range — the slider's band from the lowest head to the highest, the
 *   thumb at the highest, the field saying `40–80` — or, for a colour, a strip of the heads'
 *   swatches. A value set here lands on every picked head.
 * - **Mixed sources** draw the edge dashed and the chip counts the heads its source holds
 *   (*Programmer · 2 of 4*); the stack lists each source with its heads.
 * - **Writes** go where [write] says (`sheetPick.ts`'s `PickWrite`): one group entry for a group's
 *   *All*, else each head's own, a member's carrying the group. Never a raw channel.
 */
export const PickPropertyRow = memo(function PickPropertyRow({ pickRow, write }: { pickRow: PickRow; write: PickWrite }) {
  switch (pickRow.row.kind) {
    case 'slider':
      return <SliderPickRow pickRow={pickRow} heads={pickRow.heads as HeadsOf<'slider'>} write={write} />
    case 'virtual-dimmer':
      return <VirtualDimmerPickRow pickRow={pickRow} heads={pickRow.heads as HeadsOf<'virtual-dimmer'>} write={write} />
    case 'setting':
      return <SettingPickRow pickRow={pickRow} heads={pickRow.heads as HeadsOf<'setting'>} write={write} />
    case 'colour':
      return <ColourPickRow pickRow={pickRow} heads={pickRow.heads as HeadsOf<'colour'>} write={write} />
    case 'position':
      return <PositionPickRow pickRow={pickRow} heads={pickRow.heads as HeadsOf<'position'>} write={write} />
  }
})

/** Every channel a head's row covers — what a park on any of them makes the row read-only for. */
function rowChannels(row: SheetRow): ChannelRef[] {
  switch (row.kind) {
    case 'slider':
    case 'setting':
      return [row.property.channel]
    case 'colour':
    case 'virtual-dimmer': {
      const p = row.property
      return [p.redChannel, p.greenChannel, p.blueChannel, p.whiteChannel, p.amberChannel, p.uvChannel].filter(
        (c): c is ChannelRef => c != null,
      )
    }
    case 'position':
      return [row.resolution.pan, row.resolution.tilt]
  }
}

/** A park on any picked head's channels: the row is read-only, as a parked single row is (D2). */
function usePickParked(pickRow: PickRow): boolean {
  const { data: parks } = useGetParkStateListQuery()
  return useMemo(() => {
    if (!parks?.length) return false
    const parked = new Set(parks.map((p) => `${p.universe}:${p.channel}`))
    return pickRow.heads.some((h) => rowChannels(h.row).some((c) => parked.has(`${c.universe}:${c.channelNo}`)))
  }, [parks, pickRow])
}

/** The heads' own sources over the row's keys, and whether the row is read-only. */
function usePickRowState(pickRow: PickRow) {
  const { connected } = useFixtureSheet()
  const headKeys = useMemo(() => pickRow.heads.map((h) => h.key), [pickRow])
  const source = usePickSource(headKeys, pickRow.row.keys)
  const parked = usePickParked(pickRow)
  return { source, parked, readOnly: !connected || parked }
}

/** The pick's frame: the edge (dashed where the heads' sources differ), the name line and the control. */
function PickFrame({
  pickRow,
  write,
  source,
  readOnly,
  parked,
  trailing,
  note,
  children,
}: {
  pickRow: PickRow
  write: PickWrite
  source: PickSource
  readOnly: boolean
  parked: boolean
  trailing?: ReactNode
  /** A muted line after the name — `· 4 heads, 4 colours`. */
  note?: string | null
  children: ReactNode
}) {
  const { openRowId, connected } = useFixtureSheet()
  const finger = useFingerSized()
  const { row } = pickRow
  const name = row.label
  const heads = useMemo(() => pickRow.heads.map((h) => ({ key: h.key, name: h.name })), [pickRow])
  return (
    <div
      data-row={row.id}
      data-kind={row.kind}
      data-source={source.kind}
      data-mixed-source={source.mixed || undefined}
      data-heads={pickRow.heads.length}
      data-read-only={readOnly || undefined}
      data-parked={parked || undefined}
      className={cn(
        'relative flex flex-col gap-1.5 py-[7px] pr-3 pl-4',
        "before:absolute before:top-[7px] before:bottom-[7px] before:left-0 before:w-[3px] before:rounded-r-sm before:content-['']",
        source.mixed ? MIXED_EDGE_CLASS[source.kind] : SOURCE_EDGE_CLASS[source.kind],
        openRowId === openKey(PICK_HEAD, row) && 'bg-primary/[0.07]',
      )}
    >
      <div className="flex min-h-5 min-w-0 items-center gap-1.5">
        <span title={name} className={cn('min-w-0 truncate text-[12.5px] font-medium', source.kind === 'base' && 'opacity-60')}>
          {name}
        </span>
        <SourceChip
          source={source}
          heads={heads}
          group={write.kind === 'group' ? write.group : undefined}
          propertyName={row.keys[0]}
          label={name}
        />
        {note && <span className="min-w-0 truncate text-[10.5px] text-muted-foreground">· {note}</span>}
        <span className="flex-1" />
        {trailing}
        {source.holds && (
          <button
            type="button"
            data-row-clear
            aria-label={`Clear your ${name.toLowerCase()}`}
            title={`Clear your ${name.toLowerCase()} on ${pickRow.heads.length === 1 ? pickRow.heads[0].name : `these ${pickRow.heads.length}`} — it falls to what is underneath`}
            disabled={!connected}
            onClick={() => clearPickRow(write, row.keys, getProgrammerFadeMs())}
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

/**
 * The open-row key of a row over a pick. The pick's rows are one set whatever is picked, so one
 * stand-in head names them all — and a single picked head's own row (`PropertyRow`, keyed by its
 * head) is a different open row, so changing the pick closes the editor rather than moving it.
 */
const PICK_HEAD = '\u0001pick'

const lowHigh = (values: readonly number[]) =>
  values.length === 0 ? { low: 0, high: 0 } : { low: Math.min(...values), high: Math.max(...values) }

// ─── Slider ────────────────────────────────────────────────────────────────

function SliderPickRow({ pickRow, heads, write }: { pickRow: PickRow; heads: HeadsOf<'slider'>; write: PickWrite }) {
  const { source, parked, readOnly } = usePickRowState(pickRow)
  const property = heads[0].row.property
  const refs = useMemo(() => heads.map((h) => h.row.property.channel), [heads])
  const { low, high } = lowHigh(useChannelValues(refs))
  return (
    <PickFrame pickRow={pickRow} write={write} source={source} readOnly={readOnly} parked={parked}>
      <LevelControl
        label={pickRow.row.label}
        value={high}
        lowest={low}
        min={property.min}
        max={property.max}
        readOnly={readOnly}
        onDrag={(v) => writePickLevel(write, property.name, v)}
        onTyped={(v) => writePickLevel(write, property.name, v, getProgrammerFadeMs())}
      />
    </PickFrame>
  )
}

function VirtualDimmerPickRow({ pickRow, heads, write }: { pickRow: PickRow; heads: HeadsOf<'virtual-dimmer'>; write: PickWrite }) {
  const { source, parked, readOnly } = usePickRowState(pickRow)
  const refs = useMemo(() => heads.flatMap((h) => [h.row.property.redChannel, h.row.property.greenChannel, h.row.property.blueChannel]), [heads])
  const values = useChannelValues(refs)
  const levels = heads.map((_, i) => Math.max(values[i * 3] ?? 0, values[i * 3 + 1] ?? 0, values[i * 3 + 2] ?? 0))
  const { low, high } = lowHigh(levels)
  const targets = useMemo(() => heads.map((h) => ({ key: h.key, property: h.row.property })), [heads])
  return (
    <PickFrame pickRow={pickRow} write={write} source={source} readOnly={readOnly} parked={parked}>
      <LevelControl
        label={pickRow.row.label}
        value={high}
        lowest={low}
        min={0}
        max={255}
        readOnly={readOnly}
        onDrag={(v) => writePickVirtualDimmer(write, targets, v)}
        onTyped={(v) => writePickVirtualDimmer(write, targets, v, getProgrammerFadeMs())}
      />
    </PickFrame>
  )
}

// ─── Setting ───────────────────────────────────────────────────────────────

function SettingPickRow({ pickRow, heads, write }: { pickRow: PickRow; heads: HeadsOf<'setting'>; write: PickWrite }) {
  const { source, parked, readOnly } = usePickRowState(pickRow)
  const property = heads[0].row.property
  const refs = useMemo(() => heads.map((h) => h.row.property.channel), [heads])
  const levels = useChannelValues(refs)
  const cellValue = useMemo(() => {
    const options = heads.map((h, i) => resolveSettingOption(h.row.property.options, levels[i] ?? 0))
    const first = options[0]
    const isUniform = options.every((o) => o?.name === first?.name)
    return { kind: 'setting' as const, isUniform, level: levels[0] ?? 0, option: isUniform ? first : undefined }
  }, [heads, levels])
  const resolutions = useMemo(() => [{ kind: 'setting' as const, property }], [property])
  const onCommit = useCallback(
    (commit: CellCommit) => {
      if (commit.kind === 'setting') writePickLevel(write, property.name, commit.level, getProgrammerFadeMs())
    },
    [write, property.name],
  )
  const noop = useCallback(() => {}, [])
  const finger = useFingerSized()
  return (
    <PickFrame pickRow={pickRow} write={write} source={source} readOnly={readOnly} parked={parked}>
      <div className={cn('flex h-7 min-w-0 flex-1 items-center rounded-md border bg-background', finger && FINGER_HEIGHT_CLASS)}>
        <SettingCell value={cellValue} resolutions={resolutions} label={pickRow.row.label} disabled={readOnly} onCommit={onCommit} onBeginEdit={noop} />
      </div>
    </PickFrame>
  )
}

// ─── Colour ────────────────────────────────────────────────────────────────

/** One head's colour channels, then its dimmer's, as `useChannelValues` reads them. */
function colourRefs(property: ColourPropertyDescriptor, dimmer: SliderPropertyDescriptor | undefined): (ChannelRef | null)[] {
  return [
    property.redChannel,
    property.greenChannel,
    property.blueChannel,
    property.whiteChannel ?? null,
    property.amberChannel ?? null,
    property.uvChannel ?? null,
    dimmer?.channel ?? null,
  ]
}

const COLOUR_SLOTS = 7

/** How one head looks — `useColourAppearance`'s reading: colour × its dimmer, with the swatch floor. */
interface HeadColour {
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
  appearanceCss: string
  signature: string
}

/** `#ff8a3d` — the heads' one colour, where they agree; W / A / UV are in the editor's fields. */
const hex = (c: { r: number; g: number; b: number }) => `#${[c.r, c.g, c.b].map((v) => v.toString(16).padStart(2, '0')).join('')}`

function ColourPickRow({ pickRow, heads, write }: { pickRow: PickRow; heads: HeadsOf<'colour'>; write: PickWrite }) {
  const { openRowId, target } = useFixtureSheet()
  const noun = target.type === 'group' ? 'member' : 'head'
  const open = openRowId === openKey(PICK_HEAD, pickRow.row)
  const { source, parked, readOnly } = usePickRowState(pickRow)
  // A slot per channel a head could have, a dummy where it has none, so head i's values sit at a
  // fixed offset. The dummy reads channel 0 and is never looked at.
  const slots = useMemo(() => heads.flatMap((h) => colourRefs(h.row.property, h.row.dimmer)), [heads])
  const refs = useMemo(() => slots.map((c) => c ?? { universe: 0, channelNo: 0 }), [slots])
  const values = useChannelValues(refs)
  const colours = useMemo(
    () =>
      heads.map((h, i): HeadColour => {
        const at = (slot: number) => (slots[i * COLOUR_SLOTS + slot] != null ? (values[i * COLOUR_SLOTS + slot] ?? 0) : undefined)
        const r = at(0) ?? 0
        const g = at(1) ?? 0
        const b = at(2) ?? 0
        const w = at(3)
        const a = at(4)
        const uv = at(5)
        const dimmer = at(6)
        const level = (dimmer == null ? 1 : Math.max(0, Math.min(1, dimmer / 255))) * colourFactor(r, g, b, w, a, uv)
        return {
          r,
          g,
          b,
          w,
          a,
          uv,
          appearanceCss: computeAppearanceCss(r, g, b, w, a, uv, perceptualBrightness(level, SWATCH_FLOOR)),
          signature: [r, g, b, w, a, uv].join(','),
        }
      }),
    [heads, slots, values],
  )
  const distinct = new Set(colours.map((c) => c.signature)).size
  // The editor's seed: the first head's colour, each emitter from the first head that has one — so
  // a pick whose first head has no white still shows, and keeps, the others' white.
  const first = useMemo(() => {
    const has = (pick: (c: HeadColour) => number | undefined) => colours.map(pick).find((v) => v !== undefined)
    return { ...colours[0], w: has((c) => c.w), a: has((c) => c.a), uv: has((c) => c.uv) }
  }, [colours])
  const targets = useMemo(() => heads.map((h) => ({ key: h.key, property: h.row.property })), [heads])
  const update = useCallback(
    (r: number, g: number, b: number, w?: number, a?: number, uv?: number) => writePickColour(write, targets, { r, g, b, w, a, uv }),
    [write, targets],
  )
  const anyHas = (channel: 'whiteChannel' | 'amberChannel' | 'uvChannel') => heads.some((h) => h.row.property[channel] != null)
  const note = `${heads.length} ${noun}${heads.length === 1 ? '' : 's'}${distinct > 1 ? `, ${distinct} colours` : ''}`
  return (
    <PickFrame
      pickRow={pickRow}
      write={write}
      source={source}
      readOnly={readOnly}
      parked={parked}
      note={note}
      trailing={<OpenToggle row={pickRow.row} headKey={PICK_HEAD} label={pickRow.row.label} />}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div className="flex h-7 min-w-0 items-center gap-2">
          {/* The heads as a strip of swatches, each how it looks (note 4). */}
          <span data-testid="colour-strip" className="flex h-7 min-w-0 flex-1 overflow-hidden rounded-md border">
            {colours.map((c, i) => (
              <span key={heads[i].key} className="min-w-0 flex-1" style={{ backgroundColor: c.appearanceCss }} title={heads[i].name} />
            ))}
          </span>
          <span className="w-[76px] shrink-0 truncate rounded-md border bg-background px-2 py-1 font-mono text-xs text-muted-foreground">
            {distinct > 1 ? 'mixed' : hex(first)}
          </span>
        </div>
        {open && (
          // The same docked editor a single head opens; its first colour seeds the picker, and what
          // is set lands on every picked head.
          <fieldset disabled={readOnly} className="min-w-0" data-testid="colour-editor">
            <ColourEditor
              r={first.r}
              g={first.g}
              b={first.b}
              w={first.w}
              a={first.a}
              uv={first.uv}
              combinedCss={computeCombinedCss(first.r, first.g, first.b, first.w, first.a, first.uv)}
              hasWhiteChannel={anyHas('whiteChannel')}
              hasAmberChannel={anyHas('amberChannel')}
              hasUvChannel={anyHas('uvChannel')}
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
    </PickFrame>
  )
}

// ─── Position ──────────────────────────────────────────────────────────────

const norm = (byte: number, min: number, max: number) => (byte - min) / Math.max(1, max - min)

/** An axis over the heads: `90°`, or `10°–90°` — each head in its own degrees where they all annotate them. */
function rangeText(bytes: readonly number[], sliders: readonly (SliderPropertyDescriptor | undefined)[], degrees: boolean): string {
  const degs = degrees ? bytes.map((b, i) => (sliders[i] ? dmxToDegrees(b, sliders[i]) : null)) : []
  if (degrees && degs.every((d): d is number => d != null)) {
    const { low, high } = lowHigh(degs.map(Math.round))
    return low === high ? `${low}°` : `${low}°–${high}°`
  }
  const { low, high } = lowHigh(bytes)
  return low === high ? String(low) : `${low}–${high}`
}

function PositionPickRow({ pickRow, heads, write }: { pickRow: PickRow; heads: HeadsOf<'position'>; write: PickWrite }) {
  const { openRowId } = useFixtureSheet()
  const open = openRowId === openKey(PICK_HEAD, pickRow.row)
  const { source, parked, readOnly } = usePickRowState(pickRow)
  const refs = useMemo(() => heads.flatMap((h) => [h.row.resolution.pan, h.row.resolution.tilt]), [heads])
  const values = useChannelValues(refs)
  const pans = heads.map((_, i) => values[i * 2] ?? 0)
  const tilts = heads.map((_, i) => values[i * 2 + 1] ?? 0)
  // Degrees only where every picked head annotates both axes; a typed degree is resolved per head.
  const degrees = heads.every((h) => h.row.degrees)
  const lead = heads[0].row.resolution
  const targets = useMemo(() => heads.map((h) => ({ key: h.key, resolution: h.row.resolution })), [heads])
  const write1 = (commit: Extract<CellCommit, { kind: 'position' }> | ((r: PositionResolution) => Extract<CellCommit, { kind: 'position' }>), typed: boolean) =>
    writePickPosition(write, targets, commit, typed ? getProgrammerFadeMs() : undefined)
  const panDeg = degrees && lead.panProperty ? dmxToDegrees(pans[0], lead.panProperty) : null
  const tiltDeg = degrees && lead.tiltProperty ? dmxToDegrees(tilts[0], lead.tiltProperty) : null
  const mixed = new Set(pans).size > 1 || new Set(tilts).size > 1
  return (
    <PickFrame
      pickRow={pickRow}
      write={write}
      source={source}
      readOnly={readOnly}
      parked={parked}
      trailing={<OpenToggle row={pickRow.row} headKey={PICK_HEAD} label={pickRow.row.label} />}
    >
      <div className="flex min-w-0 flex-1 items-start gap-2">
        <div className="relative shrink-0">
          <PositionPad
            size={open ? 120 : 64}
            pan={norm(pans[0], lead.panMin, lead.panMax)}
            tilt={norm(tilts[0], lead.tiltMin, lead.tiltMax)}
            readOnly={readOnly}
            // The point lands on each head's own range, so a pick of two models moves alike.
            onMove={(x, y) =>
              write1(
                (r) => ({
                  kind: 'position',
                  pan: Math.round(r.panMin + x * (r.panMax - r.panMin)),
                  tilt: Math.round(r.tiltMin + y * (r.tiltMax - r.tiltMin)),
                }),
                false,
              )
            }
          />
          {/* Every other picked head as a hollow dot, so a spread pick reads as one. */}
          {heads.slice(1).map((h, i) => (
            <span
              key={h.key}
              aria-hidden
              data-testid="position-head-dot"
              className="pointer-events-none absolute size-2 -translate-x-1/2 -translate-y-1/2 rounded-full border border-primary"
              style={{
                left: `calc(5px + (100% - 10px) * ${Math.max(0, Math.min(1, norm(pans[i + 1], h.row.resolution.panMin, h.row.resolution.panMax)))})`,
                top: `calc(5px + (100% - 10px) * ${Math.max(0, Math.min(1, norm(tilts[i + 1], h.row.resolution.tiltMin, h.row.resolution.tiltMax)))})`,
              }}
            />
          ))}
        </div>
        {open ? (
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <AxisControl
              label="Pan"
              byte={pans[0]}
              deg={panDeg}
              min={lead.panMin}
              max={lead.panMax}
              slider={lead.panProperty}
              readOnly={readOnly}
              onCommit={(c, typed) => write1(c, typed)}
              axis="pan"
            />
            <AxisControl
              label="Tilt"
              byte={tilts[0]}
              deg={tiltDeg}
              min={lead.tiltMin}
              max={lead.tiltMax}
              slider={lead.tiltProperty}
              readOnly={readOnly}
              onCommit={(c, typed) => write1(c, typed)}
              axis="tilt"
            />
            {mixed && <p className="text-[10.5px] text-muted-foreground">The heads differ; a value set here lands on all {heads.length}.</p>}
          </div>
        ) : (
          <div className="flex min-w-0 flex-1 flex-col gap-0.5 pt-1 font-mono text-xs">
            <span>
              <span className="mr-1.5 text-[9px] font-bold tracking-wider text-muted-foreground uppercase">Pan</span>
              {rangeText(pans, heads.map((h) => h.row.resolution.panProperty), degrees)}
            </span>
            <span>
              <span className="mr-1.5 text-[9px] font-bold tracking-wider text-muted-foreground uppercase">Tilt</span>
              {rangeText(tilts, heads.map((h) => h.row.resolution.tiltProperty), degrees)}
            </span>
          </div>
        )}
      </div>
    </PickFrame>
  )
}

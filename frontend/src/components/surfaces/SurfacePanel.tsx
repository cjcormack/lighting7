import { memo, useMemo } from "react"
import { useDroppable } from "@dnd-kit/core"
import { X } from "lucide-react"
import { cn } from "@/lib/utils"
import type {
  BankButtonControl,
  ButtonControl,
  ControlDescriptor,
  ControlState,
  ControlSurfaceBinding,
  ControlSurfaceType,
  EncoderControl,
  FaderControl,
  LayoutRegion,
  PickupChange,
  StripDefinition,
  EncoderBankSelection,
} from "@/store/surfaces"
import type { ResolvedControl, SurfaceBindingIndex } from "@/lib/surfaceResolve"
import { activeBindingAt, resolveControl } from "@/lib/surfaceResolve"
import { canLand, controlKinds, type SurfaceDragData } from "@/lib/surfaceDrop"
import { describeBindingTarget, type RecordBindingOptions } from "./recordOptions"
import {
  FADER_FRAME_HEIGHT,
  RING_DOTS,
  columnStrips,
  faderCapTop,
  faderFillHeight,
  knobPointer,
  midiPercent,
  regionRowCount,
  ringDotIndex,
  ringDotPosition,
} from "./surfacePanelGeometry"

/**
 * The attached surface, drawn as itself.
 *
 * Two things make this a picture rather than a table, and both are deliberate. The **arrangement
 * is profile data** (`ControlSurfaceType.layout`), so a second device is one `.kt` file in
 * lighting7 and no component here; and every state a control shows comes from the
 * `surfaceControls` stream, which is **what the hardware was told** — never a recomputation from
 * DMX. If the picture and the desk disagree, the publisher is wrong, and that is the bug worth
 * finding rather than papering over here (plan D7).
 *
 * What a control *is* comes from `lib/surfaceResolve.ts`, not from the binding list directly: a
 * strip is one row covering four controls, so the label under a fader is derived, and a control
 * with its own binding beats the strip it sits on.
 */

export interface SurfacePanelProps {
  profile: ControlSurfaceType
  /** This device's controls, keyed by `controlId`. */
  controls: Readonly<Record<string, ControlState>>
  index: SurfaceBindingIndex
  activeBank: string | null
  encoderBank: EncoderBankSelection
  /** Soft-takeover state for this device, keyed by `controlId`. */
  pickups: Readonly<Record<string, PickupChange>>
  selectedControlId: string | null
  onSelectControl: (controlId: string) => void
  /** *Edit bindings* is on: controls take drops and bound rows grow a remove cross. */
  editing: boolean
  /** The surface drag in flight, or null — what decides which droppables are live. */
  lifted: SurfaceDragData | null
  onRemoveBinding: (bindingId: number) => void
  /** Resolves a record-addressed binding's uuid to a name — see `SurfaceInspector`'s `describe`. */
  records: RecordBindingOptions
}

const UNBOUND: ControlState = {
  value: null,
  physical: null,
  touched: false,
  led: "none",
  ring: "none",
}

export function SurfacePanel({
  profile,
  controls,
  index,
  activeBank,
  encoderBank,
  pickups,
  selectedControlId,
  onSelectControl,
  editing,
  lifted,
  onRemoveBinding,
  records,
}: SurfacePanelProps) {
  const byControlId = useMemo(() => {
    const map = new Map<string, ControlDescriptor>()
    for (const control of profile.controls) map.set(control.controlId, control)
    return map
  }, [profile])

  if (!profile.layout) return null

  return (
    <div
      className="inline-flex items-start gap-3 rounded-2xl border border-surface-panel-edge bg-surface-panel p-3"
      data-testid="surface-panel"
    >
      {profile.layout.regions.map((region) => (
        <PanelRegion
          key={region.name}
          region={region}
          byControlId={byControlId}
          controls={controls}
          index={index}
          activeBank={activeBank}
          encoderBank={encoderBank}
          pickups={pickups}
          selectedControlId={selectedControlId}
          onSelectControl={onSelectControl}
          editing={editing}
          lifted={lifted}
          onRemoveBinding={onRemoveBinding}
          records={records}
        />
      ))}
    </div>
  )
}

interface RegionProps extends Omit<SurfacePanelProps, "profile"> {
  region: LayoutRegion
  byControlId: Map<string, ControlDescriptor>
}

function PanelRegion({
  region,
  byControlId,
  controls,
  index,
  activeBank,
  encoderBank,
  pickups,
  selectedControlId,
  onSelectControl,
  editing,
  lifted,
  onRemoveBinding,
  records,
}: RegionProps) {
  // A column whose controls all belong to one strip gets a backdrop spanning the whole column —
  // the thing session 3b outlines when a group row is dragged over it. Derived from the strips
  // rather than from the region's name, so the right-hand block correctly gets none.
  const strips = useMemo(
    () => columnStrips(region, index.stripControls),
    [region, index.stripControls],
  )
  const rows = regionRowCount(region)

  // Resolved once per region rather than inline per cell, and this is what makes `ControlCell`'s
  // memo actually skip: resolving in the map would mint a fresh `ResolvedControl` for every
  // control on every frame, so no cell's props would ever compare equal and a fader drag would
  // re-render the whole panel at 20 Hz — the exact cost the memo exists to avoid. Bindings and
  // the two bank values change rarely, so this map is stable across the frames that matter.
  const resolved = useMemo(() => {
    const map = new Map<string, ResolvedControl | null>()
    for (const cell of region.cells) {
      map.set(cell.controlId, resolveControl(cell.controlId, index, activeBank, encoderBank))
    }
    return map
  }, [region, index, activeBank, encoderBank])

  return (
    <div
      className="grid gap-1.5"
      style={{
        gridTemplateColumns: `repeat(${region.columns}, minmax(0, 1fr))`,
        gridTemplateRows: `repeat(${rows}, auto)`,
      }}
      data-region={region.name}
    >
      {[...strips.entries()].map(([col, strip]) => (
        <StripZone
          key={`strip-${strip.id}`}
          strip={strip}
          col={col}
          rows={rows}
          editing={editing}
          lifted={lifted}
          // `activeBindingAt`, not `exactBindingAt`: this is the *reading* question, and a strip
          // driven by a bank-agnostic row is drawn on every bank — so a cross that only saw the
          // exact-bank row would leave a visibly bound strip with no way to unbind it.
          binding={activeBindingAt(index, strip.id, activeBank)}
          onRemoveBinding={onRemoveBinding}
        />
      ))}
      {region.cells.map((cell) => {
        const descriptor = byControlId.get(cell.controlId)
        if (!descriptor) return null
        const hit = resolved.get(cell.controlId) ?? null
        return (
          <ControlZone
            key={cell.controlId}
            descriptor={descriptor}
            col={cell.col}
            row={cell.row}
            editing={editing}
            lifted={lifted}
            // Only a row the *control itself* owns can be crossed off here; a strip's row is
            // crossed off on the strip, where one cross means one row rather than four controls.
            ownBinding={hit?.via == null ? (hit?.binding ?? null) : null}
            onRemoveBinding={onRemoveBinding}
          >
            <ControlCell
              descriptor={descriptor}
              resolved={hit}
              state={controls[cell.controlId] ?? UNBOUND}
              pickup={pickups[cell.controlId]}
              activeBank={activeBank}
              selected={selectedControlId === cell.controlId}
              onSelect={onSelectControl}
              records={records}
            />
          </ControlZone>
        )
      })}
    </div>
  )
}

/**
 * The grid cell around one control: a plain wrapper in run mode, a drop zone while editing.
 *
 * **The droppable never lives inside `ControlCell`.** That cell is memoized precisely so a 20 Hz
 * `surfaceControls` delta re-renders only the controls that moved, and `useDroppable` re-renders
 * its host every time `isOver` flips — inside, a hover would cost the whole panel.
 *
 * Out here it costs something too, and that is why the two modes are **two components rather than
 * one with a `disabled` flag**: `PanelRegion` reads `controls[…]` and so re-renders on every frame,
 * which would re-run `useDroppable` for every control on the device sixty times a second while
 * nobody is dragging anything. Run mode is the state the panel spends its life in, and there is no
 * drop target in it at all, so it renders no hook. Switching modes remounts the wrappers, which is
 * what a mode switch is.
 */
function ControlZone(props: ControlZoneProps) {
  return props.editing ? <EditableControlZone {...props} /> : <PlainControlZone {...props} />
}

interface ControlZoneProps {
  descriptor: ControlDescriptor
  col: number
  row: number
  editing: boolean
  lifted: SurfaceDragData | null
  ownBinding: ControlSurfaceBinding | null
  onRemoveBinding: (bindingId: number) => void
  children: React.ReactNode
}

function PlainControlZone({ col, row, children }: ControlZoneProps) {
  return (
    <div style={{ gridColumn: col + 1, gridRow: row + 1 }} className="relative min-w-0">
      {children}
    </div>
  )
}

/**
 * `disabled` is dnd-kit's own, and it is the half `canLand` cannot do: it keeps `over` — and so the
 * highlight — off a place the drop would refuse, rather than showing the operator a landing that
 * then does nothing.
 */
function EditableControlZone({
  descriptor,
  col,
  row,
  lifted,
  ownBinding,
  onRemoveBinding,
  children,
}: ControlZoneProps) {
  const kinds = useMemo(() => controlKinds(descriptor), [descriptor])
  const drop = useMemo(
    () => ({ type: "surface-control" as const, controlId: descriptor.controlId, kinds }),
    [descriptor.controlId, kinds],
  )
  const eligible = lifted != null && canLand(lifted, drop)
  const { setNodeRef, isOver } = useDroppable({
    id: `surface-control:${descriptor.controlId}`,
    data: drop,
    disabled: !eligible,
  })

  return (
    <div
      ref={setNodeRef}
      style={{ gridColumn: col + 1, gridRow: row + 1 }}
      className={cn(
        "relative min-w-0 rounded-lg",
        // A chip in flight that cannot land here says so by receding — nothing on the backend
        // refuses a cue on a fader, so this dim is the whole warning.
        lifted?.type === "surface-chip" && !eligible && "opacity-40",
        isOver && "bg-primary/10 outline-2 outline-offset-2 outline-dashed outline-primary",
      )}
    >
      {children}
      {ownBinding && (
        <RemoveCross
          label={`Remove the binding on ${descriptor.label}`}
          onClick={() => onRemoveBinding(ownBinding.id)}
        />
      )}
    </div>
  )
}

/**
 * The column backdrop behind a strip: its drop zone while a row is lifted, and where its one row
 * is crossed off.
 *
 * Split by mode for `ControlZone`'s reason — nine strips is nine fewer per-frame `useDroppable`
 * calls in run mode, where nothing can be dropped anyway.
 *
 * `pointer-events-none` on the backdrop so it never steals a click from the controls painted over
 * it — dnd-kit measures rects rather than hit-testing, so the droppable is unaffected — with the
 * cross opting back in.
 */
function StripZone(props: StripZoneProps) {
  if (!props.editing) {
    return (
      <div
        className="pointer-events-none rounded-[10px]"
        style={{ gridColumn: props.col + 1, gridRow: `1 / ${props.rows + 1}` }}
        data-strip={props.strip.id}
      />
    )
  }
  return <EditableStripZone {...props} />
}

interface StripZoneProps {
  strip: StripDefinition
  col: number
  rows: number
  editing: boolean
  lifted: SurfaceDragData | null
  binding: ControlSurfaceBinding | null
  onRemoveBinding: (bindingId: number) => void
}

function EditableStripZone({
  strip,
  col,
  rows,
  lifted,
  binding,
  onRemoveBinding,
}: StripZoneProps) {
  const { setNodeRef, isOver } = useDroppable({
    id: `surface-strip:${strip.id}`,
    data: { type: "surface-strip", stripId: strip.id },
    disabled: lifted?.type !== "surface-row",
  })

  return (
    <div
      ref={setNodeRef}
      className={cn(
        "pointer-events-none relative rounded-[10px]",
        isOver && "bg-primary/10 outline-2 outline-dashed outline-primary",
      )}
      style={{ gridColumn: col + 1, gridRow: `1 / ${rows + 1}` }}
      data-strip={strip.id}
    >
      {binding && (
        <RemoveCross
          label={`Remove the ${strip.id} binding`}
          onClick={() => onRemoveBinding(binding.id)}
        />
      )}
    </div>
  )
}

/** The busk pad's remove cross, at the top-left corner of whatever it is placed in. */
function RemoveCross({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="pointer-events-auto absolute -top-1.5 -left-1.5 z-10 grid size-[18px] place-items-center rounded-full border bg-muted text-muted-foreground hover:bg-destructive hover:text-destructive-foreground"
    >
      <X className="size-2.5" />
    </button>
  )
}

interface ControlCellProps {
  descriptor: ControlDescriptor
  resolved: ResolvedControl | null
  state: ControlState
  pickup: PickupChange | undefined
  activeBank: string | null
  selected: boolean
  onSelect: (controlId: string) => void
  records: RecordBindingOptions
}

/**
 * Memoized on purpose: a `surfaceControls.changed` delta names only the controls that moved, and
 * the WS layer merges rather than rebuilds, so every untouched control's `state` keeps its
 * identity and skips the render. Without that pairing a fader drag re-renders the whole panel at
 * 20 Hz. `records` is safe alongside that: it is a `useRecordBindingOptions` result, stable across
 * every frame that only moves a channel value, and only changes identity when the Look/template/
 * busk-page libraries themselves do.
 */
const ControlCell = memo(function ControlCell({
  descriptor,
  resolved,
  state,
  pickup,
  activeBank,
  selected,
  onSelect,
  records,
}: ControlCellProps) {
  const dead = resolved != null && resolved.binding.health.type !== "ok"
  const label = resolved ? describeBindingTarget(resolved.target, records) : null
  const common = { descriptor, state, label, dead, selected, onSelect }

  switch (descriptor.type) {
    case "fader":
      return <FaderCell {...common} descriptor={descriptor} pickup={pickup} />
    case "encoder":
      return <EncoderCell {...common} descriptor={descriptor} />
    case "button":
      return <ButtonCell {...common} descriptor={descriptor} />
    case "bankButton":
      return <BankButtonCell {...common} descriptor={descriptor} activeBank={activeBank} />
  }
})

interface CellCommon {
  state: ControlState
  label: string | null
  dead: boolean
  selected: boolean
  onSelect: (controlId: string) => void
}

/** The binding's short label under a control — `—` dashed when unbound, red when dead. */
function TargetLabel({ label, dead }: { label: string | null; dead: boolean }) {
  return (
    <div
      className={cn(
        "min-h-6 overflow-hidden text-center text-[10px] leading-3",
        label == null && "text-muted-foreground/60",
        dead && "text-destructive",
      )}
      style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}
    >
      {label ?? "—"}
    </div>
  )
}

function ControlName({ children }: { children: string }) {
  return (
    <div className="text-[8px] font-bold uppercase leading-3 tracking-[0.04em] text-muted-foreground">
      {children}
    </div>
  )
}

/** Everything a control shares: the click target, and the ring drawn around the inspected one. */
function CellShell({
  controlId,
  title,
  selected,
  onSelect,
  className,
  children,
}: {
  controlId: string
  title: string
  selected: boolean
  onSelect: (controlId: string) => void
  className?: string
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      title={title}
      aria-pressed={selected}
      onClick={() => onSelect(controlId)}
      className={cn(
        "relative w-full rounded-lg text-left",
        selected && "outline-2 outline-offset-2 outline-ring",
        className,
      )}
    >
      {children}
    </button>
  )
}

function FaderCell({
  descriptor,
  state,
  label,
  dead,
  selected,
  onSelect,
  pickup,
}: CellCommon & { descriptor: FaderControl; pickup: PickupChange | undefined }) {
  // A non-motor fader is never told its own position, so `physical` is the only truthful place to
  // draw the cap on one; a motor fader's `value` is both what it was told and where it is.
  const capValue = state.physical ?? state.value
  const awaiting = pickup?.state === "AWAITING_PICKUP" ? pickup.target : null

  return (
    <CellShell
      controlId={descriptor.controlId}
      title={descriptor.label}
      selected={selected}
      onSelect={onSelect}
    >
      <div className="flex flex-col items-center gap-1 px-0 pb-0.5 pt-1">
        <div className="h-3 font-mono text-[11px] leading-3 text-muted-foreground tabular-nums">
          {capValue == null ? "" : `${midiPercent(capValue)}%`}
        </div>
        <div className="relative w-[34px]" style={{ height: FADER_FRAME_HEIGHT }}>
          <div className="absolute inset-y-1.5 left-[15px] w-1 rounded-sm bg-surface-track" />
          {capValue != null && (
            <div
              className="absolute bottom-1.5 left-[15px] w-1 rounded-sm bg-primary"
              style={{ height: faderFillHeight(capValue) }}
            />
          )}
          {awaiting != null && (
            <>
              <div
                className="absolute left-[5px] -mt-2 h-4 w-6 rounded-[3px] border-2 border-dashed border-[var(--editor-warning)]"
                style={{ top: faderCapTop(awaiting) }}
              />
              <div
                className="absolute inset-x-0 -mt-1 text-center text-[8px] leading-2 text-[var(--editor-warning)]"
                style={{ top: faderCapTop(awaiting) - 16 }}
              >
                {midiPercent(awaiting)}%
              </div>
            </>
          )}
          {capValue != null && (
            <div
              className={cn(
                "absolute left-[5px] -mt-2 h-4 w-6 rounded-[3px] bg-surface-cap shadow-sm",
                state.touched && "outline-2 outline-ring",
              )}
              style={{ top: faderCapTop(capValue) }}
            >
              <div className="absolute inset-x-[3px] top-[7px] h-0.5 rounded-full bg-surface-cap-line" />
            </div>
          )}
        </div>
        <TargetLabel label={label} dead={dead} />
        <ControlName>{descriptor.label}</ControlName>
      </div>
    </CellShell>
  )
}

function EncoderCell({
  descriptor,
  state,
  label,
  dead,
  selected,
  onSelect,
}: CellCommon & { descriptor: EncoderControl }) {
  // `ring: "on"` is the only state that lights a dot. `off` is the shared rendering of mixed,
  // unbound and no-selection — three different reasons the desk has nothing to show, and the
  // hardware draws all three the same way, so the picture does too.
  const lit = state.ring === "on" && state.value != null ? ringDotIndex(state.value) : null
  const pointer = lit == null ? null : knobPointer(lit)

  return (
    <CellShell
      controlId={descriptor.controlId}
      title={descriptor.label}
      selected={selected}
      onSelect={onSelect}
    >
      <div className="flex flex-col items-center gap-0.5 px-0.5 py-0.5">
        <svg width="44" height="44" viewBox="0 0 44 44" aria-hidden>
          {Array.from({ length: RING_DOTS }, (_, i) => {
            const { x, y } = ringDotPosition(i)
            const on = i === lit
            return (
              <circle
                key={i}
                cx={x}
                cy={y}
                r={1.6}
                fill={on ? "var(--surface-led)" : "var(--surface-led-off)"}
                style={on ? { filter: "drop-shadow(0 0 3px var(--surface-led))" } : undefined}
              />
            )
          })}
          <circle
            cx={22}
            cy={22}
            r={13}
            fill="var(--surface-knob)"
            stroke="var(--surface-knob-edge)"
            strokeWidth={1}
          />
          {pointer && (
            <line
              x1={22}
              y1={22}
              x2={pointer.x}
              y2={pointer.y}
              stroke="var(--surface-cap-line)"
              strokeWidth={2}
              strokeLinecap="round"
            />
          )}
          {/* An encoder's LED is its push switch's: the ring is reported separately. */}
          {state.led === "on" && (
            <circle cx={22} cy={22} r={3} fill="var(--surface-led)" />
          )}
        </svg>
        <TargetLabel label={label} dead={dead} />
        <ControlName>{descriptor.label}</ControlName>
      </div>
    </CellShell>
  )
}

/** The 3px LED bar every button carries at its top edge, lit / dark / absent. */
function LedBar({ led }: { led: ControlState["led"] }) {
  return (
    <span
      className={cn(
        "absolute inset-x-2 top-0 h-[3px] rounded-b-[3px]",
        led === "on"
          ? "bg-surface-led shadow-[0_0_6px_var(--surface-led)]"
          : "bg-surface-led-off",
      )}
    />
  )
}

function ButtonFace({
  text,
  state,
  bound,
  dead,
  selected,
  controlId,
  title,
  onSelect,
}: {
  text: string
  state: ControlState
  bound: boolean
  dead: boolean
  selected: boolean
  controlId: string
  title: string
  onSelect: (controlId: string) => void
}) {
  return (
    <CellShell
      controlId={controlId}
      title={title}
      selected={selected}
      onSelect={onSelect}
      className={cn(
        "flex h-[30px] flex-col items-center justify-center rounded-md border px-1.5 pt-0.5",
        bound ? "border-surface-panel-edge bg-card" : "border-dashed border-surface-panel-edge bg-transparent",
        dead && "border-destructive",
      )}
    >
      <LedBar led={state.led} />
      <span
        className={cn(
          "max-w-full truncate text-[10px] leading-[11px]",
          !bound && "text-muted-foreground/60",
          dead && "text-destructive",
        )}
      >
        {text}
      </span>
    </CellShell>
  )
}

function ButtonCell({
  descriptor,
  state,
  label,
  dead,
  selected,
  onSelect,
}: CellCommon & { descriptor: ButtonControl }) {
  return (
    <ButtonFace
      controlId={descriptor.controlId}
      title={descriptor.label}
      text={label ?? "—"}
      state={state}
      bound={label != null}
      dead={dead}
      selected={selected}
      onSelect={onSelect}
    />
  )
}

/**
 * A bank button is the profile's, never a binding's: it names the bank it switches to, and lights
 * while that bank is active.
 *
 * It draws its own label **whatever row sits on its control id**, because a binding there can never
 * fire: `SurfaceInputRouter.route` matches a bank button to `ResolvedInput.BankButton` and switches
 * the bank before resolving anything — "bank buttons short-circuit binding resolution", in its own
 * words. Showing a bound target here (as this cell did) promised a press that the desk does not
 * make; `controlKinds` returns `[]` for one so the library will not offer it either, and the
 * inspector says so for a row an older build or a hand edit left behind.
 */
function BankButtonCell({
  descriptor,
  state,
  label,
  selected,
  onSelect,
  activeBank,
}: CellCommon & { descriptor: BankButtonControl; activeBank: string | null }) {
  const isActiveBank = activeBank === descriptor.bankId
  // A row that resolves here is one an older build or a hand edit left behind. It is drawn as
  // **dead** rather than hidden: the picture's whole promise is that it mirrors the desk, and an
  // orphaned row that only the inspector mentions is one an operator scanning the surface would
  // never find. The *label* stays the bank's, because that is still what the press does.
  const orphaned = label != null
  return (
    <ButtonFace
      controlId={descriptor.controlId}
      title={
        orphaned
          ? `${descriptor.label} · bank ${descriptor.bankId} — holds a binding that can never fire`
          : `${descriptor.label} · bank ${descriptor.bankId}`
      }
      text={descriptor.label}
      state={{ ...state, led: isActiveBank ? "on" : "off" }}
      bound
      dead={orphaned}
      selected={selected}
      onSelect={onSelect}
    />
  )
}

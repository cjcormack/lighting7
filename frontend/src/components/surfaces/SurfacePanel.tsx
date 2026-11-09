import { memo, useMemo } from "react"
import { cn } from "@/lib/utils"
import type {
  BankButtonControl,
  ButtonControl,
  ControlDescriptor,
  ControlState,
  ControlSurfaceType,
  EncoderControl,
  FaderControl,
  LayoutRegion,
  PickupChange,
  ResolvedPart,
} from "@/store/surfaces"
import { colourVar, FIXTURE_COLOUR_LABELS, type FixtureColour } from "@/lib/fixtureColours"
import {
  FADER_FRAME_HEIGHT,
  RING_DOTS,
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
 * DMX (plan D7).
 *
 * What a control *does* — its label, its colour, whether it is dead — is the desk's served map
 * (`surfaceMap.state`, surface-setup plan D15), read for the layer being viewed. Nothing here
 * resolves a binding.
 */

export interface SurfacePanelProps {
  profile: ControlSurfaceType
  /** This device's controls, keyed by `controlId`. */
  controls: Readonly<Record<string, ControlState>>
  /** The viewed layer's parts, keyed `controlId:PART`. */
  parts: Readonly<Record<string, ResolvedPart>>
  /** The layer being viewed, which a layer button lights. */
  viewedLayer: string | null
  /**
   * The viewed layer is not the one the device is on, so the live state drawn — positions, LEDs —
   * is the other layer's: drawn dimmed (D13).
   */
  dimmed?: boolean
  /** Soft-takeover state for this device, keyed by `controlId`. */
  pickups: Readonly<Record<string, PickupChange>>
  selectedControlId: string | null
  onSelectControl: (controlId: string) => void
  /** The controls of the job region being edited, lit on the picture (Editor B). */
  highlighted?: ReadonlySet<string>
}

const UNBOUND: ControlState = {
  value: null,
  physical: null,
  touched: false,
  led: "none",
  ring: "none",
}

const NO_PART: ResolvedPart = { from: "NONE" }

export function SurfacePanel({
  profile,
  controls,
  parts,
  viewedLayer,
  dimmed = false,
  pickups,
  selectedControlId,
  onSelectControl,
  highlighted,
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
      data-dimmed={dimmed || undefined}
    >
      {profile.layout.regions.map((region) => (
        <PanelRegion
          key={region.name}
          region={region}
          byControlId={byControlId}
          controls={controls}
          parts={parts}
          viewedLayer={viewedLayer}
          dimmed={dimmed}
          pickups={pickups}
          selectedControlId={selectedControlId}
          onSelectControl={onSelectControl}
          highlighted={highlighted}
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
  parts,
  viewedLayer,
  dimmed = false,
  pickups,
  selectedControlId,
  onSelectControl,
  highlighted,
}: RegionProps) {
  const rows = regionRowCount(region)
  return (
    <div
      className="grid gap-1.5"
      style={{
        gridTemplateColumns: `repeat(${region.columns}, minmax(0, 1fr))`,
        gridTemplateRows: `repeat(${rows}, auto)`,
      }}
      data-region={region.name}
    >
      {region.cells.map((cell) => {
        const descriptor = byControlId.get(cell.controlId)
        if (!descriptor) return null
        return (
          <div
            key={cell.controlId}
            style={{ gridColumn: cell.col + 1, gridRow: cell.row + 1 }}
            className={cn("relative min-w-0 rounded-md", highlighted?.has(cell.controlId) && "bg-primary/15 ring-1 ring-primary/40")}
            data-in-job={highlighted?.has(cell.controlId) || undefined}
          >
            <ControlCell
              descriptor={descriptor}
              main={parts[`${cell.controlId}:MAIN`] ?? NO_PART}
              push={parts[`${cell.controlId}:PUSH`] ?? NO_PART}
              state={controls[cell.controlId] ?? UNBOUND}
              pickup={pickups[cell.controlId]}
              dimmed={dimmed}
              viewedLayer={viewedLayer}
              selected={selectedControlId === cell.controlId}
              onSelect={onSelectControl}
            />
          </div>
        )
      })}
    </div>
  )
}

interface ControlCellProps {
  descriptor: ControlDescriptor
  main: ResolvedPart
  push: ResolvedPart
  state: ControlState
  pickup: PickupChange | undefined
  /** The live state is another layer's: drawn faint. */
  dimmed: boolean
  viewedLayer: string | null
  selected: boolean
  onSelect: (controlId: string) => void
}

/** A part's label as the panel draws it, or null for a part nothing is bound to. */
function partLabel(part: ResolvedPart): string | null {
  return part.from === "NONE" ? null : (part.label ?? "—")
}

/**
 * Memoized on purpose: a `surfaceControls.changed` delta names only the controls that moved, and
 * the WS layer merges rather than rebuilds, so every untouched control's `state` keeps its
 * identity and skips the render. A map frame keeps an unchanged part's identity only within one
 * frame, which arrives at operator rate, never at 20 Hz.
 */
const ControlCell = memo(function ControlCell({
  descriptor,
  main,
  push,
  state,
  pickup,
  dimmed,
  viewedLayer,
  selected,
  onSelect,
}: ControlCellProps) {
  const shown = main.from === "NONE" && push.from !== "NONE" ? push : main
  const pushOnly = shown === push && push !== main
  const raw = partLabel(shown)
  const label = raw == null ? null : pushOnly ? `Push · ${raw}` : raw
  const dead = shown.from !== "NONE" && shown.health != null && shown.health.type !== "ok"
  const colour = (shown.colour ?? null) as FixtureColour | null
  const common = { descriptor, state, label, colour, dead, selected, onSelect }

  const cell = (() => {
    switch (descriptor.type) {
      case "fader":
        return <FaderCell {...common} descriptor={descriptor} pickup={pickup} />
      case "encoder":
        return <EncoderCell {...common} descriptor={descriptor} />
      case "button":
        return <ButtonCell {...common} descriptor={descriptor} />
      case "bankButton":
        return <BankButtonCell {...common} descriptor={descriptor} viewedLayer={viewedLayer} />
    }
  })()
  return dimmed ? <div className="opacity-60" data-live-dimmed>{cell}</div> : cell
})

interface CellCommon {
  state: ControlState
  label: string | null
  /** The colour of the fixture or group the binding drives, for the label's top band. */
  colour: FixtureColour | null
  dead: boolean
  selected: boolean
  onSelect: (controlId: string) => void
}

/**
 * The binding's short label under a control — `—` dashed when unbound, red when dead — with a 3px
 * band across its top in the colour of the fixture or group it drives (`Colours.dc.html` §C).
 */
function TargetLabel({ label, colour, dead }: { label: string | null; colour: FixtureColour | null; dead: boolean }) {
  return (
    <div className="relative w-full">
      {colour != null && label != null && (
        <span
          role="img"
          aria-label={`Colour: ${FIXTURE_COLOUR_LABELS[colour]}`}
          data-target-band={colour}
          className="absolute inset-x-1 top-0 h-[3px] rounded-full"
          style={{ background: colourVar(colour) }}
        />
      )}
      <div
        className={cn(
          // The band's room on every label, coloured or not, so a row of controls stays aligned.
          "min-h-7 overflow-hidden pt-1 text-center text-[10px] leading-3",
          label == null && "text-muted-foreground/60",
          dead && "text-destructive",
        )}
        style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}
      >
        {label ?? "—"}
      </div>
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
  colour,
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
        <TargetLabel label={label} colour={colour} dead={dead} />
        <ControlName>{descriptor.label}</ControlName>
      </div>
    </CellShell>
  )
}

function EncoderCell({
  descriptor,
  state,
  label,
  colour,
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
        <TargetLabel label={label} colour={colour} dead={dead} />
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
 * while that bank is the one being viewed.
 *
 * It draws its own label **whatever row sits on its control id**, because a binding there can never
 * fire: `SurfaceInputRouter.route` matches a bank button to `ResolvedInput.BankButton` and switches
 * the bank before resolving anything — "bank buttons short-circuit binding resolution", in its own
 * words. The desk refuses a binding on one, and the inspector says so for a row an older build or a
 * hand edit left behind.
 */
function BankButtonCell({
  descriptor,
  state,
  label,
  selected,
  onSelect,
  viewedLayer,
}: CellCommon & { descriptor: BankButtonControl; viewedLayer: string | null }) {
  const isActiveBank = viewedLayer === descriptor.bankId
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

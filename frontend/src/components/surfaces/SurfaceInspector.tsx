import { useCallback } from "react"
import { AlertTriangle, Hand, Trash2, X } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { cn } from "@/lib/utils"
import {
  useClearSurfaceSlotMutation,
  useWriteSurfaceSlotMutation,
  type CatalogueEntry,
  type ControlDescriptor,
  type ControlPart,
  type ControlState,
  type ControlSurfaceType,
  type IdentifyWait,
  type JobRegionDefinition,
  type PickupChange,
  type ResolvedPart,
  type SurfaceBinding,
  type SurfaceMapFrame,
  type SurfaceSlot,
  type TakeoverPolicy,
} from "@/store/surfaces"
import { inputOf, partKey, partsOf } from "@/api/surfacesApi"
import { DESK_OFFLINE_LABEL } from "@/api/wsGesture"
import { BindingForm, WritesToPill } from "./BindingForm"
import { useBindingChoices } from "./useBindingChoices"
import { midiPercent } from "./surfacePanelGeometry"

/**
 * Editor A (surface-setup plan D12, §4): the control picked on the picture, or touched on the desk,
 * with one **Does · On · How** block per part — Turn and Push for an encoder — each with its
 * *Writes to* word, its layer chips and a line saying what it shows. A part its region's job derives
 * says which (*From Groups as channel strips · item 3*) and offers *Make this control its own*; a
 * part bound by hand over a job says what it beats. Every write is the slot write; what the control
 * then does comes back on the map.
 */
export interface SurfaceInspectorProps {
  projectId: number
  profile: ControlSurfaceType
  controlId: string
  /** The layer being viewed; edits land on it or on both. */
  viewedLayer: string | null
  frame: SurfaceMapFrame | null
  catalogue: readonly CatalogueEntry[]
  state: ControlState | undefined
  pickup: PickupChange | undefined
  connected: boolean
  identify: { wait: IdentifyWait; begin: () => void; cancel: () => void }
  /** Open the jobs panel on [region]; absent draws no link. */
  onEditJob?: (region: string) => void
}

const PART_WORDS: Record<ControlDescriptor["type"], Partial<Record<ControlPart, string>>> = {
  fader: { MAIN: "Move" },
  encoder: { MAIN: "Turn", PUSH: "Push" },
  button: { MAIN: "Press" },
  bankButton: {},
}

const KIND_WORDS: Record<ControlDescriptor["type"], (control: ControlDescriptor) => string> = {
  fader: (c) => (c.type === "fader" && c.hasMotor ? "Motor fader" : "Fader"),
  encoder: (c) => (c.type === "encoder" && c.pushNote != null ? "Encoder with ring and push" : "Encoder"),
  button: () => "Button",
  bankButton: () => "Layer key",
}

const NO_PART: ResolvedPart = { from: "NONE" }

export const CLEAR_FAILED = "The desk could not take the binding off"

/**
 * What *Remove* takes off, from the banks of the rows it clears: `both layers`, `Layer A`, or both
 * at once. Null on a profile with no layers, where there is only the one row.
 */
export function removeScope(banks: readonly (string | null)[], layers: readonly { id: string; name: string }[]): string | null {
  if (layers.length === 0) return null
  const words = [...new Set(banks)].map((bank) => (bank == null ? "both layers" : `Layer ${layers.find((l) => l.id === bank)?.name ?? bank} only`))
  return words.join(" and ")
}

export function SurfaceInspector({
  projectId,
  profile,
  controlId,
  viewedLayer,
  frame,
  catalogue,
  state,
  pickup,
  connected,
  identify,
  onEditJob,
}: SurfaceInspectorProps) {
  const descriptor = profile.controls.find((c) => c.controlId === controlId)
  const layerParts = frame?.layers[viewedLayer ?? ""] ?? {}
  const place = jobPlaceOf(profile, controlId)
  const choices = useBindingChoices(projectId)
  const [writeSlot] = useWriteSurfaceSlotMutation()
  const [clearSlot] = useClearSurfaceSlotMutation()

  const write = useCallback(
    (slot: SurfaceSlot, binding: SurfaceBinding, takeoverPolicy?: TakeoverPolicy | null) =>
      writeSlot({ projectId, slot, binding, takeoverPolicy })
        .unwrap()
        .then(
          () => true,
          (e: { data?: { message?: string } }) => {
            toast.error(e?.data?.message ?? "The desk refused the binding")
            return false
          },
        ),
    [projectId, writeSlot],
  )
  const clear = useCallback(
    (slot: SurfaceSlot) =>
      clearSlot({ projectId, slot })
        .unwrap()
        .catch((e: { data?: { message?: string } }) => {
          toast.error(e?.data?.message ?? CLEAR_FAILED)
        }),
    [projectId, clearSlot],
  )

  if (!descriptor) return null
  const slotOf = (part: ControlPart, layer: string | null): SurfaceSlot => ({
    deviceTypeKey: profile.typeKey,
    controlId,
    part,
    layer,
  })

  const header = (
    <div className="space-y-0.5">
      <div className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{KIND_WORDS[descriptor.type](descriptor)}</div>
      <div className="text-sm font-semibold">{descriptor.label}</div>
      {place && (
        <p className="flex flex-wrap items-center gap-x-1 text-[11px] text-muted-foreground">
          In {place.region.name} · slot {place.slot + 1}.
          {onEditJob && (
            <button type="button" className="underline-offset-2 hover:underline" onClick={() => onEditJob(place.region.id)}>
              Its job…
            </button>
          )}
        </p>
      )}
    </div>
  )

  const ownRows = partsOf(descriptor).flatMap((part) => {
    const resolved = layerParts[partKey(controlId, part)]
    return resolved?.from === "DIRECT" ? [{ part, bank: resolved.bank ?? null }] : []
  })
  const scope = removeScope(ownRows.map((row) => row.bank), profile.banks)
  const footer = (
    <div className="flex flex-wrap items-center gap-2">
      <IdentifyButton identify={identify} connected={connected} />
      {ownRows.length > 0 && (
        <Button
          variant="ghost"
          size="sm"
          disabled={!connected}
          title={connected ? `Take this control's own binding off ${scope ?? "it"}` : DESK_OFFLINE_LABEL}
          onClick={() => {
            for (const row of ownRows) clear(slotOf(row.part, row.bank))
          }}
        >
          <Trash2 className="size-3.5" />
          {scope ? `Remove from ${scope}` : "Remove"}
        </Button>
      )}
    </div>
  )

  if (descriptor.type === "bankButton") {
    return (
      <div className="space-y-3 p-4" data-testid="surface-inspector">
        {header}
        <p className="text-[11px] text-muted-foreground">
          The device switches layers itself, so this key takes no binding. Any button can switch layer with Desk ›
          Switch layer.
        </p>
        {footer}
      </div>
    )
  }

  return (
    <div className="space-y-3 overflow-y-auto p-4" data-testid="surface-inspector">
      {header}
      {partsOf(descriptor).map((part) => {
        const resolved = layerParts[partKey(controlId, part)] ?? NO_PART
        const input = inputOf(descriptor, part)!
        const word = PART_WORDS[descriptor.type][part] ?? part
        return (
          <section key={part} className="space-y-2 rounded-lg border p-3" data-part={part}>
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-semibold">{word}</span>
              {resolved.from === "JOB" && <WritesToPill writesTo={resolved.writesTo} />}
            </div>
            {resolved.from === "JOB" ? (
              <div className="space-y-2">
                <p className="text-xs">{resolved.label}</p>
                <p className="text-[11px] text-muted-foreground">
                  From {resolved.jobLabel ?? "its region's job"}. Change the job to move every control it fills together.
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!connected || resolved.does == null}
                  onClick={() => {
                    if (resolved.does == null) return
                    write(slotOf(part, viewedLayer), { does: resolved.does, on: resolved.on ?? undefined, how: resolved.how ?? undefined }, resolved.takeoverPolicy)
                  }}
                >
                  Make this control its own
                </Button>
              </div>
            ) : (
              <BindingForm
                key={`${controlId}:${part}:${viewedLayer ?? ""}:${resolved.bindingId ?? "none"}`}
                name={word}
                catalogue={catalogue}
                input={input}
                binding={resolved.does == null ? null : { does: resolved.does, on: resolved.on ?? undefined, how: resolved.how ?? undefined }}
                entryId={resolved.entry ?? null}
                takeoverPolicy={resolved.takeoverPolicy ?? null}
                subjects={choices.subjects}
                attributesFor={choices.attributesFor}
                layers={profile.banks}
                layer={resolved.from === "DIRECT" ? (resolved.bank ?? null) : null}
                onLayerChange={(layer) => {
                  if (resolved.does == null) return
                  const from = resolved.bank ?? null
                  if (from === layer) return
                  const binding = { does: resolved.does, on: resolved.on ?? undefined, how: resolved.how ?? undefined }
                  write(slotOf(part, layer), binding, resolved.takeoverPolicy).then((ok) => {
                    if (ok) clear(slotOf(part, from))
                  })
                }}
                motor={descriptor.type === "fader" && descriptor.hasMotor}
                disabled={!connected}
                onChange={(binding, takeoverPolicy) => {
                  const layer = resolved.from === "DIRECT" ? (resolved.bank ?? null) : null
                  if (binding == null) clear(slotOf(part, layer))
                  else write(slotOf(part, layer), binding, takeoverPolicy)
                }}
              />
            )}
            {resolved.from === "DIRECT" && resolved.beats && (
              <p className="text-[11px] text-muted-foreground">Bound by hand over {resolved.beats}.</p>
            )}
            {resolved.shows && <p className="text-[11px] text-muted-foreground">{resolved.shows}</p>}
            {resolved.healthText && (
              <p className="flex items-center gap-1 text-[11px] text-destructive">
                <AlertTriangle className="size-3" />
                Dead: {resolved.healthText}
              </p>
            )}
          </section>
        )
      })}
      <LiveLine descriptor={descriptor} state={state} pickup={pickup} />
      <Separator />
      {footer}
    </div>
  )
}

/** The job region [controlId] sits in, and its slot there; null when no region claims it. */
export function jobPlaceOf(profile: ControlSurfaceType, controlId: string): { region: JobRegionDefinition; slot: number } | null {
  for (const region of profile.jobRegions ?? []) {
    const slot = region.slots.findIndex((s) => Object.values(s.roles).includes(controlId))
    if (slot >= 0) return { region, slot }
  }
  return null
}

/** Where the control is now, as the hardware was told. */
function LiveLine({ descriptor, state, pickup }: { descriptor: ControlDescriptor; state: ControlState | undefined; pickup: PickupChange | undefined }) {
  const position = state?.physical ?? state?.value ?? null
  return (
    <div className="space-y-0.5 text-[11px] text-muted-foreground">
      {position != null && <p>At {midiPercent(position)}%</p>}
      {descriptor.type === "fader" && state?.touched && <p>Touched — the motor holds still</p>}
      {pickup?.state === "AWAITING_PICKUP" && pickup.target != null && (
        <p className="text-[var(--editor-warning)]">Waiting to pick up — move to {midiPercent(pickup.target)}%</p>
      )}
    </div>
  )
}

/** *Touch a control on the desk*: the identify wait, with its banner and Cancel (D14). */
export function IdentifyButton({
  identify,
  connected,
}: {
  identify: { wait: IdentifyWait; begin: () => void; cancel: () => void }
  connected: boolean
}) {
  if (identify.wait.waiting) {
    return (
      <div role="status" className="flex items-center gap-2 rounded-md border border-primary/40 bg-primary/5 px-2 py-1 text-xs">
        <Hand className="size-3.5 text-primary" />
        Touch a control on the desk… it won’t fire.
        <Button variant="ghost" size="sm" className="h-6 px-1.5" onClick={identify.cancel}>
          <X className="size-3" />
          Cancel
        </Button>
      </div>
    )
  }
  return (
    <span className="flex items-center gap-2">
      <Button variant="outline" size="sm" disabled={!connected} title={connected ? undefined : DESK_OFFLINE_LABEL} onClick={identify.begin}>
        <Hand className="size-3.5" />
        Touch a control on the desk
      </Button>
      {identify.wait.ended === "timeout" && <span className={cn("text-[11px] text-muted-foreground")}>Nothing was touched.</span>}
    </span>
  )
}

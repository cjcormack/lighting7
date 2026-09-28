import { useCallback, useEffect, useMemo, useState } from "react"
import { useDndMonitor } from "@dnd-kit/core"
import { useSearchParams } from "react-router"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { AlertTriangle, Sliders } from "lucide-react"
import {
  useSurfaceDevices,
  useActiveBanks,
  useEncoderBanks,
  useSurfaceControls,
  usePickupStates,
  useControlSurfaceTypeListQuery,
  useSurfaceBindingsQuery,
  useCreateSurfaceBindingMutation,
  useUpdateSurfaceBindingMutation,
  useDeleteSurfaceBindingMutation,
} from "@/store/surfaces"
import type {
  ControlState,
  ControlSurfaceBinding,
  PickupChange,
  SurfaceDeviceInfo,
} from "@/store/surfaces"
import { BankSwitcher } from "@/components/surfaces/BankSwitcher"
import { BindingMatrix } from "@/components/surfaces/BindingMatrix"
import { SurfacePanel } from "@/components/surfaces/SurfacePanel"
import { SurfaceInspector } from "@/components/surfaces/SurfaceInspector"
import { SurfaceLibrary } from "@/components/surfaces/SurfaceLibrary"
import { SelectionChip } from "@/components/surfaces/SelectionChip"
import { effectiveTarget } from "@/components/surfaces/targetUtils"
import { useRecordBindingOptions } from "@/components/surfaces/recordOptions"
import { buildBindingIndex, DEFAULT_ENCODER_BANK } from "@/lib/surfaceResolve"
import {
  bindingWriteFor,
  surfaceDragData,
  surfaceDropData,
  type SurfaceDragData,
} from "@/lib/surfaceDrop"
import { targetKey } from "@/lib/targetKey"
import { lightingApi } from "@/api/lightingApi"
import { cn } from "@/lib/utils"
import { CurrentProjectRedirect } from "@/components/CurrentProjectRedirect"

/**
 * Settings › Surfaces — a picture of the attached desk, and an inspector for whichever control
 * you click.
 *
 * The Blackout and grand-master buttons that used to sit in this header are **gone** (plan D1).
 * The targets, the `surfaceScaler.*` family and the ShowBar's own toggles are untouched: this is
 * where the operator wires the desk, not where they run it, and a live-rig control on a settings
 * page is a control in the wrong place rather than a missing feature.
 */

// ─── Redirect ─────────────────────────────────────────────────────────

export function SurfacesRedirect() {
  return <CurrentProjectRedirect to="settings/surfaces" />
}

// ─── Content ──────────────────────────────────────────────────────────

const NO_CONTROLS: Readonly<Record<string, ControlState>> = {}

export function SurfacesContent({ projectId }: { projectId: number }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const devices = useSurfaceDevices()
  const banks = useActiveBanks()
  const encoderBanks = useEncoderBanks()
  const allControls = useSurfaceControls()
  const pickups = usePickupStates()
  const { data: types } = useControlSurfaceTypeListQuery()
  const { data: bindings } = useSurfaceBindingsQuery(projectId)
  const [createBinding] = useCreateSurfaceBindingMutation()
  const [updateBinding] = useUpdateSurfaceBindingMutation()
  const [deleteBinding] = useDeleteSurfaceBindingMutation()
  // One subscription for the whole route: the picture and the table are both mounted at once
  // (one CSS-hidden by breakpoint, not unmounted — see the layout below), and both want a
  // record's name where `describeTarget` can only give a uuid.
  const records = useRecordBindingOptions(projectId)
  // Edit mode is **local state**, deliberately not the busk view's Redux slice: that one exists
  // because the cue-slot overlay is a sibling of the routed page and could never read a context
  // provided inside it. Here the library and the picture are both inside this route, so a slice
  // would be state outliving both of its readers for nothing.
  const [editing, setEditing] = useState(false)

  // `?binding=<id>` is minted from the fixtures and groups pages (`BoundControlBadge`), so it is
  // an in-app contract: it picks the binding's device, forces its bank, and now also opens the
  // inspector on the control it names.
  const highlightBindingId = useMemo(() => {
    const v = searchParams.get("binding")
    return v ? Number(v) : null
  }, [searchParams])

  const [selectedDisplayKey, setSelectedDisplayKey] = useState<string | null>(null)
  const [selectedControlId, setSelectedControlId] = useState<string | null>(null)

  // Pick first matched device if nothing selected yet.
  useEffect(() => {
    if (selectedDisplayKey) return
    const first = devices.find((d) => d.isMatched)
    if (first) setSelectedDisplayKey(first.displayKey)
  }, [devices, selectedDisplayKey])

  useEffect(() => {
    if (!highlightBindingId || !bindings) return
    const binding = bindings.find((b) => b.id === highlightBindingId)
    if (!binding) return
    const device = devices.find((d) => d.typeKey === binding.deviceTypeKey)
    if (device) setSelectedDisplayKey(device.displayKey)
    // A strip row's `controlId` is the *strip* id, which is in no profile's `controls` — so
    // handing it straight to the inspector renders nothing at all. Land on the strip's fader
    // instead: the badge that minted this link matched on the dimmer, and the fader is the
    // dimmer. This is the common case rather than a corner, since a group on a strip is the
    // shape the view is built around.
    const linkProfile = (types ?? []).find((t) => t.typeKey === binding.deviceTypeKey)
    const strip = linkProfile?.strips?.find((s) => s.id === binding.controlId)
    setSelectedControlId(strip ? strip.fader : binding.controlId)
    if (binding.bank != null && binding.deviceTypeKey) {
      lightingApi.surfaces.setBank(binding.deviceTypeKey, binding.bank)
    }
    // Clear the param after we've consumed it so back-nav doesn't re-trigger.
    const params = new URLSearchParams(searchParams)
    params.delete("binding")
    setSearchParams(params, { replace: true })
  }, [highlightBindingId, bindings, devices, types, searchParams, setSearchParams])

  const selectedDevice = devices.find((d) => d.displayKey === selectedDisplayKey) ?? null
  const selectedProfile = selectedDevice?.typeKey
    ? ((types ?? []).find((t) => t.typeKey === selectedDevice.typeKey) ?? null)
    : null

  const deadBindingCount = useMemo(
    () => (bindings ?? []).filter((b) => b.health.type !== "ok").length,
    [bindings],
  )

  const activeBank = selectedDevice?.typeKey ? (banks[selectedDevice.typeKey] ?? null) : null
  const encoderBank = selectedDevice?.typeKey
    ? (encoderBanks[selectedDevice.typeKey] ?? DEFAULT_ENCODER_BANK)
    : DEFAULT_ENCODER_BANK

  const index = useMemo(
    () => buildBindingIndex(bindings ?? [], selectedProfile),
    [bindings, selectedProfile],
  )

  const deviceTypeKey = selectedProfile?.typeKey ?? null
  const [lifted, setLifted] = useState<SurfaceDragData | null>(null)

  /**
   * The library's drops, resolved here rather than in the palette.
   *
   * `useDndMonitor` on the app's one `DndContext` (`dnd/DeskDndProvider.tsx`), never a nested one,
   * for the busk page's reason — and foreign drags are ignored by id on the way in, exactly as the
   * cue-slot handler ignores ours. The mapping itself is pure in `lib/surfaceDrop.ts`; what is left
   * here is the two mutations and the device this page is showing.
   */
  const handleDragEnd = useCallback(
    (drag: SurfaceDragData, over: Parameters<typeof surfaceDropData>[0]) => {
      const drop = surfaceDropData(over)
      if (drop == null || deviceTypeKey == null) return
      const write = bindingWriteFor(drag, drop, index, activeBank)
      if (write == null) return
      if (write.kind === "update") {
        updateBinding({ projectId, bindingId: write.bindingId, target: write.target })
        return
      }
      createBinding({
        projectId,
        deviceTypeKey,
        controlId: write.controlId,
        bank: write.bank,
        target: write.target,
      })
    },
    [projectId, deviceTypeKey, index, activeBank, createBinding, updateBinding],
  )

  useDndMonitor({
    onDragStart(event) {
      setLifted(surfaceDragData(event.active))
    },
    onDragEnd(event) {
      const drag = surfaceDragData(event.active)
      setLifted(null)
      if (drag != null) handleDragEnd(drag, event.over)
    },
    onDragCancel() {
      setLifted(null)
    },
  })

  const removeBinding = useCallback(
    (bindingId: number) => {
      deleteBinding({ projectId, bindingId })
    },
    [projectId, deleteBinding],
  )

  /** Where each library row already sits — `strip 3`, `on 2 controls`. */
  const placements = useMemo(
    () => describePlacements(bindings ?? [], deviceTypeKey, activeBank),
    [bindings, deviceTypeKey, activeBank],
  )

  // Pickup state is keyed by `displayKey|controlId` across every device; the panel wants one
  // device's, keyed by control.
  const devicePickups = useMemo(() => {
    if (!selectedDevice) return {}
    const out: Record<string, PickupChange> = {}
    for (const change of Object.values(pickups)) {
      if (change.displayKey === selectedDevice.displayKey) out[change.controlId] = change
    }
    return out
  }, [pickups, selectedDevice])

  const controls = selectedDevice
    ? (allControls[selectedDevice.displayKey] ?? NO_CONTROLS)
    : NO_CONTROLS

  return (
    <div className="flex h-full flex-col">
      <div className="flex flex-wrap items-center gap-2 p-4">
        {devices.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No MIDI devices connected — plug one in and it will appear here.
          </p>
        ) : (
          devices.map((device) => (
            <DeviceChip
              key={device.displayKey}
              device={device}
              isSelected={device.displayKey === selectedDisplayKey}
              onSelect={() => {
                setSelectedDisplayKey(device.displayKey)
                setSelectedControlId(null)
              }}
            />
          ))
        )}
        {deadBindingCount > 0 && (
          <Badge variant="destructive" className="gap-1 text-[10px]">
            <AlertTriangle className="size-3" />
            {deadBindingCount} dead binding{deadBindingCount === 1 ? "" : "s"}
          </Badge>
        )}
        <div className="ml-auto flex items-center gap-2">
          {selectedProfile && (
            <BankSwitcher
              deviceTypeKey={selectedProfile.typeKey}
              banks={selectedProfile.banks}
              activeBank={activeBank}
            />
          )}
          {/*
            Hidden below `md` with the picture it edits — there is nothing to drag onto down
            there — but *Done* stays at every width, so a window narrowed mid-edit can still leave.
            The busk view's *Edit layout* makes the same pair of calls for the same reason.
          */}
          {selectedProfile?.layout && (
            <Button
              size="sm"
              variant={editing ? "default" : "outline"}
              className={cn("h-7 text-xs", !editing && "hidden md:inline-flex")}
              onClick={() => setEditing((on) => !on)}
            >
              {editing ? "Done" : "Edit bindings"}
            </Button>
          )}
        </div>
      </div>

      <Separator />

      <div className="flex min-h-0 flex-1 flex-col overflow-hidden lg:flex-row">
        <section className="min-w-0 flex-1 overflow-auto p-4">
          {!selectedDevice ? (
            <EmptyState />
          ) : !selectedProfile ? (
            <UnmatchedDeviceState device={selectedDevice} />
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <p className="text-xs text-muted-foreground">
                  {selectedProfile.vendor} · {selectedProfile.product} ·{" "}
                  {activeBank == null ? "Global" : `Bank ${activeBank}`}
                </p>
                <SelectionChip />
                <Legend />
              </div>

              {/*
                The picture is the view; the grouped table is the narrow rendering and the
                fallback for a profile with no layout. Both are hidden rather than reflowed,
                because a panel drawn as a picture does not become a list by getting narrower.
              */}
              {selectedProfile.layout ? (
                <>
                  <div className="hidden overflow-x-auto md:block">
                    <SurfacePanel
                      profile={selectedProfile}
                      controls={controls}
                      index={index}
                      activeBank={activeBank}
                      encoderBank={encoderBank}
                      pickups={devicePickups}
                      selectedControlId={selectedControlId}
                      onSelectControl={setSelectedControlId}
                      editing={editing}
                      lifted={lifted}
                      onRemoveBinding={removeBinding}
                      records={records}
                    />
                  </div>
                  <div className="md:hidden">
                    <BindingMatrix
                      projectId={projectId}
                      device={selectedDevice}
                      profile={selectedProfile}
                      activeBank={activeBank}
                      highlightBindingId={highlightBindingId}
                      records={records}
                    />
                  </div>
                </>
              ) : (
                <BindingMatrix
                  projectId={projectId}
                  device={selectedDevice}
                  profile={selectedProfile}
                  activeBank={activeBank}
                  highlightBindingId={highlightBindingId}
                  records={records}
                />
              )}
            </div>
          )}
        </section>

        {/*
          One slot, two occupants (D9): *Edit bindings* replaces the inspector with the library,
          rather than opening a second panel beside it. The picture is what both are about, and
          two 360px columns would leave it nothing.
        */}
        {selectedProfile && (editing || selectedControlId) && (
          <aside className="hidden w-[360px] shrink-0 overflow-hidden border-l md:block">
            {editing ? (
              <SurfaceLibrary
                projectId={projectId}
                banks={selectedProfile.banks}
                deviceTypeKey={selectedProfile.typeKey}
                placements={placements}
              />
            ) : (
              selectedControlId && (
                <SurfaceInspector
                  projectId={projectId}
                  profile={selectedProfile}
                  controlId={selectedControlId}
                  index={index}
                  activeBank={activeBank}
                  encoderBank={encoderBank}
                  state={controls[selectedControlId]}
                  pickup={devicePickups[selectedControlId]}
                  bindings={bindings ?? []}
                />
              )
            )}
          </aside>
        )}
      </div>
    </div>
  )
}

/**
 * "This is already on the surface, and where" — `strip 3`, `on 2 controls`, keyed by the row's
 * `type:key`.
 *
 * **Only rows in force on the bank being drawn**, which is the exact-bank ones plus the
 * bank-agnostic ones — the same rule `activeBindingAt` applies to the picture. Counting every bank
 * would badge a group *strip 3* while the panel beside it shows strip 3 empty, and a drop there
 * would then create a second, separate binding rather than the one the badge implied was already
 * present.
 *
 * A strip wins over a count because it *is* the placement the library's row gesture makes; a target
 * that is only on single controls has no one place to name, so it gets the count instead. `flash`
 * is unwrapped so a flash button counts towards its own group rather than towards nothing.
 *
 * The record variants key by **uuid**, matching `SurfaceLibrary`'s row keys — a `pressPad` is
 * deliberately not counted, because its library row is its *page* and badging that page "on 1
 * control" for one of its pads would claim the page itself is bound.
 */
function describePlacements(
  bindings: readonly ControlSurfaceBinding[],
  deviceTypeKey: string | null,
  activeBank: string | null,
): ReadonlyMap<string, string> {
  const out = new Map<string, string>()
  const counts = new Map<string, number>()
  for (const binding of bindings) {
    if (deviceTypeKey != null && binding.deviceTypeKey !== deviceTypeKey) continue
    if (binding.bank != null && binding.bank !== activeBank) continue
    const target = effectiveTarget(binding.target)
    let key: string | null = null
    if (target.type === "strip" || target.type === "selectTarget") key = targetKey(target.target)
    else if (target.type === "fixtureProperty") key = `fixture:${target.fixtureKey}`
    else if (target.type === "groupProperty") key = `group:${target.groupName}`
    // The record rows key by uuid, matching `SurfaceLibrary`'s own row keys. No collision with the
    // two above: a `CueTarget` type is only ever `fixture` or `group`. A pad badges the **page** it
    // sits on, which is the row the library draws it in.
    else if (target.type === "pressTemplate") key = `template:${target.templateUuid}`
    else if (target.type === "applyLook") key = `look:${target.lookUuid}`
    else if (target.type === "buskPageSet") key = `busk-page:${target.pageUuid}`
    if (key == null) continue
    if (binding.target.type === "strip") {
      out.set(key, binding.controlId.replace(/^strip-/, "strip "))
      continue
    }
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  // Strips are already in `out` and win, so a count only fills a gap — no write-then-overwrite.
  for (const [key, count] of counts) {
    if (!out.has(key)) out.set(key, `on ${count} control${count === 1 ? "" : "s"}`)
  }
  return out
}

/** The legend the panel's states are read against — LED, touched, dead. */
function Legend() {
  return (
    <span className="flex items-center gap-3 text-[10px] text-muted-foreground">
      <span className="flex items-center gap-1">
        <span className="h-[3px] w-3 rounded-full bg-surface-led" /> LED lit
      </span>
      <span className="flex items-center gap-1">
        <span className="size-2.5 rounded-[2px] outline-2 outline-ring" /> touched
      </span>
      <span className="flex items-center gap-1">
        <span className="size-2.5 rounded-[2px] border border-destructive" /> dead binding
      </span>
    </span>
  )
}

function DeviceChip({
  device,
  isSelected,
  onSelect,
}: {
  device: SurfaceDeviceInfo
  isSelected: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        "flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs transition-colors",
        isSelected ? "border-accent-foreground/20 bg-accent" : "border-transparent hover:bg-accent/50",
      )}
    >
      <Sliders className="size-3.5 shrink-0" />
      <span className="font-medium">{device.displayName}</span>
      {device.isMatched ? (
        <Badge variant="secondary" className="text-[10px]">{device.typeKey}</Badge>
      ) : (
        <Badge variant="outline" className="text-[10px]">unmatched</Badge>
      )}
      {device.isMatched && (
        <span className="text-[10px] text-muted-foreground">
          {device.hasInputPort ? "in" : "no in"} · {device.hasOutputPort ? "out" : "no out"}
        </span>
      )}
    </button>
  )
}

function EmptyState() {
  return (
    <div className="flex h-full flex-col items-center justify-center text-sm text-muted-foreground">
      <Sliders className="mb-2 size-8 opacity-40" />
      <p>Select a device to view and edit its bindings.</p>
    </div>
  )
}

function UnmatchedDeviceState({ device }: { device: SurfaceDeviceInfo }) {
  return (
    <Card className="space-y-2 p-6">
      <h2 className="font-semibold">{device.displayName}</h2>
      <p className="text-sm text-muted-foreground">
        This device didn&rsquo;t match any registered <code>@ControlSurfaceType</code> profile.
        Add a Kotlin profile under <code>src/main/kotlin/uk/me/cormack/lighting7/midi/devices/</code>
        to bind controls on this device.
      </p>
      <div className="font-mono text-xs text-muted-foreground">displayKey: {device.displayKey}</div>
    </Card>
  )
}

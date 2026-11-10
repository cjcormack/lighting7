import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useSearchParams } from "react-router"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { AlertTriangle, Sliders } from "lucide-react"
import {
  useSurfaceDevices,
  useActiveBanks,
  useSurfaceControls,
  usePickupStates,
  useControlSurfaceTypeListQuery,
  useSurfaceBindingsQuery,
  useSurfaceFunctionsQuery,
  useSurfaceJobKindsQuery,
  useSurfaceMap,
  useIdentify,
} from "@/store/surfaces"
import type { ControlState, IdentifyCapture, PickupChange, SurfaceDeviceInfo } from "@/store/surfaces"
import { useIsDeskConnected } from "@/store/status"
import { BankSwitcher } from "@/components/surfaces/BankSwitcher"
import { SurfacePanel } from "@/components/surfaces/SurfacePanel"
import { SurfaceInspector, IdentifyButton } from "@/components/surfaces/SurfaceInspector"
import { SurfaceSheet } from "@/components/surfaces/SurfaceSheet"
import { SurfaceJobsPanel } from "@/components/surfaces/SurfaceJobsPanel"
import { SelectionChip } from "@/components/surfaces/SelectionChip"
import { cn } from "@/lib/utils"
import { CurrentProjectRedirect } from "@/components/CurrentProjectRedirect"

/**
 * Settings › Surfaces — the attached desk as a picture, and an inspector for whichever control you
 * click or touch (editor A); a job for each region beside the picture (editor B); or the same
 * bindings as a table (editor C), the only view below tablet width. What every control does is the
 * desk's served map (surface-setup plan D15), for the layer being viewed, which switches nothing on
 * the hardware (D13).
 */

// ─── Redirect ─────────────────────────────────────────────────────────

export function SurfacesRedirect() {
  return <CurrentProjectRedirect to="settings/surfaces" />
}

// ─── Content ──────────────────────────────────────────────────────────

const NO_CONTROLS: Readonly<Record<string, ControlState>> = {}
const NO_CATALOGUE: never[] = []
type View = "picture" | "jobs" | "table"
const VIEW_WORDS: Record<View, string> = { picture: "Picture", jobs: "Jobs", table: "Table" }

export function SurfacesContent({ projectId }: { projectId: number }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const devices = useSurfaceDevices()
  const banks = useActiveBanks()
  const allControls = useSurfaceControls()
  const pickups = usePickupStates()
  const connected = useIsDeskConnected()
  const { data: types } = useControlSurfaceTypeListQuery()
  const { data: bindings } = useSurfaceBindingsQuery(projectId)
  const { data: catalogue } = useSurfaceFunctionsQuery()
  const { data: jobKinds } = useSurfaceJobKindsQuery()

  const [selectedDisplayKey, setSelectedDisplayKey] = useState<string | null>(null)
  const [selectedControlId, setSelectedControlId] = useState<string | null>(null)
  /**
   * The layer the editors show, null for no layer; undefined until one is chosen, which views the
   * device's own.
   */
  const [viewed, setViewed] = useState<string | null | undefined>(undefined)
  const [view, setView] = useState<View>("picture")
  /** The job region the Jobs view is editing, lit on the picture. */
  const [focusedRegion, setFocusedRegion] = useState<string | null>(null)

  // Pick first matched device if nothing selected yet.
  useEffect(() => {
    if (selectedDisplayKey) return
    const first = devices.find((d) => d.isMatched)
    if (first) setSelectedDisplayKey(first.displayKey)
  }, [devices, selectedDisplayKey])

  const selectedDevice = devices.find((d) => d.displayKey === selectedDisplayKey) ?? null
  const selectedProfile = selectedDevice?.typeKey
    ? ((types ?? []).find((t) => t.typeKey === selectedDevice.typeKey) ?? null)
    : null
  const frame = useSurfaceMap(selectedProfile?.typeKey)

  const deviceLayer = selectedDevice ? (banks[selectedDevice.displayKey] ?? null) : null
  const viewedLayer = viewed !== undefined ? viewed : deviceLayer
  const layered = (selectedProfile?.banks.length ?? 0) > 0
  const deviceOnNoLayer = layered && selectedDevice != null && deviceLayer == null

  // `?type=&control=&layer=` is minted by a record's *Bound on* chip (`BoundOnChip`): it picks a
  // device of that type and the control, and **views** the layer, moving nothing on the hardware.
  const linkedControl = searchParams.get("control")
  const linkedType = searchParams.get("type")
  const linkedLayer = searchParams.get("layer")
  const selectedDisplayKeyRef = useRef(selectedDisplayKey)
  useEffect(() => {
    selectedDisplayKeyRef.current = selectedDisplayKey
  }, [selectedDisplayKey])
  useEffect(() => {
    if (linkedControl == null) return
    const shown = devices.find((d) => d.displayKey === selectedDisplayKeyRef.current)
    const device =
      linkedType == null || shown?.typeKey === linkedType ? shown : devices.find((d) => d.typeKey === linkedType)
    // A link to a type with no device attached selects nothing; it is still consumed.
    if (linkedType == null || device != null) {
      if (device) setSelectedDisplayKey(device.displayKey)
      setSelectedControlId(linkedControl)
      if (linkedLayer != null) setViewed(linkedLayer)
      setView("picture")
    }
    const params = new URLSearchParams(searchParams)
    params.delete("control")
    params.delete("type")
    params.delete("layer")
    setSearchParams(params, { replace: true })
  }, [linkedControl, linkedType, linkedLayer, devices, searchParams, setSearchParams])

  // A touch on the desk selects its control, on the device it came from.
  const onCapture = useCallback((capture: IdentifyCapture) => {
    setSelectedDisplayKey(capture.displayKey)
    setSelectedControlId(capture.controlId)
    setView("picture")
  }, [])
  const identify = useIdentify(onCapture)
  const beginIdentify = useCallback(() => identify.begin(selectedProfile?.typeKey ?? null), [identify, selectedProfile])
  const identifyControls = useMemo(() => ({ wait: identify.wait, begin: beginIdentify, cancel: identify.cancel }), [identify.wait, identify.cancel, beginIdentify])

  const deadBindingCount = useMemo(
    () => (bindings ?? []).filter((b) => b.health.type !== "ok").length,
    [bindings],
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
  const parts = frame?.layers[viewedLayer ?? ""] ?? {}
  const dimmed = layered && selectedDevice != null && viewedLayer !== deviceLayer
  const viewedName = selectedProfile?.banks.find((b) => b.id === viewedLayer)?.name
  const highlighted = useMemo(() => {
    if (view !== "jobs" || focusedRegion == null) return undefined
    const region = selectedProfile?.jobRegions?.find((r) => r.id === focusedRegion)
    return region ? new Set(region.slots.flatMap((slot) => Object.values(slot.roles))) : undefined
  }, [view, focusedRegion, selectedProfile])
  const editJob = useCallback((region: string) => {
    setFocusedRegion(region)
    setView("jobs")
  }, [])
  const deviceName = selectedProfile?.banks.find((b) => b.id === deviceLayer)?.name

  const sheet = selectedProfile ? (
    <SurfaceSheet
      projectId={projectId}
      profile={selectedProfile}
      frame={frame}
      catalogue={catalogue ?? NO_CATALOGUE}
      viewedLayer={viewedLayer}
      connected={connected}
    />
  ) : null

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
                setViewed(undefined)
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
        <div className="ml-auto flex flex-wrap items-center gap-3">
          {selectedProfile && (
            <BankSwitcher
              banks={selectedProfile.banks}
              viewedLayer={viewedLayer}
              deviceLayer={deviceLayer}
              deviceOnNoLayer={deviceOnNoLayer}
              onView={setViewed}
            />
          )}
          {selectedProfile?.layout && (
            <div className="hidden items-center gap-1 md:flex" role="group" aria-label="View">
              {(["picture", "jobs", "table"] as const).filter((v) => v !== "jobs" || (selectedProfile.jobRegions?.length ?? 0) > 0).map((v) => (
                <button
                  key={v}
                  type="button"
                  aria-pressed={view === v}
                  onClick={() => setView(v)}
                  className={cn("rounded-md border px-2 py-0.5 text-xs", view === v ? "border-primary bg-primary/10" : "text-muted-foreground")}
                >
                  {VIEW_WORDS[v]}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <Separator />

      <div className="flex min-h-0 flex-1 flex-col overflow-hidden lg:flex-row">
        <section className="flex min-w-0 flex-1 flex-col overflow-auto p-4">
          {!selectedDevice ? (
            <EmptyState />
          ) : !selectedProfile ? (
            <UnmatchedDeviceState device={selectedDevice} />
          ) : (
            <div className="flex min-h-0 flex-1 flex-col space-y-3">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <p className="text-xs text-muted-foreground">
                  {selectedProfile.vendor} · {selectedProfile.product}
                  {viewedName ? ` · Layer ${viewedName}` : layered ? " · No layer" : ""}
                </p>
                <SelectionChip />
                <Legend />
                {deviceOnNoLayer && (
                  <p className="text-[11px] text-[var(--editor-warning)]">
                    The desk is on no layer, so only bindings on both layers fire. Its own layer key switches it.
                  </p>
                )}
                {dimmed && (
                  <p className="text-[11px] text-[var(--editor-warning)]">
                    Viewing {viewedName ? `Layer ${viewedName}` : "no layer"}; the desk is on{" "}
                    {deviceName ? `Layer ${deviceName}` : "no layer"}, so its live state is faint.
                  </p>
                )}
                {view === "picture" && selectedControlId == null && (
                  <span className="hidden md:inline">
                    <IdentifyButton identify={identifyControls} connected={connected} />
                  </span>
                )}
              </div>

              {/*
                The picture and the table are hidden rather than reflowed, because a panel drawn as a
                picture does not become a list by getting narrower: below tablet width the table is
                the only view.
              */}
              {selectedProfile.layout ? (
                <>
                  <div className={cn("hidden overflow-x-auto", view !== "table" && "md:block")}>
                    <SurfacePanel
                      profile={selectedProfile}
                      controls={controls}
                      parts={parts}
                      viewedLayer={viewedLayer}
                      dimmed={dimmed}
                      pickups={devicePickups}
                      selectedControlId={view === "jobs" ? null : selectedControlId}
                      onSelectControl={(controlId) => {
                        if (view !== "jobs") return setSelectedControlId(controlId)
                        const region = selectedProfile.jobRegions?.find((r) => r.slots.some((slot) => Object.values(slot.roles).includes(controlId)))
                        if (region) setFocusedRegion(region.id)
                      }}
                      highlighted={highlighted}
                    />
                  </div>
                  <div className={cn("flex min-h-0 flex-1 flex-col", view !== "table" && "md:hidden")} data-testid="surface-table-view">
                    {sheet}
                  </div>
                </>
              ) : (
                sheet
              )}
            </div>
          )}
        </section>

        {selectedProfile && selectedControlId && view === "picture" && (
          <aside className="hidden w-[360px] shrink-0 overflow-y-auto border-l md:block">
            <SurfaceInspector
              projectId={projectId}
              profile={selectedProfile}
              controlId={selectedControlId}
              viewedLayer={viewedLayer}
              frame={frame}
              catalogue={catalogue ?? NO_CATALOGUE}
              state={controls[selectedControlId]}
              pickup={devicePickups[selectedControlId]}
              connected={connected}
              identify={identifyControls}
              onEditJob={(selectedProfile.jobRegions?.length ?? 0) > 0 ? editJob : undefined}
            />
          </aside>
        )}

        {selectedProfile && view === "jobs" && (
          <aside className="hidden w-[380px] shrink-0 overflow-y-auto border-l md:block">
            <SurfaceJobsPanel
              projectId={projectId}
              profile={selectedProfile}
              frame={frame}
              catalogue={catalogue ?? NO_CATALOGUE}
              kinds={jobKinds ?? NO_CATALOGUE}
              connected={connected}
              focused={focusedRegion}
              onFocus={setFocusedRegion}
            />
          </aside>
        )}
      </div>
    </div>
  )
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

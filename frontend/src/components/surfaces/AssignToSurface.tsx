import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import { Sliders } from 'lucide-react'
import { toast } from 'sonner'
import { skipToken } from '@reduxjs/toolkit/query'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { isRemoteMode } from '@/lib/remoteMode'
import { MENU_PARTS, type MenuKind } from '@/components/menus/menuAdapter'
import { inputOf, partsOf } from '@/api/surfacesApi'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'
import { useIsDeskConnected } from '@/store/status'
import { useCurrentProjectQuery } from '@/store/projects'
import {
  useControlSurfaceTypeListQuery,
  useIdentify,
  useSurfaceControls,
  useSurfaceDevices,
  useSurfaceFunctionsQuery,
  useSurfaceMap,
  useSurfaceMapsQuery,
  useSurfaceSlotPreviewQuery,
  useWriteSurfaceSlotMutation,
  type CatalogueEntry,
  type ControlPart,
  type ControlState,
  type ControlSurfaceType,
  type IdentifyCapture,
  type SurfaceBinding,
  type SurfaceMapFrame,
  type TakeoverPolicy,
} from '@/store/surfaces'
import { BindingForm, isWhole, type SubjectChoices } from './BindingForm'
import { IdentifyButton } from './SurfaceInspector'
import { SurfacePanel } from './SurfacePanel'
import { boundOnText, useBoundOn } from './BoundOnChip'
import { EMPTY_RECORD_OPTIONS } from './recordOptions'
import { useBindingChoices } from './useBindingChoices'
import { fitEntries, initialBinding, type AssignSubject } from './assignOffer'

/**
 * *Assign to surface…* (surface-setup plan D12 D, §4): a record's, a group's or a fixture's own door
 * onto a control. It waits for a touch on the desk — captured, never fired (D14) — or a click on the
 * picture, the only way in remote mode; offers the functions that act on the record and fit the
 * part; says what the assign replaces; and assigns through the slot write.
 */

export interface AssignTarget {
  /** What the sheet calls it: `Act 1`. */
  name: string
  /** What it can be assigned as, most specific first — a cue's place, then the cue. */
  subjects: readonly AssignSubject[]
  /** The project the record is in; absent is the current one. Surfaces are bound in the current project only. */
  projectId?: number
}

/** Each open is numbered, so a second open is a fresh sheet. */
let openTarget: { target: AssignTarget; seq: number } | null = null
let openSeq = 0
const listeners = new Set<() => void>()

/** Open the sheet on [target]; one at a time, the last asked wins. */
export function openAssignToSurface(target: AssignTarget) {
  openTarget = { target, seq: ++openSeq }
  listeners.forEach((fn) => fn())
}

/** What the sheet is open on, or null. */
export function assignTargetNow(): AssignTarget | null {
  return openTarget?.target ?? null
}

export function closeAssignToSurface() {
  openTarget = null
  listeners.forEach((fn) => fn())
}

function useOpenTarget(): { target: AssignTarget; seq: number } | null {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn)
      return () => listeners.delete(fn)
    },
    () => openTarget,
  )
}

/** The menu entry, in either kind of menu, with what the record is bound on beside it. */
export function AssignToSurfaceMenuItem({ target, menu }: { target: AssignTarget; menu: MenuKind }) {
  const { Item } = MENU_PARTS[menu]
  const bound = boundOnText(useBoundOn(target.subjects, target.projectId).uses)
  return (
    <Item onSelect={() => openAssignToSurface(target)}>
      <Sliders className="size-3.5" />
      <span className="flex min-w-0 flex-col">
        <span>Assign to surface…</span>
        {bound != null && <span className="truncate text-[10px] text-muted-foreground">{bound}</span>}
      </span>
    </Item>
  )
}

/** The same door as a button, for a sheet with no menu to put it in. */
export function AssignToSurfaceButton({ target, className }: { target: AssignTarget; className?: string }) {
  return (
    <Button variant="outline" size="sm" className={className} onClick={() => openAssignToSurface(target)}>
      <Sliders className="size-3.5" />
      Assign to surface…
    </Button>
  )
}

/** Mounted once in the layout: the sheet a door opened, for the current project. */
export function AssignToSurfaceHost() {
  const open = useOpenTarget()
  return open == null ? null : <OpenAssignSheet key={open.seq} target={open.target} />
}

function OpenAssignSheet({ target }: { target: AssignTarget }) {
  const { data: project } = useCurrentProjectQuery()
  if (project == null) return null
  return <AssignToSurfaceSheet projectId={project.id} target={target} onClose={closeAssignToSurface} />
}

const NO_CATALOGUE: CatalogueEntry[] = []
const NO_CONTROLS: Readonly<Record<string, ControlState>> = {}
const PART_WORDS: Record<ControlPart, string> = { MAIN: 'Turn', PUSH: 'Push' }

interface Slot {
  deviceTypeKey: string
  controlId: string
  part: ControlPart
}

interface Draft {
  /** The slot it was made for; a draft for another slot is not this one's. */
  at: string
  binding: SurfaceBinding
  entryId: string
  takeoverPolicy: TakeoverPolicy | null
}

export function AssignToSurfaceSheet({ projectId, target, onClose }: { projectId: number; target: AssignTarget; onClose: () => void }) {
  const remote = isRemoteMode()
  const connected = useIsDeskConnected()
  const { data: types } = useControlSurfaceTypeListQuery()
  const { data: catalogue } = useSurfaceFunctionsQuery()
  const { data: maps } = useSurfaceMapsQuery()
  const devices = useSurfaceDevices()
  const allControls = useSurfaceControls()
  const { attributesFor } = useBindingChoices(projectId)
  const [writeSlot, { isLoading: assigning }] = useWriteSurfaceSlotMutation()
  const elsewhere = target.projectId != null && target.projectId !== projectId

  // The types worth offering: those attached, then those the map has; failing both, every profile.
  const offered = useMemo(() => {
    const all = types ?? []
    const keys = new Set<string>([
      ...devices.flatMap((d) => (d.isMatched && d.typeKey != null ? [d.typeKey] : [])),
      ...Object.keys(maps ?? {}),
    ])
    const known = all.filter((t) => keys.has(t.typeKey) && t.controls.length > 0)
    return known.length > 0 ? known : all.filter((t) => t.controls.length > 0)
  }, [types, devices, maps])

  const [typeKey, setTypeKey] = useState<string | null>(null)
  const [pickedSlot, setSlot] = useState<Slot | null>(null)
  const [pickedLayer, setLayer] = useState<string | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  // A slot on a type no longer offered is dropped, so the sheet never shows one surface and writes another.
  const slot = pickedSlot != null && offered.some((t) => t.typeKey === pickedSlot.deviceTypeKey) ? pickedSlot : null
  const profile: ControlSurfaceType | null =
    offered.find((t) => t.typeKey === (slot?.deviceTypeKey ?? typeKey)) ?? offered[0] ?? null
  const layer = pickedLayer != null && profile?.banks.some((b) => b.id === pickedLayer) ? pickedLayer : null
  const frame = useSurfaceMap(profile?.typeKey)
  const liveFrame = frame != null && frame.projectId === projectId ? frame : null
  const revision = useFrameRevision(liveFrame)
  const { data: preview } = useSurfaceSlotPreviewQuery(
    slot == null ? skipToken : { projectId, slot: { ...slot, layer }, revision },
  )

  const onCapture = useCallback((capture: IdentifyCapture) => {
    setTypeKey(capture.deviceTypeKey)
    setSlot({ deviceTypeKey: capture.deviceTypeKey, controlId: capture.controlId, part: capture.part })
  }, [])
  const identify = useIdentify(onCapture)
  const { begin } = identify
  const identifyControls = useMemo(
    () => ({ wait: identify.wait, begin: () => begin(null), cancel: identify.cancel }),
    [identify.wait, identify.cancel, begin],
  )
  // The sheet opens waiting for a touch, except in remote mode, where the picture is the only way.
  useEffect(() => {
    if (!remote && connected && !elsewhere) begin(null)
    // Once, on open: a later reconnect does not start another wait the operator did not ask for.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `begin` is stable (useIdentify's useCallback with no deps); `connected` is read at open only, by design
  }, [])

  const descriptor = slot == null || profile == null ? null : (profile.controls.find((c) => c.controlId === slot.controlId) ?? null)
  const input = descriptor == null || slot == null ? null : inputOf(descriptor, slot.part)
  const fits = useMemo(
    () => (input == null ? [] : fitEntries(catalogue ?? NO_CATALOGUE, target.subjects, input)),
    [catalogue, target.subjects, input],
  )
  const fitCatalogue = useMemo(() => fits.map((fit) => fit.entry), [fits])
  const at = slot == null ? '' : `${slot.deviceTypeKey}|${slot.controlId}|${slot.part}`
  const initial = fits[0] == null ? null : { binding: initialBinding(fits[0]), entryId: fits[0].entry.id }
  const shown = draft?.at === at ? draft : initial == null ? null : { at, ...initial, takeoverPolicy: null }
  const shownEntry = shown == null ? null : (fitCatalogue.find((e) => e.id === shown.entryId) ?? null)
  const whole = shown != null && shownEntry != null && isWhole(shownEntry, shown.binding)

  const choices = useMemo(() => choicesFor(target), [target])

  const pickControl = (controlId: string) => {
    if (profile == null) return
    const control = profile.controls.find((c) => c.controlId === controlId)
    if (control == null) return
    const parts = partsOf(control)
    const part =
      parts.find((p) => {
        const kind = inputOf(control, p)
        return kind != null && fitEntries(catalogue ?? NO_CATALOGUE, target.subjects, kind).length > 0
      }) ?? parts[0]
    if (part == null) return
    setSlot({ deviceTypeKey: profile.typeKey, controlId, part })
  }

  const place = preview?.place ?? descriptor?.label ?? slot?.controlId ?? ''
  const assign = () => {
    if (slot == null || shown == null || !whole || profile == null) return
    const where = place
    writeSlot({
      projectId,
      slot: { deviceTypeKey: slot.deviceTypeKey, controlId: slot.controlId, part: slot.part, layer },
      binding: shown.binding,
      takeoverPolicy: shown.takeoverPolicy,
    })
      .unwrap()
      .then(
        () => {
          toast.success(`Assigned ${target.name} to ${where}`)
          onClose()
        },
        (e: { data?: { message?: string } }) => toast.error(e?.data?.message ?? 'The desk refused the binding'),
      )
  }

  const device = profile == null ? null : devices.find((d) => d.typeKey === profile.typeKey)
  const controls = device != null ? (allControls[device.displayKey] ?? NO_CONTROLS) : NO_CONTROLS
  const parts = liveFrame?.layers[layer ?? ''] ?? {}
  const replaces = preview?.replaces ?? []

  return (
    <Sheet open onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="flex flex-col sm:max-w-xl" data-testid="assign-to-surface">
        <SheetHeader>
          <SheetTitle>Assign {target.name} to a surface</SheetTitle>
          <SheetDescription>
            {remote ? 'Click a control on the picture.' : 'Touch a control on the desk, or click one on the picture.'}
          </SheetDescription>
        </SheetHeader>
        <SheetBody className="space-y-3">
          {elsewhere ? (
            <p className="text-sm text-muted-foreground">Surfaces are bound in the current show. Open this one to assign its records.</p>
          ) : profile == null ? (
            <p className="text-sm text-muted-foreground">No control surface is known to the desk.</p>
          ) : (
            <>
              {!remote && <IdentifyButton identify={identifyControls} connected={connected} />}
              {offered.length > 1 && (
                <Chips
                  label="Surface"
                  value={profile.typeKey}
                  options={offered.map((t) => ({ value: t.typeKey, label: t.product ?? t.typeKey }))}
                  onChange={(value) => {
                    setTypeKey(value)
                    setSlot(null)
                    setLayer(null)
                  }}
                />
              )}
              {profile.banks.length > 0 && (
                <Chips
                  label="Layer"
                  value={layer ?? ''}
                  options={[{ value: '', label: 'Both' }, ...profile.banks.map((b) => ({ value: b.id, label: `${b.name} only` }))]}
                  onChange={(value) => setLayer(value === '' ? null : value)}
                />
              )}
              <div className="overflow-x-auto rounded-md border p-2">
                <SurfacePanel
                  profile={profile}
                  controls={controls}
                  parts={parts}
                  viewedLayer={layer}
                  pickups={{}}
                  selectedControlId={slot?.deviceTypeKey === profile.typeKey ? slot.controlId : null}
                  onSelectControl={pickControl}
                />
              </div>
              {slot != null && descriptor != null && (
                <section className="space-y-2 rounded-lg border p-3" data-testid="assign-slot">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xs font-semibold">{place}</span>
                    {partsOf(descriptor).length > 1 && (
                      <Chips
                        label="Part"
                        value={slot.part}
                        options={partsOf(descriptor).map((p) => ({ value: p, label: PART_WORDS[p] }))}
                        onChange={(value) => setSlot({ ...slot, part: value as ControlPart })}
                      />
                    )}
                  </div>
                  {input == null || fits.length === 0 ? (
                    <p className="text-[11px] text-muted-foreground">
                      Nothing {target.name} can do fits {slot.part === 'PUSH' ? 'a push' : `this ${descriptor.type === 'fader' ? 'fader' : descriptor.type === 'encoder' ? 'encoder' : 'button'}`}.
                    </p>
                  ) : (
                    <BindingForm
                      key={at}
                      catalogue={fitCatalogue}
                      input={input}
                      binding={shown?.binding ?? null}
                      entryId={shown?.entryId ?? null}
                      takeoverPolicy={shown?.takeoverPolicy ?? null}
                      subjects={choices}
                      attributesFor={attributesFor}
                      layers={profile.banks}
                      motor={descriptor.type === 'fader' && descriptor.hasMotor}
                      disabled={!connected}
                      onChange={(binding, takeoverPolicy, entryId) => {
                        if (binding == null || entryId == null) return
                        setDraft({ at, binding, entryId, takeoverPolicy: takeoverPolicy ?? null })
                      }}
                    />
                  )}
                  <p className="text-[11px] text-muted-foreground" data-testid="assign-replaces">
                    {replaces.length > 0 ? replaces.join(' ') : 'Nothing is bound there yet.'}
                  </p>
                </section>
              )}
            </>
          )}
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={assign}
            disabled={!connected || !whole || assigning || elsewhere}
            title={connected ? undefined : DESK_OFFLINE_LABEL}
          >
            Assign
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/** Counts the map frames [frame] has been, so a query keyed on it asks again when the map moves. */
function useFrameRevision(frame: SurfaceMapFrame | null): number {
  const [seen, setSeen] = useState({ frame, revision: 0 })
  if (seen.frame === frame) return seen.revision
  setSeen({ frame, revision: seen.revision + 1 })
  return seen.revision + 1
}

/** The form's *On* offers the record alone; a palette's press still takes the selection. */
function choicesFor(target: AssignTarget): SubjectChoices {
  const records = { ...EMPTY_RECORD_OPTIONS } as Record<keyof typeof EMPTY_RECORD_OPTIONS, { uuid: string; label: string; detail: string | null }[]>
  const groups: { key: string; label: string }[] = []
  const fixtures: { key: string; label: string }[] = []
  for (const subject of target.subjects) {
    if (subject.type === 'group') groups.push({ key: subject.key, label: target.name })
    else if (subject.type === 'fixture') fixtures.push({ key: subject.key, label: target.name })
    else records[subject.kind] = [...records[subject.kind], { uuid: subject.uuid, label: target.name, detail: null }]
  }
  return { groups, fixtures, records }
}

function Chips({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string }[]
  onChange: (value: string) => void
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-1">
      <span className="mr-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</span>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
          className={cn(
            'rounded-md border px-2 py-0.5 text-[11px]',
            option.value === value ? 'border-primary bg-primary/10 text-foreground' : 'text-muted-foreground hover:bg-accent',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

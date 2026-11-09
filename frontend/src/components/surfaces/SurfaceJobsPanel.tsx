import { useMemo, useState } from "react"
import { AlertTriangle, ArrowDown, ArrowUp, X } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import {
  useDeleteSurfaceJobMutation,
  usePutSurfaceJobMutation,
  type CatalogueEntry,
  type ControlSurfaceType,
  type JobItem,
  type JobItemKind,
  type JobKindInfo,
  type JobRegionDefinition,
  type RecordKind,
  type SurfaceBinding,
  type SurfaceJobItemView,
  type SurfaceJobSlot,
  type SurfaceJobView,
  type SurfaceMapFrame,
} from "@/store/surfaces"
import { DESK_OFFLINE_LABEL } from "@/api/wsGesture"
import { colourVar, type FixtureColour } from "@/lib/fixtureColours"
import { BindingForm, type SubjectChoices } from "./BindingForm"
import { useBindingChoices } from "./useBindingChoices"

/**
 * Editor B (surface-setup plan D11, D12, §4): a job for each region of the desk. Each region the
 * profile names takes one of the job kinds that fit it — the desk says which — and its items in
 * order, reordered with up and down. A job stays live: reorder its items and the controls move with
 * them; a control bound by hand in the inspector leaves the job. Every change is one job write, and
 * what the controls then do comes back on the map.
 */
export interface SurfaceJobsPanelProps {
  projectId: number
  profile: ControlSurfaceType
  frame: SurfaceMapFrame | null
  catalogue: readonly CatalogueEntry[]
  kinds: readonly JobKindInfo[]
  connected: boolean
  /** The region being edited, lit on the picture. */
  focused: string | null
  onFocus: (region: string) => void
}

const NOTHING = ""

export function SurfaceJobsPanel({ projectId, profile, frame, catalogue, kinds, connected, focused, onFocus }: SurfaceJobsPanelProps) {
  const [layer, setLayer] = useState<string | null>(null)
  const choices = useBindingChoices(projectId)
  const [putJob] = usePutSurfaceJobMutation()
  const [deleteJob] = useDeleteSurfaceJobMutation()
  const regions = profile.jobRegions ?? []

  const slotOf = (region: string): SurfaceJobSlot => ({ deviceTypeKey: profile.typeKey, region, layer })
  const refused = (fallback: string) => (e: { data?: { error?: string; message?: string } }) => {
    toast.error(e?.data?.error ?? e?.data?.message ?? fallback)
  }
  const write = (region: string, kind: string, items: (JobItem | null)[]) =>
    putJob({ projectId, slot: slotOf(region), kind, items }).unwrap().catch(refused("The desk refused the job"))
  const clear = (region: string) => deleteJob({ projectId, slot: slotOf(region) }).unwrap().catch(refused("The desk could not take the job off"))

  if (regions.length === 0) {
    return <p className="p-4 text-xs text-muted-foreground">This device names no regions a job can fill.</p>
  }

  return (
    <div className="space-y-3 overflow-y-auto p-4" data-testid="surface-jobs">
      <div className="space-y-1">
        <div className="text-sm font-semibold">Jobs</div>
        <p className="text-[11px] text-muted-foreground">
          A job fills a whole region. It stays live: reorder its items and the controls move with them. A control bound by hand leaves the job.
        </p>
        {profile.banks.length > 0 && (
          <div role="group" aria-label="Jobs on" className="flex flex-wrap gap-1 pt-1">
            {[{ id: null as string | null, label: "Both layers" }, ...profile.banks.map((bank) => ({ id: bank.id, label: `Layer ${bank.name} only` }))].map(
              (option) => (
                <button
                  key={option.id ?? ""}
                  type="button"
                  aria-pressed={layer === option.id}
                  onClick={() => setLayer(option.id)}
                  className={cn(
                    "rounded-md border px-2 py-0.5 text-[11px]",
                    layer === option.id ? "border-primary bg-primary/10 text-foreground" : "text-muted-foreground hover:bg-accent",
                  )}
                >
                  {option.label}
                </button>
              ),
            )}
          </div>
        )}
      </div>
      {regions.map((region) => (
        <RegionJob
          key={region.id}
          region={region}
          job={frame?.jobs?.find((j) => j.region === region.id && (j.bank ?? null) === layer) ?? null}
          kinds={kinds}
          catalogue={catalogue}
          choices={choices}
          profile={profile}
          connected={connected}
          focused={focused === region.id}
          onFocus={() => onFocus(region.id)}
          onWrite={(kind, items) => write(region.id, kind, items)}
          onClear={() => clear(region.id)}
        />
      ))}
    </div>
  )
}

function RegionJob({
  region,
  job,
  kinds,
  catalogue,
  choices,
  profile,
  connected,
  focused,
  onFocus,
  onWrite,
  onClear,
}: {
  region: JobRegionDefinition
  job: SurfaceJobView | null
  kinds: readonly JobKindInfo[]
  catalogue: readonly CatalogueEntry[]
  choices: ReturnType<typeof useBindingChoices>
  profile: ControlSurfaceType
  connected: boolean
  focused: boolean
  onFocus: () => void
  onWrite: (kind: string, items: (JobItem | null)[]) => void
  onClear: () => void
}) {
  const fitting = kinds.filter((kind) => region.kinds.includes(kind.id))
  const kind = job == null ? null : (kinds.find((k) => k.id === job.kind) ?? null)
  const views = job?.items ?? []
  const items = views.map((view) => view?.item ?? null)
  const most = kind?.maxItems ?? region.slots.length
  const disabled = !connected || (job != null && job.readable === false)

  const pick = (next: string) => {
    if (next === NOTHING) {
      if (job != null) onClear()
      return
    }
    const nextKind = kinds.find((k) => k.id === next)
    onWrite(next, nextKind?.item != null && nextKind.item === kind?.item ? items.slice(0, nextKind.maxItems ?? region.slots.length) : [])
  }
  const move = (from: number, to: number) => {
    if (kind == null || to < 0 || to >= items.length) return
    const next = [...items]
    ;[next[from], next[to]] = [next[to], next[from]]
    onWrite(kind.id, next)
  }
  const remove = (at: number) => kind && onWrite(kind.id, items.map((item, i) => (i === at ? null : item)))
  const add = (item: JobItem) => {
    if (kind == null) return
    const gap = items.indexOf(null)
    onWrite(kind.id, gap < 0 ? [...items, item] : items.map((it, i) => (i === gap ? item : it)))
  }
  const room = items.includes(null) || items.length < most

  return (
    <section
      className={cn("space-y-2 rounded-lg border p-3", focused && "border-primary/60 bg-primary/5")}
      data-region={region.id}
      onFocusCapture={onFocus}
      onPointerDown={onFocus}
    >
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-xs font-semibold">{region.name}</span>
        <span className="text-[10px] text-muted-foreground">
          {region.slots.length} slot{region.slots.length === 1 ? "" : "s"}
        </span>
      </div>
      <select
        aria-label={`${region.name} job`}
        className="h-7 w-full rounded-md border bg-background px-1 text-xs"
        value={job?.kind ?? NOTHING}
        disabled={!connected}
        title={connected ? undefined : DESK_OFFLINE_LABEL}
        onChange={(e) => pick(e.target.value)}
      >
        <option value={NOTHING}>Nothing: bind each control yourself</option>
        {job != null && kind == null && <option value={job.kind}>Unknown ({job.kind})</option>}
        {fitting.map((option) => (
          <option key={option.id} value={option.id}>
            {option.label}
          </option>
        ))}
      </select>
      {job != null && job.readable === false && (
        <p className="text-[11px] text-[var(--editor-warning)]">This desk cannot read this job; it is kept and does nothing.</p>
      )}
      {kind && <p className="text-[11px] text-muted-foreground">{kind.help}</p>}
      {kind?.item != null && (
        <>
          <ol className="space-y-1" aria-label={`${region.name} items`}>
            {views.map((view, at) => (
              <ItemRow
                key={at}
                at={at}
                view={view}
                count={views.length}
                disabled={disabled}
                onUp={() => move(at, at - 1)}
                onDown={() => move(at, at + 1)}
                onRemove={() => remove(at)}
              />
            ))}
          </ol>
          {room ? (
            <AddItem
              kind={kind}
              catalogue={catalogue}
              choices={choices}
              profile={profile}
              taken={items}
              disabled={disabled}
              onAdd={add}
            />
          ) : (
            <p className="text-[11px] text-muted-foreground">Every slot has an item.</p>
          )}
        </>
      )}
    </section>
  )
}

function ItemRow({
  at,
  view,
  count,
  disabled,
  onUp,
  onDown,
  onRemove,
}: {
  at: number
  view: SurfaceJobItemView | null
  count: number
  disabled: boolean
  onUp: () => void
  onDown: () => void
  onRemove: () => void
}) {
  if (view == null) {
    return (
      <li className="flex items-center gap-1.5 rounded-md border border-dashed px-1.5 py-1 text-xs" data-item={at} data-empty>
        <span className="w-4 shrink-0 text-right text-[10px] text-muted-foreground">{at + 1}</span>
        <span className="min-w-0 flex-1 truncate text-muted-foreground/60">Empty</span>
      </li>
    )
  }
  const label = view.label
  return (
    <li className="flex items-center gap-1.5 rounded-md border px-1.5 py-1 text-xs" data-item={at}>
      <span className="w-4 shrink-0 text-right text-[10px] text-muted-foreground">{at + 1}</span>
      {view.colour && <span className="h-3 w-[3px] shrink-0 rounded-full" style={{ background: colourVar(view.colour as FixtureColour) }} />}
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {view.healthText && (
        <span title={view.healthText} className="text-destructive">
          <AlertTriangle className="size-3" />
        </span>
      )}
      <Button variant="ghost" size="icon" className="size-6" aria-label={`Move ${label} up`} disabled={disabled || at === 0} onClick={onUp}>
        <ArrowUp className="size-3" />
      </Button>
      <Button variant="ghost" size="icon" className="size-6" aria-label={`Move ${label} down`} disabled={disabled || at === count - 1} onClick={onDown}>
        <ArrowDown className="size-3" />
      </Button>
      <Button variant="ghost" size="icon" className="size-6" aria-label={`Take ${label} off`} disabled={disabled} onClick={onRemove}>
        <X className="size-3" />
      </Button>
    </li>
  )
}

const RECORD_KINDS: Partial<Record<JobItemKind, RecordKind>> = {
  STACK: "STACK",
  MASTER: "MASTER",
  PALETTE: "PALETTE",
  LOOK: "LOOK",
}

/** The options an item of [kind] can be, as `{value, label}`, keyed so [itemOf] reads one back. */
export function itemOptions(kind: JobItemKind, choices: SubjectChoices): { value: string; label: string }[] {
  if (kind === "GROUP") return choices.groups.map((g) => ({ value: `group:${g.key}`, label: g.label }))
  if (kind === "FIXTURE") return choices.fixtures.map((f) => ({ value: `fixture:${f.key}`, label: f.label }))
  const record = RECORD_KINDS[kind]
  if (record == null) return []
  return choices.records[record].map((r) => ({ value: `record:${record}:${r.uuid}`, label: r.detail ? `${r.label} · ${r.detail}` : r.label }))
}

/** An option's value as the item it stands for. */
export function itemOf(value: string): JobItem | null {
  const [type, ...rest] = value.split(":")
  if (type === "group" || type === "fixture") return { type, key: rest.join(":") }
  if (type === "record" && rest.length >= 2) return { type: "record", kind: rest[0] as RecordKind, uuid: rest.slice(1).join(":") }
  return null
}

function AddItem({
  kind,
  catalogue,
  choices,
  profile,
  taken,
  disabled,
  onAdd,
}: {
  kind: JobKindInfo
  catalogue: readonly CatalogueEntry[]
  choices: ReturnType<typeof useBindingChoices>
  profile: ControlSurfaceType
  taken: readonly (JobItem | null)[]
  disabled: boolean
  onAdd: (item: JobItem) => void
}) {
  const [adding, setAdding] = useState(false)
  const [pending, setPending] = useState<{ binding: SurfaceBinding; entryId: string } | null>(null)
  const entries = useMemo(() => catalogue.filter((entry) => kind.entries?.includes(entry.id)), [catalogue, kind.entries])
  const close = () => {
    setPending(null)
    setAdding(false)
  }
  if (kind.item === "FUNCTION") {
    const input = entries.every((entry) => entry.inputs.includes("CONTINUOUS")) ? "CONTINUOUS" : "BUTTON"
    if (!adding) {
      return (
        <Button variant="outline" size="sm" disabled={disabled} onClick={() => setAdding(true)}>
          Add {kind.id === "encoderPages" ? "a page" : "a wheel"}…
        </Button>
      )
    }
    return (
      <div className="space-y-2 rounded-md border border-dashed p-2" data-testid="job-add-function">
        <BindingForm
          name="New item"
          catalogue={entries}
          input={input}
          binding={pending?.binding ?? null}
          entryId={pending?.entryId ?? null}
          subjects={choices.subjects}
          attributesFor={choices.attributesFor}
          layers={profile.banks}
          disabled={disabled}
          onChange={(binding, _policy, entryId) => setPending(binding != null && entryId != null ? { binding, entryId } : null)}
        />
        <div className="flex gap-2">
          <Button
            size="sm"
            disabled={disabled || pending == null}
            onClick={() => {
              if (pending == null) return
              const { does, on } = pending.binding
              onAdd({ type: "function", does, ...(on ? { on } : {}) })
              close()
            }}
          >
            Add
          </Button>
          <Button variant="ghost" size="sm" onClick={close}>
            Cancel
          </Button>
        </div>
      </div>
    )
  }
  const takenValues = new Set(taken.flatMap((item) => (item == null || item.type === "function" ? [] : [itemValue(item)])))
  const options = itemOptions(kind.item!, choices.subjects).filter((option) => !takenValues.has(option.value))
  return (
    <select
      aria-label="Add an item"
      className="h-7 w-full rounded-md border bg-background px-1 text-xs"
      value=""
      disabled={disabled || options.length === 0}
      onChange={(e) => {
        const item = itemOf(e.target.value)
        if (item) onAdd(item)
      }}
    >
      <option value="">{options.length === 0 ? "Nothing more to add" : "Add…"}</option>
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  )
}

function itemValue(item: Exclude<JobItem, { type: "function" }>): string {
  return item.type === "record" ? `record:${item.kind}:${item.uuid}` : `${item.type}:${item.key}`
}

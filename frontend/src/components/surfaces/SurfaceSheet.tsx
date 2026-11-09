import { useCallback, useMemo, useState } from "react"
import { AlertTriangle, X } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import { EditorSurface } from "@/components/editor/EditorSurface"
import { EditorLabelLine } from "@/components/editor/EditorLabelLine"
import { useEditorOpen } from "@/components/editor/useEditorOpen"
import { SpreadPanel, type SpreadPlan } from "@/components/editor/SpreadPanel"
import { CellSelectionActions } from "@/components/sheet/CellSelectionActions"
import { SelectionBar } from "@/components/sheet/SelectionBar"
import { SheetTable } from "@/components/sheet/SheetTable"
import { OptionCell } from "@/components/sheet/cells/OptionCell"
import { useSheet } from "@/components/sheet/useSheet"
import { listNames, type SheetCellProps, type SheetColumn, type SheetRow } from "@/components/sheet/sheetModel"
import {
  useClearSurfaceSlotMutation,
  useWriteSurfaceSlotMutation,
  type CatalogueEntry,
  type ControlDescriptor,
  type ControlKind,
  type ControlPart,
  type ControlSurfaceType,
  type ResolvedPart,
  type SurfaceBinding,
  type SurfaceMapFrame,
  type SurfaceSlot,
  type TakeoverPolicy,
} from "@/store/surfaces"
import { inputOf, partKey, partsOf } from "@/api/surfacesApi"
import { DESK_OFFLINE_LABEL } from "@/api/wsGesture"
import { colourVar, type FixtureColour } from "@/lib/fixtureColours"
import { CLEAR_FAILED } from "./SurfaceInspector"
import { BindingForm, WRITES_TO_WORDS, subjectKindOf, subjectOf, subjectOptions, subjectValue, type SubjectChoices } from "./BindingForm"
import { useBindingChoices } from "./useBindingChoices"

/**
 * Editor C (surface-setup plan D12, §4): every control part as a row of the sheet kit, grouped by
 * the picture's regions — Control · Layer · Does · On · How · Writes to · Shows · Health. Settings ›
 * Surfaces' table view, and the only view below tablet width. A part a job derives reads out what the
 * job gives it; the job itself is edited in the Jobs view.
 *
 * *Does* is the compound binding cell, edited through the inspector's form. *On* takes a marquee:
 * one subject set over many rows, or **Fill in order** — groups, fixtures, stacks or masters one to a
 * row — where a row that cannot take the value is skipped and named (`SheetColumn.accepts`). Live
 * control values are kept out of the rows, so a fader moving never re-renders the sheet.
 */
export interface SurfaceSheetProps {
  projectId: number
  profile: ControlSurfaceType
  frame: SurfaceMapFrame | null
  catalogue: readonly CatalogueEntry[]
  viewedLayer: string | null
  connected: boolean
}

type Col = "layer" | "does" | "on" | "how" | "writesTo" | "shows" | "health"

interface PartRow extends SheetRow {
  controlId: string
  part: ControlPart
  name: string
  sub: string | null
  input: ControlKind
  descriptor: ControlDescriptor | null
  resolved: ResolvedPart
  entry: CatalogueEntry | null
}

const NO_PART: ResolvedPart = { from: "NONE" }
const PART_SUB: Record<ControlPart, string> = { MAIN: "", PUSH: "push" }

/** The rows, in region order; dead rows first when asked. */
export function sheetRows(
  profile: ControlSurfaceType,
  parts: Readonly<Record<string, ResolvedPart>>,
  catalogue: readonly CatalogueEntry[],
  deadFirst: boolean,
): PartRow[] {
  const entryOf = (part: ResolvedPart) => catalogue.find((e) => e.id === part.entry) ?? null
  const groups: { name: string; rows: PartRow[] }[] = []
  const byId = new Map(profile.controls.map((c) => [c.controlId, c]))
  const regions = profile.layout?.regions ?? [{ name: "Controls", columns: 1, cells: profile.controls.map((c, row) => ({ controlId: c.controlId, col: 0, row })) }]
  for (const region of regions) {
    const ordered = [...region.cells].sort((a, b) => a.row - b.row || a.col - b.col)
    const rows: PartRow[] = []
    for (const cell of ordered) {
      const descriptor = byId.get(cell.controlId)
      if (!descriptor) continue
      for (const part of partsOf(descriptor)) {
        const resolved = parts[partKey(descriptor.controlId, part)] ?? NO_PART
        rows.push({
          id: partKey(descriptor.controlId, part),
          controlId: descriptor.controlId,
          part,
          name: descriptor.label,
          sub: PART_SUB[part] || null,
          input: inputOf(descriptor, part)!,
          descriptor,
          resolved,
          entry: entryOf(resolved),
        })
      }
    }
    if (rows.length > 0) groups.push({ name: region.name, rows })
  }
  const isDead = (row: PartRow) => row.resolved.from !== "NONE" && row.resolved.health != null && row.resolved.health.type !== "ok"
  const out: PartRow[] = []
  if (deadFirst) {
    const dead = groups.flatMap((g) => g.rows.filter(isDead))
    if (dead.length > 0) out.push(divider("Dead"), ...dead)
  }
  for (const group of groups) {
    const rows = deadFirst ? group.rows.filter((row) => !isDead(row)) : group.rows
    if (rows.length > 0) out.push(divider(group.name), ...rows)
  }
  return out
}

function divider(name: string): PartRow {
  return {
    id: `divider:${name}`,
    divider: name,
    controlId: "",
    part: "MAIN",
    name,
    sub: null,
    input: "BUTTON",
    descriptor: null,
    resolved: NO_PART,
    entry: null,
  }
}

/**
 * Whether [row] takes [on]: its function acts on that kind of subject (D2). A part a job derives
 * takes nothing: its subject is the job's item.
 */
export function rowTakesSubject(row: PartRow, on: string): boolean {
  if (row.entry == null || row.resolved.from === "JOB") return false
  return row.entry.subjects.includes(subjectKindOf(subjectOf(on)))
}

/** The lists *Fill in order* offers, each as the subject values it lays down. */
export function fillLists(choices: SubjectChoices): { key: string; label: string; values: string[] }[] {
  return [
    { key: "GROUP", label: "Groups, in order", values: subjectOptions(["GROUP"], choices).map((o) => o.value) },
    { key: "FIXTURE", label: "Fixtures, in order", values: subjectOptions(["FIXTURE"], choices).map((o) => o.value) },
    { key: "STACK", label: "Cue stacks, in order", values: subjectOptions(["STACK"], choices).map((o) => o.value) },
    { key: "MASTER", label: "Speed masters, in order", values: subjectOptions(["MASTER"], choices).map((o) => o.value) },
    { key: "SELECTION", label: "The selection", values: ["selection"] },
  ]
}

/**
 * The fill itself: each row in order takes the next value its function accepts; a row that takes
 * none, or has nothing bound, is skipped and named. Answers the writes and the skips.
 */
export function planFill(
  rows: readonly PartRow[],
  list: { key: string; values: string[] },
): { writes: { row: PartRow; on: string }[]; skipped: string[] } {
  const writes: { row: PartRow; on: string }[] = []
  const skipped: string[] = []
  let next = 0
  for (const row of rows) {
    const label = row.sub ? `${row.name} ${row.sub}` : row.name
    if (row.resolved.from === "JOB") {
      skipped.push(`${label} follows its job`)
      continue
    }
    if (row.entry == null) {
      skipped.push(`${label} does nothing yet`)
      continue
    }
    const value = list.key === "SELECTION" ? "selection" : list.values[next]
    if (value == null) {
      skipped.push(`${label}: no more to fill`)
      continue
    }
    if (!rowTakesSubject(row, value)) {
      skipped.push(`${label} (${row.entry.label}) can’t take ${list.key === "SELECTION" ? "the selection" : "that"}`)
      continue
    }
    writes.push({ row, on: value })
    if (list.key !== "SELECTION") next += 1
  }
  return { writes, skipped }
}

export function SurfaceSheet({ projectId, profile, frame, catalogue, viewedLayer, connected }: SurfaceSheetProps) {
  const parts = useMemo(() => frame?.layers[viewedLayer ?? ""] ?? {}, [frame, viewedLayer])
  const [deadFirst, setDeadFirst] = useState(false)
  const rows = useMemo(() => sheetRows(profile, parts, catalogue, deadFirst), [profile, parts, catalogue, deadFirst])
  const choices = useBindingChoices(projectId)
  const [writeSlot] = useWriteSurfaceSlotMutation()
  const [clearSlot] = useClearSurfaceSlotMutation()

  const slotOf = useCallback(
    (row: PartRow): SurfaceSlot => ({
      deviceTypeKey: profile.typeKey,
      controlId: row.controlId,
      part: row.part,
      layer: row.resolved.from === "DIRECT" ? (row.resolved.bank ?? null) : null,
    }),
    [profile.typeKey],
  )
  const bindingOf = (row: PartRow): SurfaceBinding | null =>
    row.resolved.does == null ? null : { does: row.resolved.does, on: row.resolved.on ?? undefined, how: row.resolved.how ?? undefined }

  const write = useCallback(
    (row: PartRow, binding: SurfaceBinding | null, takeoverPolicy?: TakeoverPolicy | null) => {
      const request = binding == null ? clearSlot({ projectId, slot: slotOf(row) }) : writeSlot({ projectId, slot: slotOf(row), binding, takeoverPolicy })
      request.unwrap().catch((e: { data?: { message?: string } }) => {
        toast.error(e?.data?.message ?? (binding == null ? CLEAR_FAILED : "The desk refused the binding"))
      })
    },
    [projectId, slotOf, writeSlot, clearSlot],
  )

  const setOn = useCallback(
    (row: PartRow, on: string) => {
      const binding = bindingOf(row)
      const subject = subjectOf(on)
      if (binding == null || subject == null) return
      write(row, { ...binding, on: subject }, row.resolved.takeoverPolicy)
    },
    [write],
  )

  const onOptions = useMemo(
    () => subjectOptions(["SELECTION", "GROUP", "FIXTURE", "STACK", "ENTRY", "CUE", "LOOK", "PAD", "BANK", "PAGE", "MASTER"], choices.subjects).map(
      (option) => ({ value: option.value, label: option.group ? `${option.label} · ${option.group.replace(/s$/, "").toLowerCase()}` : option.label }),
    ),
    [choices.subjects],
  )

  const columns = useMemo<SheetColumn<PartRow, Col>[]>(
    () => [
      {
        key: "layer",
        label: "Layer",
        width: "84px",
        value: () => undefined,
        display: (row) => <span className="text-xs text-muted-foreground">{layerWord(row, profile)}</span>,
      },
      {
        key: "does",
        label: "Does",
        width: "minmax(140px,1.2fr)",
        value: (row) => row.entry?.id ?? "",
        cell: (row, props) => (
          <BindingCell
            {...(props as SheetCellProps<string>)}
            row={row}
            catalogue={catalogue}
            choices={choices}
            profile={profile}
            disabled={props.disabled || !connected}
            onWrite={(binding, policy) => write(row, binding, policy)}
          />
        ),
      },
      {
        key: "on",
        label: "On",
        width: "minmax(140px,1fr)",
        kind: "subject",
        value: (row) =>
          row.entry == null || row.resolved.from === "JOB" || !row.entry.subjects.some((k) => k !== "NONE" && k !== "SLOT")
            ? undefined
            : subjectValue(row.resolved.on),
        display: (row) => (row.resolved.from === "JOB" ? <SubjectFace part={row.resolved} /> : null),
        accepts: (row, value) => typeof value === "string" && rowTakesSubject(row, value),
        refused: (refusedRows) =>
          toast.warning(`Skipped ${listNames(refusedRows.map((r) => (r.sub ? `${r.name} ${r.sub}` : r.name)))}: it can’t take that`),
        write: (batch, value) => {
          for (const row of batch) setOn(row, value as string)
          return true
        },
        cell: (row, props) => (
          <OptionCell
            {...(props as SheetCellProps<string>)}
            options={onOptions}
            disabled={props.disabled || !connected}
            face={<SubjectFace part={row.resolved} />}
          />
        ),
        spread: (batch) => {
          const lists = fillLists(choices.subjects)
          const plan: SpreadPlan = {
            kind: "records",
            col: "on",
            label: "On",
            count: batch.length,
            lists: lists.map(({ key, label }) => ({ key, label })),
            apply: (key) => {
              const list = lists.find((l) => l.key === key)
              if (!list) return
              const { writes, skipped } = planFill(batch, list)
              for (const { row, on } of writes) setOn(row, on)
              toast.message(
                `Filled ${writes.length}.` + (skipped.length ? ` Skipped ${skipped.length}: ${skipped.slice(0, 2).join("; ")}${skipped.length > 2 ? "…" : "."}` : ""),
              )
            },
          }
          return plan
        },
      },
      {
        key: "how",
        label: "How",
        width: "110px",
        value: () => undefined,
        display: (row) => <span className="text-xs text-muted-foreground">{howWord(row)}</span>,
      },
      {
        key: "writesTo",
        label: "Writes to",
        width: "84px",
        value: () => undefined,
        display: (row) =>
          row.resolved.writesTo ? (
            <span className="rounded-full border px-1.5 text-[10px] text-muted-foreground">{WRITES_TO_WORDS[row.resolved.writesTo]}</span>
          ) : null,
      },
      {
        key: "shows",
        label: "Shows",
        width: "minmax(160px,1.4fr)",
        value: () => undefined,
        display: (row) => (
          <span className="truncate text-[11px] text-muted-foreground" title={row.resolved.shows ?? undefined}>
            {row.resolved.shows ?? ""}
          </span>
        ),
      },
      {
        key: "health",
        label: "Health",
        width: "minmax(90px,0.8fr)",
        value: () => undefined,
        display: (row) =>
          row.resolved.healthText ? (
            <span className="flex items-center gap-1 truncate text-[11px] text-destructive" title={row.resolved.healthText}>
              <AlertTriangle className="size-3 shrink-0" />
              {row.resolved.healthText}
            </span>
          ) : null,
      },
    ],
    [catalogue, choices, connected, onOptions, profile, setOn, write],
  )

  const sheet = useSheet<PartRow, Col>({
    rows,
    columns,
    permission: { entry: connected, clear: false },
    copy: () => ({
      setTitle: connected ? "Set the selected cells" : DESK_OFFLINE_LABEL,
      clearTitle: "Take a binding off in its Does cell",
    }),
    noun: "control",
    rowName: (row) => (row.sub ? `${row.name} ${row.sub}` : row.name),
    selectsRows: false,
  })

  const cellCount = sheet.cellCount
  const verbs =
    cellCount > 0 ? (
      <div className="flex shrink-0 items-center gap-1.5">
        <CellSelectionActions
          copy={sheet.copy}
          permission={sheet.permission}
          setRef={sheet.setButtonRef}
          onSet={sheet.toggleCellEditor}
          onClear={sheet.clearSelectedCells}
          spread={
            <SpreadPanel
              host="popover"
              plans={sheet.spreadPlans}
              triggerWord="Fill"
              disabledReason={connected ? null : DESK_OFFLINE_LABEL}
              drivableHint="On"
            />
          }
        />
        <Button variant="ghost" size="sm" onClick={sheet.clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  return (
    <div className="flex min-h-0 flex-1 flex-col" data-testid="surface-sheet">
      <div className="flex flex-wrap items-center gap-2 pb-2">
        <SelectionBar
          rowLabel={null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? "" : "s"}` : null}
          family={null}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: false }}
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
          addLatch={sheet.addLatch}
        />
        <button
          type="button"
          aria-pressed={deadFirst}
          onClick={() => setDeadFirst((on) => !on)}
          className={cn("ml-auto rounded-md border px-2 py-0.5 text-xs", deadFirst ? "border-destructive text-destructive" : "text-muted-foreground")}
        >
          Dead first
        </button>
      </div>
      <SheetTable<PartRow, Col>
        {...sheet.tableProps}
        minWidth="880px"
        firstColumn={{
          label: "Control",
          width: "150px",
          render: (row) => (
            <span className="flex min-w-0 items-baseline gap-1.5 truncate text-xs">
              {row.name}
              {row.sub && <span className="text-[10px] text-muted-foreground">{row.sub}</span>}
            </span>
          ),
        }}
      />
    </div>
  )
}

function SubjectFace({ part }: { part: ResolvedPart }) {
  return (
    <span className="mx-1.5 flex min-w-0 items-center gap-1.5 truncate text-xs">
      {part.colour && <span className="h-3 w-[3px] shrink-0 rounded-full" style={{ background: colourVar(part.colour as FixtureColour) }} />}
      {part.subjectLabel ?? "—"}
    </span>
  )
}

function layerWord(row: PartRow, profile: ControlSurfaceType): string {
  if (row.resolved.from === "NONE") return ""
  const bank = row.resolved.bank
  const layer = bank == null ? "Both" : `${profile.banks.find((b) => b.id === bank)?.name ?? bank} only`
  return row.resolved.from === "JOB" ? `Job · ${layer}` : layer
}

function howWord(row: PartRow): string {
  if (row.entry == null) return ""
  if (row.input === "CONTINUOUS") {
    if (row.descriptor?.type === "fader" && row.descriptor.hasMotor) return "Motor follows"
    return row.resolved.takeoverPolicy === "IMMEDIATE" ? "Jump" : "Pick up"
  }
  const how = row.resolved.how ?? row.entry.defaultHow
  return how == null ? "Press" : HOW_LABELS[how]
}

const HOW_LABELS = {
  TOGGLE: "Toggle",
  WHILE_HELD: "While held",
  ON: "On",
  OFF: "Off",
  TOGGLE_HOLD_REPLACES: "Toggle, hold to replace",
  REPLACE: "Replace",
} as const

/** The compound cell: the part's whole binding, edited through the inspector's form. */
function BindingCell({
  row,
  catalogue,
  choices,
  profile,
  disabled,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  editorAnchorRef,
  onBeginEdit,
  onWrite,
}: SheetCellProps<string> & {
  row: PartRow
  catalogue: readonly CatalogueEntry[]
  choices: ReturnType<typeof useBindingChoices>
  profile: ControlSurfaceType
  onWrite: (binding: SurfaceBinding | null, takeoverPolicy?: TakeoverPolicy | null) => void
}) {
  const { isOpen, setOpen, atButton } = useEditorOpen({ autoOpen, autoClose, anchorAtButton, keyboardSeed, disabled, selectionEmpty })
  const resolved = row.resolved
  const label = row.entry == null ? (resolved.from === "NONE" ? "Nothing" : (resolved.label ?? "Unknown")) : row.entry.label
  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={row.sub ? `${row.name} ${row.sub}` : row.name}
      contentClassName="w-80"
      triggerOpens={false}
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button type="button" disabled={disabled} onClick={onBeginEdit} className="flex h-full w-full items-center rounded text-left hover:bg-accent/50">
          <span className={cn("mx-1.5 truncate text-xs", row.entry == null && "text-muted-foreground/60")}>{label}</span>
        </button>
      }
    >
      <div className="space-y-2">
        <EditorLabelLine subject={row.sub ? `${row.name} ${row.sub}` : row.name} column="Does" />
        {resolved.from === "JOB" ? (
          <p className="text-[11px] text-muted-foreground">
            From {resolved.jobLabel ?? "its region's job"}: {resolved.label}. The Jobs view says what the job holds.
          </p>
        ) : (
          <BindingForm
            catalogue={catalogue}
            input={row.input}
            binding={resolved.does == null ? null : { does: resolved.does, on: resolved.on ?? undefined, how: resolved.how ?? undefined }}
            entryId={resolved.entry ?? null}
            takeoverPolicy={resolved.takeoverPolicy ?? null}
            subjects={choices.subjects}
            attributesFor={choices.attributesFor}
            layers={profile.banks}
            motor={row.descriptor?.type === "fader" && row.descriptor.hasMotor}
            disabled={disabled}
            onChange={onWrite}
          />
        )}
      </div>
    </EditorSurface>
  )
}

import { useMemo, useState } from "react"
import { ChevronDown, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { cn } from "@/lib/utils"
import type {
  BankDefinition,
  CatalogueEntry,
  CatalogueParam,
  ControlKind,
  How,
  RecordKind,
  SubjectKind,
  SurfaceBinding,
  SurfaceDoes,
  SurfaceSubject,
  TakeoverPolicy,
  WritesTo,
} from "@/store/surfaces"
import type { RecordOptions } from "./recordOptions"
import type { AvailableAttribute } from "@/hooks/useTargetAttributes"

/**
 * One control part's binding as a form (surface-setup plan D1, D12): **Does** — the catalogue,
 * grouped and searchable, filtered to what the part's input reaches — then its parameters, **On**,
 * **How**, and the layer it applies on, with the *Writes to* word. Every function is drawn from the
 * catalogue the desk serves, so a new function needs no screen work here.
 *
 * A change is handed up only once the binding is whole (a function, every required parameter, a
 * subject the function takes); until then the form holds the draft.
 */

export interface SubjectChoices {
  groups: readonly { key: string; label: string }[]
  fixtures: readonly { key: string; label: string }[]
  records: RecordOptions
}

export interface BindingFormProps {
  catalogue: readonly CatalogueEntry[]
  /** What the part takes: a continuous move, a button, or a strip slot. */
  input: ControlKind | "STRIP"
  binding: SurfaceBinding | null
  /** The catalogue entry [binding] is, as the desk's map names it. */
  entryId: string | null
  takeoverPolicy?: TakeoverPolicy | null
  subjects: SubjectChoices
  /** The attributes a subject's heads have, for an attribute parameter. */
  attributesFor: (on: SurfaceSubject | null) => readonly AvailableAttribute[]
  /** The device's layers, for *Switch layer*'s parameter and the layer chips. */
  layers: readonly BankDefinition[]
  /** Which layer the binding is stored on, null for both; omit to draw no layer chips. */
  layer?: string | null
  onLayerChange?: (layer: string | null) => void
  /** A motor fader follows what it shows: its How is a sentence, not a choice. */
  motor?: boolean
  disabled?: boolean
  /** A whole binding, or null to take it off. */
  onChange: (binding: SurfaceBinding | null, takeoverPolicy?: TakeoverPolicy | null) => void
  /** Prefix for the fields' accessible names: `Turn`, `Push`. */
  name?: string
}

export const WRITES_TO_WORDS: Record<WritesTo, string> = {
  LOCAL: "Local",
  PLAYBACK: "Playback",
  MASTER: "Master",
  TEMPO: "Tempo",
  DESK: "Desk",
}

const HOW_WORDS: Record<How, string> = {
  TOGGLE: "Toggle",
  WHILE_HELD: "While held",
  ON: "On",
  OFF: "Off",
  TOGGLE_HOLD_REPLACES: "Toggle, hold to replace",
  REPLACE: "Replace",
}

const RECORD_GROUP_WORDS: Record<string, string> = {
  STACK: "Cue stacks",
  ENTRY: "Cues in stacks",
  CUE: "Cues",
  LOOK: "Looks",
  PALETTE: "Palettes",
  PAD: "Busk pads",
  BANK: "Busk banks",
  PAGE: "Busk pages",
  MASTER: "Speed masters",
}

/** The pill naming where a function writes (D8). */
export function WritesToPill({ writesTo }: { writesTo: WritesTo | null | undefined }) {
  if (writesTo == null) return null
  return (
    <span
      data-writes-to={writesTo}
      className="rounded-full border px-1.5 py-px text-[10px] font-medium text-muted-foreground"
    >
      Writes to {WRITES_TO_WORDS[writesTo]}
    </span>
  )
}

/** The entries a part's input reaches: a strip slot takes the strip, a control part what its input drives. */
export function entriesFor(catalogue: readonly CatalogueEntry[], input: ControlKind | "STRIP"): CatalogueEntry[] {
  return catalogue.filter((entry) => (input === "STRIP" ? entry.strip === true : !entry.strip && entry.inputs.includes(input)))
}

/** A subject as the value of a select: `none`, `selection`, `group:<key>`, `fixture:<key>`, `record:<KIND>:<uuid>`. */
export function subjectValue(on: SurfaceSubject | null | undefined): string {
  if (on == null) return "none"
  switch (on.type) {
    case "none":
    case "selection":
    case "slot":
      return on.type
    case "group":
    case "fixture":
      return `${on.type}:${on.key}`
    case "record":
      return `record:${on.kind}:${on.uuid}`
  }
}

export function subjectOf(value: string): SurfaceSubject | null {
  if (value === "none" || value === "selection" || value === "slot") return { type: value }
  const [type, ...rest] = value.split(":")
  if (type === "group" || type === "fixture") return { type, key: rest.join(":") }
  if (type === "record" && rest.length >= 2) {
    return { type: "record", kind: rest[0] as RecordKind, uuid: rest.slice(1).join(":") }
  }
  return null
}

/** The kind a subject is, as the catalogue lists the kinds an entry takes. */
export function subjectKindOf(on: SurfaceSubject | null | undefined): SubjectKind {
  if (on == null) return "NONE"
  switch (on.type) {
    case "none":
      return "NONE"
    case "selection":
      return "SELECTION"
    case "slot":
      return "SLOT"
    case "group":
      return "GROUP"
    case "fixture":
      return "FIXTURE"
    case "record":
      return on.kind
  }
}

/** Every subject [choices] offers for the kinds [kinds], as `{value, label, group}`. */
export function subjectOptions(
  kinds: readonly SubjectKind[],
  choices: SubjectChoices,
): { value: string; label: string; group: string }[] {
  const out: { value: string; label: string; group: string }[] = []
  for (const kind of kinds) {
    switch (kind) {
      case "NONE":
      case "SLOT":
        break
      case "SELECTION":
        out.push({ value: "selection", label: "The selection", group: "" })
        break
      case "GROUP":
        for (const group of choices.groups) out.push({ value: `group:${group.key}`, label: group.label, group: "Groups" })
        break
      case "FIXTURE":
        for (const fixture of choices.fixtures) out.push({ value: `fixture:${fixture.key}`, label: fixture.label, group: "Fixtures" })
        break
      default:
        for (const record of choices.records[kind]) {
          out.push({
            value: `record:${kind}:${record.uuid}`,
            label: record.detail ? `${record.label} · ${record.detail}` : record.label,
            group: RECORD_GROUP_WORDS[kind] ?? kind,
          })
        }
    }
  }
  return out
}

/** A fresh function from [entry], each parameter at its default. */
export function doesFor(entry: CatalogueEntry, keep?: SurfaceDoes | null): SurfaceDoes {
  const does: Record<string, unknown> = { type: entry.does }
  for (const param of entry.params) {
    const kept = keep != null && keep.type === entry.does ? (keep as unknown as Record<string, unknown>)[param.key] : undefined
    if (kept !== undefined) does[param.key] = kept
    else if (param.default != null) does[param.key] = param.default
  }
  return does as unknown as SurfaceDoes
}

/** Is [binding] whole for [entry]: every required parameter set, and a subject it takes. */
export function isWhole(entry: CatalogueEntry, binding: SurfaceBinding): boolean {
  const does = binding.does as unknown as Record<string, unknown>
  if (entry.params.some((param) => param.required && (does[param.key] == null || does[param.key] === ""))) return false
  return entry.subjects.includes(subjectKindOf(binding.on))
}

/** The first subject [entry] takes that [choices] can fill, keeping [current] where it fits. */
function defaultSubject(entry: CatalogueEntry, current: SurfaceSubject | null | undefined, choices: SubjectChoices): SurfaceSubject | undefined {
  if (current != null && entry.subjects.includes(subjectKindOf(current))) return current
  if (entry.subjects.includes("NONE")) return undefined
  const first = subjectOptions(entry.subjects, choices)[0]
  return first ? (subjectOf(first.value) ?? undefined) : undefined
}

export function BindingForm({
  catalogue,
  input,
  binding,
  entryId,
  takeoverPolicy,
  subjects,
  attributesFor,
  layers,
  layer,
  onLayerChange,
  motor,
  disabled,
  onChange,
  name = "",
}: BindingFormProps) {
  const offered = useMemo(() => entriesFor(catalogue, input), [catalogue, input])
  // A binding not yet whole — a function picked, its subject still to choose — and the entry it is.
  const [draft, setDraft] = useState<{ binding: SurfaceBinding; entryId: string } | null>(null)
  const shown = draft?.binding ?? binding
  const shownEntryId = draft?.entryId ?? entryId
  const entry = shownEntryId == null ? null : (catalogue.find((e) => e.id === shownEntryId) ?? null)

  const propose = (
    next: SurfaceBinding,
    nextEntry: CatalogueEntry | null = entry,
    policy: TakeoverPolicy | null | undefined = takeoverPolicy,
  ) => {
    if (nextEntry == null) return
    if (isWhole(nextEntry, next)) {
      setDraft(null)
      onChange(next, policy)
    } else {
      setDraft({ binding: next, entryId: nextEntry.id })
    }
  }

  const pickEntry = (picked: CatalogueEntry | null) => {
    if (picked == null) {
      setDraft(null)
      onChange(null)
      return
    }
    const on = defaultSubject(picked, shown?.on, subjects)
    propose({ does: doesFor(picked, entry?.does === picked.does ? shown?.does : null), ...(on ? { on } : {}) }, picked)
  }

  const setParam = (param: CatalogueParam, value: unknown) => {
    if (shown == null) return
    const does = { ...(shown.does as unknown as Record<string, unknown>) }
    if (value == null || value === "") delete does[param.key]
    else does[param.key] = value
    propose({ ...shown, does: does as unknown as SurfaceDoes })
  }

  const fieldName = (label: string) => (name ? `${name} ${label.toLowerCase()}` : label)

  return (
    <div className="flex flex-col gap-2" data-binding-form>
      <Row label="Does">
        <DoesPicker entries={offered} current={entry} disabled={disabled} label={fieldName("Does")} onPick={pickEntry} />
      </Row>
      {entry != null && shown != null && (
        <>
          {entry.params.map((param) => (
            <ParamField
              key={param.key}
              param={param}
              does={shown.does}
              on={shown.on ?? null}
              attributesFor={attributesFor}
              layers={layers}
              records={subjects.records}
              disabled={disabled}
              label={fieldName(param.label)}
              onChange={(value) => setParam(param, value)}
            />
          ))}
          {entry.subjects.some((kind) => kind !== "NONE" && kind !== "SLOT") && (
            <Row label="On">
              <SubjectSelect
                kinds={entry.subjects}
                value={shown.on ?? null}
                choices={subjects}
                disabled={disabled}
                label={fieldName("On")}
                onChange={(on) => propose({ ...shown, on: on ?? { type: "none" } })}
              />
            </Row>
          )}
          <HowRow
            entry={entry}
            how={shown.how ?? null}
            input={input}
            motor={motor}
            takeoverPolicy={takeoverPolicy ?? null}
            disabled={disabled}
            onHow={(how) => propose({ ...shown, how })}
            onTakeover={(policy) => propose(shown, entry, policy)}
          />
          {onLayerChange != null && layers.length > 0 && (
            <Row label="Layer">
              <Chips
                label={fieldName("Layer")}
                value={layer ?? ""}
                disabled={disabled}
                options={[
                  { value: "", label: "Both layers" },
                  ...layers.map((bank) => ({ value: bank.id, label: `${bank.name} only` })),
                ]}
                onChange={(value) => onLayerChange(value === "" ? null : value)}
              />
            </Row>
          )}
          <div className="flex items-center gap-2">
            <WritesToPill writesTo={entry.writesTo} />
            <span className="text-[11px] text-muted-foreground">{entry.help}</span>
          </div>
          {draft != null && <p className="text-[11px] text-[var(--editor-warning)]">Choose what it acts on to bind it.</p>}
        </>
      )}
    </div>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[52px_minmax(0,1fr)] items-center gap-2">
      <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{label}</span>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

function Chips({
  label,
  value,
  options,
  disabled,
  onChange,
}: {
  label: string
  value: string
  options: readonly { value: string; label: string }[]
  disabled?: boolean
  onChange: (value: string) => void
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          disabled={disabled}
          onClick={() => onChange(option.value)}
          className={cn(
            "rounded-md border px-2 py-0.5 text-[11px]",
            option.value === value ? "border-primary bg-primary/10 text-foreground" : "text-muted-foreground hover:bg-accent",
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/** The catalogue as a grouped, searchable list. */
function DoesPicker({
  entries,
  current,
  disabled,
  label,
  onPick,
}: {
  entries: readonly CatalogueEntry[]
  current: CatalogueEntry | null
  disabled?: boolean
  label: string
  onPick: (entry: CatalogueEntry | null) => void
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState("")
  const groups = useMemo(() => {
    const q = query.trim().toLowerCase()
    const out = new Map<string, CatalogueEntry[]>()
    for (const entry of entries) {
      if (q && !entry.label.toLowerCase().includes(q) && !entry.group.toLowerCase().includes(q)) continue
      out.set(entry.group, [...(out.get(entry.group) ?? []), entry])
    }
    return [...out.entries()]
  }, [entries, query])

  return (
    <Popover open={open} onOpenChange={(next) => { setOpen(next); if (!next) setQuery("") }}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className="h-7 w-full justify-between text-xs" disabled={disabled} aria-label={label}>
          <span className={cn("truncate", current == null && "text-muted-foreground")}>{current?.label ?? "Nothing"}</span>
          <ChevronDown className="size-3.5 opacity-60" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-2">
        <div className="relative mb-1.5">
          <Search className="absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            autoFocus
            aria-label="Find a function"
            placeholder="Find a function"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                const first = groups[0]?.[1][0]
                if (first) {
                  onPick(first)
                  setOpen(false)
                }
              }
            }}
            className="h-7 pl-7 text-xs"
          />
        </div>
        <div className="max-h-72 overflow-y-auto" role="listbox" aria-label="Functions">
          <button
            type="button"
            role="option"
            aria-selected={current == null}
            className="w-full rounded px-2 py-1 text-left text-xs text-muted-foreground hover:bg-accent"
            onClick={() => {
              onPick(null)
              setOpen(false)
            }}
          >
            Nothing
          </button>
          {groups.map(([group, list]) => (
            <div key={group} className="mt-1">
              <div className="px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{group}</div>
              {list.map((entry) => (
                <button
                  key={entry.id}
                  type="button"
                  role="option"
                  aria-selected={current?.id === entry.id}
                  title={entry.help}
                  className={cn("w-full rounded px-2 py-1 text-left text-xs hover:bg-accent", current?.id === entry.id && "bg-accent")}
                  onClick={() => {
                    onPick(entry)
                    setOpen(false)
                  }}
                >
                  {entry.label}
                </button>
              ))}
            </div>
          ))}
          {groups.length === 0 && <p className="px-2 py-1 text-xs text-muted-foreground">No function matches.</p>}
        </div>
      </PopoverContent>
    </Popover>
  )
}

function SubjectSelect({
  kinds,
  value,
  choices,
  disabled,
  label,
  onChange,
}: {
  kinds: readonly SubjectKind[]
  value: SurfaceSubject | null
  choices: SubjectChoices
  disabled?: boolean
  label: string
  onChange: (on: SurfaceSubject | null) => void
}) {
  const options = useMemo(() => subjectOptions(kinds, choices), [kinds, choices])
  const groups = useMemo(() => {
    const out = new Map<string, typeof options>()
    for (const option of options) out.set(option.group, [...(out.get(option.group) ?? []), option])
    return [...out.entries()]
  }, [options])
  const current = subjectValue(value)
  const known = options.some((option) => option.value === current)
  return (
    <Select value={known ? current : undefined} onValueChange={(next) => onChange(subjectOf(next))} disabled={disabled}>
      <SelectTrigger className="h-7 w-full text-xs" aria-label={label}>
        <SelectValue placeholder={kinds.includes("NONE") ? "Nothing" : "Choose…"} />
      </SelectTrigger>
      <SelectContent>
        {kinds.includes("NONE") && <SelectItem value="none">Nothing</SelectItem>}
        {groups.map(([group, list]) =>
          group === "" ? (
            list.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))
          ) : (
            <SelectGroup key={group}>
              <SelectLabel>{group}</SelectLabel>
              {list.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectGroup>
          ),
        )}
      </SelectContent>
    </Select>
  )
}

function ParamField({
  param,
  does,
  on,
  attributesFor,
  layers,
  records,
  disabled,
  label,
  onChange,
}: {
  param: CatalogueParam
  does: SurfaceDoes
  on: SurfaceSubject | null
  attributesFor: (on: SurfaceSubject | null) => readonly AvailableAttribute[]
  layers: readonly BankDefinition[]
  records: RecordOptions
  disabled?: boolean
  label: string
  onChange: (value: unknown) => void
}) {
  const fields = does as unknown as Record<string, unknown>
  const value = fields[param.key]
  // A colour axis means something only on a colour attribute.
  if (param.key === "axis" && "attribute" in fields) {
    const attribute = attributesFor(on).find((a) => a.name === fields.attribute)
    if (attribute?.type !== "colour") return null
  }
  switch (param.kind) {
    case "ATTRIBUTE": {
      const attributes = attributesFor(on).filter((a) => a.continuous)
      const current = typeof value === "string" ? value : ""
      const listed = attributes.some((a) => a.name === current)
      return (
        <Row label={param.label}>
          <Select value={current || undefined} onValueChange={onChange} disabled={disabled}>
            <SelectTrigger className="h-7 w-full text-xs" aria-label={label}>
              <SelectValue placeholder="Choose…" />
            </SelectTrigger>
            <SelectContent>
              {!listed && current && <SelectItem value={current}>{current}</SelectItem>}
              {attributes.map((attribute) => (
                <SelectItem key={attribute.name} value={attribute.name}>
                  {attribute.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Row>
      )
    }
    case "CHOICE": {
      const options = (param.options ?? []).map((option) => ({ value: String(option.value), label: option.label }))
      const current = value == null ? (param.default != null ? String(param.default) : "") : String(value)
      const toValue = (raw: string) => param.options?.find((option) => String(option.value) === raw)?.value ?? raw
      if (options.length <= 4) {
        return (
          <Row label={param.label}>
            <Chips label={label} value={current} options={options} disabled={disabled} onChange={(raw) => onChange(toValue(raw))} />
          </Row>
        )
      }
      return (
        <Row label={param.label}>
          <Select value={current || undefined} onValueChange={(raw) => onChange(toValue(raw))} disabled={disabled}>
            <SelectTrigger className="h-7 w-full text-xs" aria-label={label}>
              <SelectValue placeholder="Choose…" />
            </SelectTrigger>
            <SelectContent>
              {options.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Row>
      )
    }
    case "LAYER":
      return (
        <Row label={param.label}>
          <Chips
            label={label}
            value={typeof value === "string" ? value : ""}
            options={layers.map((bank) => ({ value: bank.id, label: `Layer ${bank.name}` }))}
            disabled={disabled}
            onChange={onChange}
          />
        </Row>
      )
    case "NUMBER":
      return (
        <Row label={param.label}>
          <NumberField
            label={label}
            value={typeof value === "number" ? value : typeof param.default === "number" ? param.default : null}
            min={param.min ?? undefined}
            max={param.max ?? undefined}
            disabled={disabled}
            onCommit={onChange}
          />
        </Row>
      )
    case "TEXT":
      return (
        <Row label={param.label}>
          <TextField label={label} value={typeof value === "string" ? value : ""} disabled={disabled} onCommit={onChange} />
        </Row>
      )
    case "RECORD": {
      const options = param.record == null ? [] : records[param.record]
      const current = typeof value === "string" ? value : ""
      return (
        <Row label={param.label}>
          <Select value={current || undefined} onValueChange={onChange} disabled={disabled}>
            <SelectTrigger className="h-7 w-full text-xs" aria-label={label}>
              <SelectValue placeholder="Choose…" />
            </SelectTrigger>
            <SelectContent>
              {options.map((option) => (
                <SelectItem key={option.uuid} value={option.uuid}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Row>
      )
    }
  }
}

function NumberField({
  label,
  value,
  min,
  max,
  disabled,
  onCommit,
}: {
  label: string
  value: number | null
  min?: number
  max?: number
  disabled?: boolean
  onCommit: (value: number | null) => void
}) {
  const [text, setText] = useState<string | null>(null)
  const commit = () => {
    if (text == null) return
    const parsed = text.trim() === "" ? null : Number(text)
    setText(null)
    if (parsed != null && (!Number.isFinite(parsed) || (min != null && parsed < min) || (max != null && parsed > max))) return
    onCommit(parsed)
  }
  return (
    <Input
      type="number"
      aria-label={label}
      className="h-7 text-xs tabular-nums"
      min={min}
      max={max}
      disabled={disabled}
      value={text ?? (value == null ? "" : String(value))}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") commit()
      }}
    />
  )
}

function TextField({
  label,
  value,
  disabled,
  onCommit,
}: {
  label: string
  value: string
  disabled?: boolean
  onCommit: (value: string) => void
}) {
  const [text, setText] = useState<string | null>(null)
  const commit = () => {
    if (text == null) return
    setText(null)
    onCommit(text.trim())
  }
  return (
    <Input
      aria-label={label}
      className="h-7 text-xs"
      disabled={disabled}
      value={text ?? value}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") commit()
      }}
    />
  )
}

function HowRow({
  entry,
  how,
  input,
  motor,
  takeoverPolicy,
  disabled,
  onHow,
  onTakeover,
}: {
  entry: CatalogueEntry
  how: How | null
  input: ControlKind | "STRIP"
  motor?: boolean
  takeoverPolicy: TakeoverPolicy | null
  disabled?: boolean
  onHow: (how: How | null) => void
  onTakeover: (policy: TakeoverPolicy | null) => void
}) {
  if (input === "BUTTON") {
    if (entry.hows.length <= 1) return null
    const current = how ?? entry.defaultHow ?? ""
    return (
      <Row label="How">
        <Chips
          label="How"
          value={current}
          options={entry.hows.map((h) => ({ value: h, label: HOW_WORDS[h] }))}
          disabled={disabled}
          onChange={(value) => onHow(value === entry.defaultHow ? null : (value as How))}
        />
      </Row>
    )
  }
  if (input !== "CONTINUOUS") return null
  if (motor) {
    return (
      <Row label="How">
        <span className="text-[11px] text-muted-foreground">The motor follows; it holds still while touched.</span>
      </Row>
    )
  }
  return (
    <Row label="How">
      <Chips
        label="How"
        value={takeoverPolicy === "IMMEDIATE" ? "IMMEDIATE" : "PICKUP"}
        options={[
          { value: "PICKUP", label: "Pick up" },
          { value: "IMMEDIATE", label: "Jump" },
        ]}
        disabled={disabled}
        onChange={(value) => onTakeover(value === "PICKUP" ? null : "IMMEDIATE")}
      />
    </Row>
  )
}

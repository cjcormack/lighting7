import { useMemo, useState } from "react"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SpeedMasterSelect } from "@/components/fx/SpeedMasterSelect"
import { useGroupListQuery } from "@/store/groups"
import { usePatchListQuery } from "@/store/patches"
import { useProjectCueStackListQuery } from "@/store/cueStacks"
import { useRigProperties, useTargetProperties, type AvailableProperty } from "@/hooks/useTargetProperties"
import {
  COLOUR_AXES,
  COLOUR_AXIS_LONG_LABELS,
  effectiveAxis,
  withAxis,
  type ColourAxis,
} from "@/lib/colourAxis"
import {
  useRecordBindingOptions,
  type RecordBindingOptions,
  type RecordOption,
} from "./recordOptions"
import type { BindingTarget, TakeoverPolicy } from "@/store/surfaces"
import type { CueTarget } from "@/api/cuesApi"
import { SUBSELECT_MODES, type SubselectMode } from "@/api/selectionApi"
import { useDeskWindows } from "@/store/windows"
import { BUSK_FOCUSES, type BuskFocus } from "@/lib/buskWindow"
import { SUBSELECT_MODE_LABELS } from "@/lib/cellsSubSelection"
import { FOCUS_LABELS } from "./targetUtils"

/**
 * The form behind *Change target* and MIDI Learn's commit.
 *
 * The library's drag is how a binding is normally made now (D9); this is the other door, and it has
 * to stay able to say what comes through it. It carried the twelve variants that existed before the
 * selection and strip work, so the five the drag mints — selection property, select, clear, locate
 * and encoder bank — read as *Fixture property* over an empty body until session 3b widened it.
 *
 * Two variants are deliberately **not** offerable, and each is rendered rather than offered:
 * `strip` addresses a strip id and the backend refuses it on a control (`refuseWrongSlot`), so it
 * appears only when it is already the value and edits its group; and `unknown` is a row this build
 * could not decode, which `refuseUnknown` will not accept back — it exists to be *rebound*, which
 * is what the notice below invites.
 */

interface BindingTargetPickerProps {
  projectId: number
  /** True if the selected control is a fader/encoder (continuous). False for buttons. */
  continuous: boolean
  value: BindingTarget
  onChange: (target: BindingTarget) => void
  policy: TakeoverPolicy | null
  onPolicyChange: (policy: TakeoverPolicy | null) => void
}

/**
 * Every `BindingTarget` variant offerable from the "Target type" dropdown — everything except
 * `strip` and `unknown`, which are rendered (see below) but never offered as a kind to switch to.
 *
 * **Derived from `BindingTarget["type"]` on purpose, not hand-copied.** It used to be a literal
 * union kept in step by hand, and the gap that leaves is exactly what bit once already (see the
 * test file's comment on session 3a): a `BindingTarget` variant added in `surfacesApi.ts` with no
 * matching addition here compiled cleanly and opened an empty body under "Fixture property". Tying
 * the two together turns that into a compiler error instead — `KIND_LABELS` below is a `Record`
 * over this type, so a new variant with no label is a missing-key error, and `defaultForKind`'s
 * switch has no `default` case, so a new variant with no default is `TS2366` (verified: deleting a
 * case from that switch does not compile). `CONTINUOUS_KINDS`/`BUTTON_KINDS` are still plain
 * arrays a new variant could go missing from silently — the two checks above are what matters,
 * because a kind absent from both never reaches either list-driven Select, so its label and default
 * are what a reviewer would notice missing.
 */
type TargetKind = Exclude<BindingTarget["type"], "strip" | "unknown">

const CONTINUOUS_KINDS: TargetKind[] = [
  "fixtureProperty",
  "groupProperty",
  "selectionProperty",
  "speedMasterBpm",
]
const BUTTON_KINDS: TargetKind[] = [
  "flash",
  "selectTarget",
  "clearSelection",
  "locateSelection",
  "encoderBankSet",
  "cueStackGo",
  "cueStackBack",
  "cueStackPause",
  "fireCue",
  "fireTrigger",
  "blackout",
  "grandMasterToggle",
  "setBank",
  "speedMasterTap",
  "applyLook",
  "pressTemplate",
  "pressPad",
  "pickUpPad",
  "handPlaceInBank",
  "handDrop",
  "buskPageSet",
  "buskPageNext",
  "buskPagePrev",
  "buskFocusSet",
  "buskSheetToggle",
  "selectionNext",
  "selectionPrev",
  "selectionCells",
]

const KIND_LABELS: Record<TargetKind, string> = {
  fixtureProperty: "Fixture property",
  groupProperty: "Group property",
  selectionProperty: "Selection — property",
  cueStackGo: "Cue stack — Go",
  cueStackBack: "Cue stack — Back",
  cueStackPause: "Cue stack — Pause",
  fireCue: "Fire cue",
  fireTrigger: "Fire trigger (needs the arm)",
  flash: "Flash",
  selectTarget: "Selection — select",
  clearSelection: "Selection — clear",
  locateSelection: "Selection — locate",
  encoderBankSet: "Encoder bank",
  blackout: "Blackout",
  grandMasterToggle: "Grand Master",
  setBank: "Set bank",
  speedMasterBpm: "Speed master — BPM",
  speedMasterTap: "Speed master — Tap",
  applyLook: "Look — apply",
  pressTemplate: "Template — press",
  pressPad: "Busk pad — press",
  pickUpPad: "Hand — pick up pad",
  handPlaceInBank: "Hand — place in bank",
  handDrop: "Hand — let go",
  buskPageSet: "Busk page — show",
  buskPageNext: "Busk page — next",
  buskPagePrev: "Busk page — previous",
  buskFocusSet: "Busk window — focus",
  buskSheetToggle: "Busk window — sheet",
  selectionNext: "Selection — next",
  selectionPrev: "Selection — previous",
  selectionCells: "Selection — cells",
}

export function BindingTargetPicker({
  projectId,
  continuous,
  value,
  onChange,
  policy,
  onPolicyChange,
}: BindingTargetPickerProps) {
  const options = continuous ? CONTINUOUS_KINDS : BUTTON_KINDS
  const currentKind = value.type as TargetKind
  const [kind, setKind] = useState<TargetKind>(options.includes(currentKind) ? currentKind : options[0])

  const { data: groups } = useGroupListQuery()
  const { data: patches } = usePatchListQuery(projectId)
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  // The rig union is the only vocabulary a target-*less* binding has: neither a selection property
  // nor an encoder bank names a head to ask. A property no selected head declares simply drops its
  // move (D3), which is a fact about the selection rather than a bad binding.
  const rigProperties = useRigProperties()
  // The four uuid-addressed variants need a library to pick from; one owner for the lists and for
  // the name they resolve to, shared with the inspector's binding card.
  const records = useRecordBindingOptions(projectId)

  // Every lighting fixture, plus an infrastructure one only when this binding already names it: a
  // binding that names one keeps resolving (and reads as what it is), but none is ever offered.
  const boundKeys = useMemo(() => boundFixtureKeys(value), [value])
  const fixtureOptions = useMemo(
    () =>
      (patches ?? [])
        .filter((p) => !p.infrastructure || boundKeys.has(p.key))
        .map((p) => ({ key: p.key, label: p.infrastructure ? `${p.displayName} (infrastructure)` : p.displayName })),
    [patches, boundKeys],
  )
  const groupOptions = useMemo(
    () => (groups ?? []).map((g) => g.name),
    [groups],
  )
  const continuousProperties = useMemo(
    () => rigProperties.filter((p) => p.continuous),
    [rigProperties],
  )

  // A strip row edits its group and nothing else — that is what the inspector's *Change group*
  // means. It is never offered as a kind: a strip target on a control is refused by name
  // (`BINDING_STRIP_NEEDS_STRIP`), so putting it in the list would only be a way to hit a 400.
  if (value.type === "strip") {
    return (
      <div className="space-y-3">
        <p className="text-xs text-muted-foreground">
          A strip binding covers the whole channel strip — fader, select, encoder and flash. Change
          the group or fixture it follows; <em>Fader only…</em> is how it becomes single bindings.
        </p>
        <TargetRefFields
          value={value.target}
          onChange={(next) => onChange({ ...value, target: next })}
          fixtureOptions={fixtureOptions}
          groupOptions={groupOptions}
        />
      </div>
    )
  }

  function changeKind(next: TargetKind) {
    setKind(next)
    onChange(defaultForKind(next, fixtureOptions, groupOptions, stacks))
  }

  return (
    <div className="space-y-3">
      <div className="space-y-1.5">
        <Label className="text-xs">Target type</Label>
        <Select value={kind} onValueChange={(v) => changeKind(v as TargetKind)}>
          <SelectTrigger><SelectValue /></SelectTrigger>
          <SelectContent>
            {options.map((o) => (
              <SelectItem key={o} value={o}>{KIND_LABELS[o]}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {value.type === kind ? (
        <TargetBody
          kind={kind}
          value={value}
          onChange={onChange}
          fixtureOptions={fixtureOptions}
          groupOptions={groupOptions}
          stacks={stacks ?? []}
          properties={continuousProperties}
          records={records}
        />
      ) : (
        <p className="text-xs text-muted-foreground">
          {value.type === "unknown"
            ? `This control holds a “${value.targetType}” target that this build cannot decode. Pick a target type above to rebind it.`
            : "Pick a target type above."}
        </p>
      )}

      {/* D10: a selection property arms takeover exactly as a fixed one does — against the
          selection's common value, and against nothing when it is mixed. */}
      {continuous &&
        (value.type === "fixtureProperty" ||
          value.type === "groupProperty" ||
          value.type === "selectionProperty") && (
        <div className="space-y-1.5">
          <Label className="text-xs">Takeover policy</Label>
          <Select
            value={policy ?? "DEFAULT"}
            onValueChange={(v) => onPolicyChange(v === "DEFAULT" ? null : (v as TakeoverPolicy))}
          >
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="DEFAULT">Device default</SelectItem>
              <SelectItem value="IMMEDIATE">Immediate</SelectItem>
              <SelectItem value="PICKUP">Pickup</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}
    </div>
  )
}

/** A group-or-fixture picker over the shared `{ type, key }` target shape. */
function TargetRefFields({
  value,
  onChange,
  fixtureOptions,
  groupOptions,
}: {
  value: CueTarget
  onChange: (v: CueTarget) => void
  fixtureOptions: { key: string; label: string }[]
  groupOptions: string[]
}) {
  return (
    <div className="grid grid-cols-2 gap-2">
      <div className="space-y-1.5">
        <Label className="text-xs">Kind</Label>
        <Select
          value={value.type}
          onValueChange={(v) =>
            onChange({
              type: v as CueTarget["type"],
              key: v === "group" ? (groupOptions[0] ?? "") : (fixtureOptions[0]?.key ?? ""),
            })
          }
        >
          <SelectTrigger><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="group">Group</SelectItem>
            <SelectItem value="fixture">Fixture</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <div className="space-y-1.5">
        <Label className="text-xs">{value.type === "group" ? "Group" : "Fixture"}</Label>
        <Select value={value.key} onValueChange={(v) => onChange({ ...value, key: v })}>
          <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
          <SelectContent>
            {value.type === "group"
              ? groupOptions.map((g) => (
                  <SelectItem key={g} value={g}>{g}</SelectItem>
                ))
              : fixtureOptions.map((f) => (
                  <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
                ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}

/**
 * Which axis of a colour property a continuous binding drives — shown only when the named property
 * *is* a colour, since on anything else the write boundary refuses the axis by name
 * (`BINDING_AXIS_NEEDS_COLOUR`). Hue is shown as the default and written as **absent**: the wire
 * has no field for a hue binding, and `withAxis` is what keeps it that way.
 */
function ColourAxisField({
  value,
  onChange,
}: {
  value: ColourAxis | null | undefined
  onChange: (axis: ColourAxis) => void
}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">Colour axis</Label>
      <Select value={effectiveAxis(value)} onValueChange={(v) => onChange(v as ColourAxis)}>
        <SelectTrigger><SelectValue /></SelectTrigger>
        <SelectContent>
          {COLOUR_AXES.map((axis) => (
            <SelectItem key={axis} value={axis}>{COLOUR_AXIS_LONG_LABELS[axis]}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/**
 * The axis field for a fixture or group target, whose property is a free-text `Input` rather than
 * a pick from a list — so whether it names a colour is a per-target lookup, and `TargetBody` is an
 * if-chain a hook cannot sit inside. A component so `useTargetProperties` can be called once, here.
 */
function TargetAxisField({
  target,
  propertyName,
  value,
  onChange,
}: {
  target: { type: "fixture" | "group"; key: string }
  propertyName: string
  value: ColourAxis | null | undefined
  onChange: (axis: ColourAxis) => void
}) {
  const { properties } = useTargetProperties(target)
  if (properties.find((p) => p.name === propertyName)?.type !== "colour") return null
  return <ColourAxisField value={value} onChange={onChange} />
}

function PropertyField({
  value,
  onChange,
  properties,
}: {
  value: string
  onChange: (v: string) => void
  properties: { name: string; displayName: string }[]
}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">Property</Label>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
        <SelectContent>
          {properties.map((p) => (
            <SelectItem key={p.name} value={p.name}>{p.displayName}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

function TargetBody({
  kind,
  value,
  onChange,
  fixtureOptions,
  groupOptions,
  stacks,
  properties,
  records,
}: {
  kind: TargetKind
  value: BindingTarget
  onChange: (v: BindingTarget) => void
  records: RecordBindingOptions
  fixtureOptions: { key: string; label: string }[]
  groupOptions: string[]
  stacks: { id: number; name: string }[]
  properties: AvailableProperty[]
}) {
  // Changing the property drops the axis: a colour → dimmer edit that kept `saturation` would save
  // a 400 (`BINDING_AXIS_NEEDS_COLOUR`), and hue is the right default for a colour anyway. Changing
  // the *fixture* or *group* drops it for the same reason — the same property name is a colour on
  // one head and a slider on the next, so the swap can strand an axis the axis field then hides.
  const isColour = (name: string) => properties.find((p) => p.name === name)?.type === "colour"
  if (kind === "selectionProperty" && value.type === "selectionProperty") {
    return (
      <div className="space-y-2">
        <PropertyField
          value={value.propertyName}
          onChange={(propertyName) => onChange(withAxis({ ...value, propertyName }, null))}
          properties={properties}
        />
        {isColour(value.propertyName) && (
          <ColourAxisField
            value={value.colourAxis}
            onChange={(axis) => onChange(withAxis(value, axis))}
          />
        )}
        <p className="text-xs text-muted-foreground">
          Writes this property on every selected target. With nothing selected the move is dropped.
        </p>
      </div>
    )
  }
  if (kind === "encoderBankSet" && value.type === "encoderBankSet") {
    return (
      <div className="space-y-2">
        <PropertyField
          value={value.propertyName}
          onChange={(propertyName) => onChange(withAxis({ ...value, propertyName }, null))}
          properties={properties}
        />
        {isColour(value.propertyName) && (
          <ColourAxisField
            value={value.colourAxis}
            onChange={(axis) => onChange(withAxis(value, axis))}
          />
        )}
        <p className="text-xs text-muted-foreground">
          Switches what every strip encoder on this device drives. Its LED lights while this
          property — and, for a colour, this axis — is the active bank.
        </p>
      </div>
    )
  }
  if (kind === "selectTarget" && value.type === "selectTarget") {
    return (
      <div className="space-y-2">
        <TargetRefFields
          value={value.target}
          onChange={(target) => onChange({ ...value, target })}
          fixtureOptions={fixtureOptions}
          groupOptions={groupOptions}
        />
        <div className="space-y-1.5">
          <Label className="text-xs">On press</Label>
          <Select
            value={value.mode}
            onValueChange={(v) => onChange({ ...value, mode: v as typeof value.mode })}
          >
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="toggle">Toggle</SelectItem>
              <SelectItem value="replace">Replace the selection</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            A toggle also replaces if you hold it, so both are on one button either way.
          </p>
        </div>
      </div>
    )
  }
  if (kind === "clearSelection" || kind === "locateSelection") return null
  if (kind === "fixtureProperty" && value.type === "fixtureProperty") {
    return (
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1.5">
          <Label className="text-xs">Fixture</Label>
          <Select
            value={value.fixtureKey}
            onValueChange={(v) => onChange(withAxis({ ...value, fixtureKey: v }, null))}
          >
            <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
            <SelectContent>
              {fixtureOptions.map((f) => (
                <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">Property</Label>
          <Input
            value={value.propertyName}
            onChange={(e) => onChange(withAxis({ ...value, propertyName: e.target.value }, null))}
            placeholder="dimmer"
          />
        </div>
        <TargetAxisField
          target={{ type: "fixture", key: value.fixtureKey }}
          propertyName={value.propertyName}
          value={value.colourAxis}
          onChange={(axis) => onChange(withAxis(value, axis))}
        />
      </div>
    )
  }
  if (kind === "groupProperty" && value.type === "groupProperty") {
    return (
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1.5">
          <Label className="text-xs">Group</Label>
          <Select
            value={value.groupName}
            onValueChange={(v) => onChange(withAxis({ ...value, groupName: v }, null))}
          >
            <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
            <SelectContent>
              {groupOptions.map((g) => (
                <SelectItem key={g} value={g}>{g}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">Property</Label>
          <Input
            value={value.propertyName}
            onChange={(e) => onChange(withAxis({ ...value, propertyName: e.target.value }, null))}
            placeholder="dimmer"
          />
        </div>
        <TargetAxisField
          target={{ type: "group", key: value.groupName }}
          propertyName={value.propertyName}
          value={value.colourAxis}
          onChange={(axis) => onChange(withAxis(value, axis))}
        />
      </div>
    )
  }
  if (
    (kind === "cueStackGo" || kind === "cueStackBack" || kind === "cueStackPause") &&
    (value.type === "cueStackGo" || value.type === "cueStackBack" || value.type === "cueStackPause")
  ) {
    return (
      <div className="space-y-1.5">
        <Label className="text-xs">Cue stack</Label>
        <Select
          value={String(value.stackId)}
          onValueChange={(v) => onChange({ ...value, stackId: Number(v) })}
        >
          <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
          <SelectContent>
            {stacks.map((s) => (
              <SelectItem key={s.id} value={String(s.id)}>{s.name}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    )
  }
  if (kind === "fireCue" && value.type === "fireCue") {
    return (
      <div className="space-y-1.5">
        <Label className="text-xs">Cue ID</Label>
        <Input
          type="number"
          value={value.cueId}
          onChange={(e) => onChange({ ...value, cueId: Number(e.target.value) })}
        />
      </div>
    )
  }
  if (kind === "fireTrigger" && value.type === "fireTrigger") {
    return (
      <div className="space-y-2">
        <div className="space-y-1.5">
          <Label className="text-xs">Fixture</Label>
          <Select value={value.fixtureKey || undefined} onValueChange={(fixtureKey) => onChange({ ...value, fixtureKey })}>
            <SelectTrigger><SelectValue placeholder="Select a cannon…" /></SelectTrigger>
            <SelectContent>
              {fixtureOptions.map((f) => (
                <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">Tube (trigger name or label)</Label>
          <Input value={value.trigger} onChange={(e) => onChange({ ...value, trigger: e.target.value })} />
        </div>
        <p className="text-[11px] text-muted-foreground">Fires only while the desk is armed; unarmed, the press is announced and does nothing.</p>
      </div>
    )
  }
  if (kind === "flash" && value.type === "flash") {
    return (
      <div className="space-y-2">
        <TargetBody
          kind={value.target.type as TargetKind}
          value={value.target}
          onChange={(inner) =>
            onChange({
              ...value,
              target: inner as typeof value.target,
            })
          }
          fixtureOptions={fixtureOptions}
          groupOptions={groupOptions}
          stacks={stacks}
          properties={properties}
          records={records}
        />
        <div className="space-y-1.5">
          <Label className="text-xs">Max (0–255)</Label>
          <Input
            type="number"
            min={0}
            max={255}
            value={value.max ?? 255}
            onChange={(e) => onChange({ ...value, max: Number(e.target.value) })}
          />
        </div>
      </div>
    )
  }
  if (kind === "setBank" && value.type === "setBank") {
    return (
      <div className="grid grid-cols-2 gap-2">
        <div className="space-y-1.5">
          <Label className="text-xs">Device type</Label>
          <Input
            value={value.deviceTypeKey}
            onChange={(e) => onChange({ ...value, deviceTypeKey: e.target.value })}
          />
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">Bank</Label>
          <Input
            value={value.bank}
            onChange={(e) => onChange({ ...value, bank: e.target.value })}
          />
        </div>
      </div>
    )
  }

  if (kind === "speedMasterBpm" && value.type === "speedMasterBpm") {
    return (
      <div className="space-y-3">
        <SpeedMasterSelect
          value={value.masterUuid}
          onChange={(masterUuid) => onChange({ ...value, masterUuid })}
        />
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1.5">
            <Label className="text-xs">Min BPM</Label>
            <Input
              inputMode="decimal"
              value={String(value.minBpm)}
              onChange={(e) => onChange({ ...value, minBpm: clampBpm(e.target.value, value.minBpm) })}
            />
          </div>
          <div className="space-y-1.5">
            <Label className="text-xs">Max BPM</Label>
            <Input
              inputMode="decimal"
              value={String(value.maxBpm)}
              onChange={(e) => onChange({ ...value, maxBpm: clampBpm(e.target.value, value.maxBpm) })}
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">
          The control&apos;s full travel spans this range. Narrower is finer — the whole
          20–300 range over 128 steps is about 2 BPM a step.
        </p>
      </div>
    )
  }

  if (kind === "applyLook" && value.type === "applyLook") {
    return (
      <div className="space-y-2">
        <RecordField
          label="Look"
          value={value.lookUuid}
          onChange={(lookUuid) => onChange({ ...value, lookUuid })}
          options={records.looks}
        />
        <p className="text-xs text-muted-foreground">
          Presses the Look onto <em>its own fixtures</em>, the same every press — never onto the
          selection. A Look with a deferred effect has none of its own, and is refused.
        </p>
      </div>
    )
  }
  if (kind === "pressTemplate" && value.type === "pressTemplate") {
    return (
      <div className="space-y-2">
        <RecordField
          label="Template"
          value={value.templateUuid}
          onChange={(templateUuid) => onChange({ ...value, templateUuid })}
          options={records.templates}
        />
        <p className="text-xs text-muted-foreground">
          Presses onto the desk selection as a layer that tracks the template. With nothing
          selected a generic template&rsquo;s press is dropped.
        </p>
      </div>
    )
  }
  if (kind === "pressPad" && value.type === "pressPad") {
    return (
      <div className="space-y-2">
        <RecordField
          label="Busk pad"
          value={value.padUuid}
          onChange={(padUuid) => onChange({ ...value, padUuid })}
          options={records.pads}
        />
        <p className="text-xs text-muted-foreground">
          The pad&rsquo;s own press, solo siblings included — exactly what pressing it on the busk page
          does.
        </p>
      </div>
    )
  }
  if (kind === "pickUpPad" && value.type === "pickUpPad") {
    return (
      <div className="space-y-2">
        <RecordField
          label="Busk pad"
          value={value.padUuid}
          onChange={(padUuid) => onChange({ ...value, padUuid })}
          options={records.pads}
        />
        <p className="text-xs text-muted-foreground">
          Puts the <em>record on that pad</em> into the desk&rsquo;s hand, to be placed on any
          window. A second pick-up replaces; there is no put-it-back.
        </p>
      </div>
    )
  }
  if (kind === "handPlaceInBank" && value.type === "handPlaceInBank") {
    return (
      <div className="space-y-2">
        <RecordField
          label="Busk bank"
          value={value.bankUuid}
          onChange={(bankUuid) => onChange({ ...value, bankUuid })}
          options={records.banks}
        />
        <p className="text-xs text-muted-foreground">
          Appends whatever the hand holds to this bank and lets go — the same append the busk page
          makes. An empty hand is a dropped press, not an error.
        </p>
      </div>
    )
  }
  if (kind === "buskPageSet" && value.type === "buskPageSet") {
    return (
      <div className="space-y-2">
        <RecordField
          label="Page"
          value={value.pageUuid}
          onChange={(pageUuid) => onChange({ ...value, pageUuid })}
          options={records.pages}
        />
        <p className="text-xs text-muted-foreground">
          Shows this page on the desk, and on every window following the desk’s page. Its LED is lit while it is the one showing.
        </p>
      </div>
    )
  }
  if (kind === "buskPageNext" || kind === "buskPagePrev") {
    return (
      <p className="text-xs text-muted-foreground">
        Moves the showing busk page by one, wrapping at the ends.
      </p>
    )
  }
  if (kind === "buskFocusSet" && value.type === "buskFocusSet") {
    return (
      <div className="space-y-2">
        <WindowField value={value.windowName} onChange={(windowName) => onChange({ ...value, windowName })} />
        <div className="space-y-1.5">
          <Label className="text-xs">Focus</Label>
          <Select value={value.focus} onValueChange={(v) => onChange({ ...value, focus: v as BuskFocus })}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {BUSK_FOCUSES.map((focus) => (
                <SelectItem key={focus} value={focus}>{FOCUS_LABELS[focus]}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <p className="text-xs text-muted-foreground">
          Sets that window&rsquo;s busk focus — every window of the name, and only while it is on
          the busk view. A name no signed-in window has reads as <em>missing window</em> until one
          announces.
        </p>
      </div>
    )
  }
  if (kind === "buskSheetToggle" && value.type === "buskSheetToggle") {
    return (
      <div className="space-y-2">
        <WindowField value={value.windowName} onChange={(windowName) => onChange({ ...value, windowName })} />
        <p className="text-xs text-muted-foreground">
          Folds or unfolds that window&rsquo;s busk side sheet, onto the tab it had open. Addressed
          and judged exactly as a focus button.
        </p>
      </div>
    )
  }
  if (kind === "selectionNext" || kind === "selectionPrev") {
    return (
      <p className="text-xs text-muted-foreground">
        Steps the whole desk selection one place along rig order, wrapping — one cell when only cells
        are selected. The rig band&rsquo;s {kind === "selectionNext" ? "Next" : "Prev"} step button, on a
        surface button.
      </p>
    )
  }
  if (kind === "selectionCells" && value.type === "selectionCells") {
    return (
      <div className="space-y-2">
        <div className="space-y-1.5">
          <Label className="text-xs">Mode</Label>
          <Select value={value.mode} onValueChange={(v) => onChange({ ...value, mode: v as SubselectMode })}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {SUBSELECT_MODES.map((mode) => (
                <SelectItem key={mode} value={mode}>{SUBSELECT_MODE_LABELS[mode]}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <p className="text-xs text-muted-foreground">
          Rewrites the desk selection&rsquo;s targets over rig order — the rig band&rsquo;s Cells menu on a button.
          Not a state the desk keeps, so there is no LED.
        </p>
      </div>
    )
  }

  if (kind === "speedMasterTap" && value.type === "speedMasterTap") {
    return (
      <SpeedMasterSelect
        value={value.masterUuid}
        onChange={(masterUuid) => onChange({ ...value, masterUuid })}
      />
    )
  }

  return null
}

/**
 * Keep a BPM range field inside the clock's 20..300, falling back to the previous value for
 * anything unparseable. Without this a cleared field reads `Number('') === 0` and a typo
 * reads `NaN` (which serialises to `null`), and both blow up the backend's
 * `SpeedMasterBpm` init requires during deserialisation — before the route's try/catch, so
 * they surface as a 500 rather than a validation message.
 */
function clampBpm(raw: string, fallback: number): number {
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(300, Math.max(20, parsed))
}

/**
 * A uuid-addressed library choice.
 *
 * Starts **unset** rather than on the first row, and the placeholder says so: an empty uuid is
 * refused by name at the write boundary, where a silently-defaulted first row would save and bind
 * the button to something nobody picked.
 */
function RecordField({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  options: RecordOption[]
}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">{label}</Label>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger><SelectValue placeholder="Choose…" /></SelectTrigger>
        <SelectContent>
          {options.map((o) => (
            <SelectItem key={o.uuid} value={o.uuid} disabled={o.disabled}>
              {o.label}
              {o.detail && <span className="text-muted-foreground"> · {o.detail}</span>}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {options.length === 0 && (
        <p className="text-xs text-muted-foreground">Nothing in this library yet.</p>
      )}
    </div>
  )
}

function defaultForKind(
  kind: TargetKind,
  fixtures: { key: string; label: string }[],
  groups: string[],
  stacks: { id: number; name: string }[] = [],
): BindingTarget {
  switch (kind) {
    case "fixtureProperty":
      return { type: "fixtureProperty", fixtureKey: fixtures[0]?.key ?? "", propertyName: "dimmer" }
    case "groupProperty":
      return { type: "groupProperty", groupName: groups[0] ?? "", propertyName: "dimmer" }
    case "cueStackGo":
      return { type: "cueStackGo", stackId: stacks[0]?.id ?? 0 }
    case "cueStackBack":
      return { type: "cueStackBack", stackId: stacks[0]?.id ?? 0 }
    case "cueStackPause":
      return { type: "cueStackPause", stackId: stacks[0]?.id ?? 0 }
    case "fireCue":
      return { type: "fireCue", cueId: 0 }
    // No fixture yet, the record variants' rule: the first fixture would bind a fire button to a
    // cannon the operator never picked. Health reads it dead until one is chosen.
    case "fireTrigger":
      return { type: "fireTrigger", fixtureKey: "", trigger: "output1" }
    case "flash":
      return {
        type: "flash",
        target: { type: "groupProperty", groupName: groups[0] ?? "", propertyName: "dimmer" },
        max: 255,
      }
    case "blackout":
      return { type: "blackout" }
    case "grandMasterToggle":
      return { type: "grandMasterToggle" }
    case "setBank":
      return { type: "setBank", deviceTypeKey: "", bank: "" }
    // `dimmer` is `EncoderBankState.DEFAULT_PROPERTY` and the one property every rig has, so both
    // of these start somewhere the desk can already dispatch.
    case "selectionProperty":
      return { type: "selectionProperty", propertyName: "dimmer" }
    case "encoderBankSet":
      return { type: "encoderBankSet", propertyName: "dimmer" }
    case "selectTarget":
      return {
        type: "selectTarget",
        target: { type: "group", key: groups[0] ?? "" },
        mode: "toggle",
      }
    case "clearSelection":
      return { type: "clearSelection" }
    case "locateSelection":
      return { type: "locateSelection" }
    // null = master 1, which always exists — so the default binding is useful before the
    // live bank has even loaded, and the picker never starts in an unresolvable state.
    case "speedMasterBpm":
      return { type: "speedMasterBpm", masterUuid: null, minBpm: 60, maxBpm: 180 }
    case "speedMasterTap":
      return { type: "speedMasterTap", masterUuid: null }
    // The record variants start **empty**, not on the first row of their library: an empty uuid
    // is refused by name at the write boundary, where the first row would save silently and bind
    // the button to something the operator never picked. The body below insists on a choice.
    case "applyLook":
      return { type: "applyLook", lookUuid: "" }
    case "pressTemplate":
      return { type: "pressTemplate", templateUuid: "" }
    case "pressPad":
      return { type: "pressPad", padUuid: "" }
    case "pickUpPad":
      return { type: "pickUpPad", padUuid: "" }
    case "handPlaceInBank":
      return { type: "handPlaceInBank", bankUuid: "" }
    case "handDrop":
      return { type: "handDrop" }
    case "buskPageSet":
      return { type: "buskPageSet", pageUuid: "" }
    case "buskPageNext":
      return { type: "buskPageNext" }
    case "buskPagePrev":
      return { type: "buskPagePrev" }
    // The window-addressed pair start with **no** window, the record variants' rule: an empty name
    // would save as a binding to nothing, where the first connected window would bind the button to
    // whichever screen happened to be signed in.
    case "buskFocusSet":
      return { type: "buskFocusSet", windowName: "", focus: "split" }
    case "buskSheetToggle":
      return { type: "buskSheetToggle", windowName: "" }
    case "selectionNext":
      return { type: "selectionNext" }
    case "selectionPrev":
      return { type: "selectionPrev" }
    case "selectionCells":
      return { type: "selectionCells", mode: "ALL" }
  }
}

/**
 * A desk window by its registry **name** — the names signed in now, plus the binding's own if no
 * window of that name is connected, so a binding to a screen that is off tonight still shows what
 * it names rather than a blank.
 */
function WindowField({ value, onChange }: { value: string; onChange: (name: string) => void }) {
  const windows = useDeskWindows()
  const names = [...new Set(windows.map((w) => w.name))]
  if (value !== "" && !names.includes(value)) names.push(value)
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">Window</Label>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger><SelectValue placeholder="Select…" /></SelectTrigger>
        <SelectContent>
          {names.map((name) => (
            <SelectItem key={name} value={name}>{name}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/**
 * Every fixture key a binding target names, wherever its variant keeps one — a `fixtureKey` field
 * or a `{ type: 'fixture', key }` target — found by walking the value rather than by listing the
 * variants, so a new variant that names a fixture is covered without touching this.
 */
function boundFixtureKeys(value: unknown): Set<string> {
  const keys = new Set<string>()
  const walk = (node: unknown) => {
    if (Array.isArray(node)) {
      node.forEach(walk)
      return
    }
    if (node == null || typeof node !== "object") return
    const record = node as Record<string, unknown>
    if (typeof record.fixtureKey === "string") keys.add(record.fixtureKey)
    if (record.type === "fixture" && typeof record.key === "string") keys.add(record.key)
    Object.values(record).forEach(walk)
  }
  walk(value)
  return keys
}

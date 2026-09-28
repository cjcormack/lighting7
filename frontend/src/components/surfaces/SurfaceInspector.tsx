import { useState } from "react"
import { AlertTriangle, Radio, Trash2, Wand2 } from "lucide-react"
import { useFixtureListQuery } from "@/store/fixtures"
import { useGroupPropertiesQuery } from "@/store/groups"
import { useColourValue, useSliderValue } from "@/hooks/usePropertyValues"
import { useGroupColourValues, useGroupSliderValues } from "@/hooks/useGroupPropertyValues"
import type {
  ChannelRef,
  ColourPropertyDescriptor,
  PropertyDescriptor,
  SliderPropertyDescriptor,
} from "@/store/fixtures"
import type {
  GroupColourPropertyDescriptor,
  GroupPropertyDescriptor,
  GroupSliderPropertyDescriptor,
} from "@/api/groupsApi"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { describeHealth } from "@/lib/healthDescriptor"
import { cn } from "@/lib/utils"
import {
  useDeleteSurfaceBindingMutation,
  useExpandSurfaceBindingMutation,
  useUpdateSurfaceBindingMutation,
} from "@/store/surfaces"
import { axisSuffix, type ColourAxis } from "@/lib/colourAxis"
import type {
  BindingTarget,
  ControlDescriptor,
  ControlState,
  ControlSurfaceBinding,
  ControlSurfaceType,
  EncoderBankSelection,
  PickupChange,
} from "@/store/surfaces"
import { useDeskSelection } from "@/store/selection"
import { deriveStripTarget, type SurfaceBindingIndex } from "@/lib/surfaceResolve"
import { resolveControl } from "@/lib/surfaceResolve"
import { EditBindingSheet } from "./BindingMatrix"
import { LearnModeOverlay } from "./LearnModeOverlay"
import { describeTarget, effectiveTarget } from "./targetUtils"
import { describeBindingTarget, useRecordBindingOptions } from "./recordOptions"
import { midiPercent } from "./surfacePanelGeometry"

/**
 * The clicked control: what it is, what it is bound to, and what it is doing.
 *
 * The **binding card** is where a strip stops being invisible. A strip is one row covering four
 * controls, so an inspector that showed only "the binding on fader 5" would be describing a row
 * whose other three effects the operator cannot see from anywhere — hence the four-line breakdown,
 * and *Fader only…* beside it as the way back to single bindings.
 */

export interface SurfaceInspectorProps {
  projectId: number
  profile: ControlSurfaceType
  controlId: string
  index: SurfaceBindingIndex
  activeBank: string | null
  encoderBank: EncoderBankSelection
  state: ControlState | undefined
  pickup: PickupChange | undefined
  /** Every row on this device type, for the "other banks" line. */
  bindings: readonly ControlSurfaceBinding[]
}

export function SurfaceInspector({
  projectId,
  profile,
  controlId,
  index,
  activeBank,
  encoderBank,
  state,
  pickup,
  bindings,
}: SurfaceInspectorProps) {
  const [editing, setEditing] = useState(false)
  const [learning, setLearning] = useState(false)
  const [updateBinding] = useUpdateSurfaceBindingMutation()
  const [expandBinding] = useExpandSurfaceBindingMutation()
  const [deleteBinding] = useDeleteSurfaceBindingMutation()

  // One lookup for the whole inspector: the heading and the binding card both want a record's
  // name where `describeTarget` can only give a uuid, and a hook per line would be two
  // subscriptions to one library.
  const records = useRecordBindingOptions(projectId)
  const describe = (target: BindingTarget) => describeBindingTarget(target, records)

  const descriptor = profile.controls.find((c) => c.controlId === controlId)
  const resolved = resolveControl(controlId, index, activeBank, encoderBank)
  const onStrip = resolved?.via ?? null
  const deadReason = resolved ? describeHealth(resolved.binding.health) : null

  if (!descriptor) return null

  return (
    <div className="flex h-full flex-col gap-3 overflow-y-auto p-4">
      <div>
        <div className="text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
          Selected control{onStrip ? ` · ${onStrip.strip.id}` : ""}
        </div>
        <h3 className="text-sm font-semibold">
          {descriptor.label}
          {resolved && (
            <span className="ml-1.5 font-normal text-muted-foreground">
              — {describe(resolved.target)}
            </span>
          )}
        </h3>
        <p className="font-mono text-[11px] text-muted-foreground">{addressing(descriptor)}</p>
      </div>

      {deadReason && (
        <p className="flex items-start gap-1.5 rounded-md bg-destructive/10 p-2 text-xs text-destructive">
          <AlertTriangle className="mt-px size-3.5 shrink-0" />
          {deadReason}
        </p>
      )}

      <Separator />

      {!resolved ? (
        <p className="text-xs text-muted-foreground">
          Nothing is bound to this control on{" "}
          {activeBank == null ? "the global bank" : `bank ${activeBank}`}.
        </p>
      ) : (
        <BindingCard
          binding={resolved.binding}
          profile={profile}
          onStrip={onStrip}
          encoderBank={encoderBank}
          describe={describe}
        />
      )}

      <div className="flex flex-wrap gap-2">
        {resolved && (
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}>
            {onStrip ? "Change group" : "Change target"}
          </Button>
        )}
        {onStrip && resolved?.binding.target.type === "strip" && (
          <Button
            size="sm"
            variant="outline"
            title="Replace this strip with the four single bindings it was deriving"
            onClick={() => expandBinding({ projectId, bindingId: resolved.binding.id })}
          >
            Fader only…
          </Button>
        )}
        <Button size="sm" variant="outline" onClick={() => setLearning(true)}>
          <Radio className="size-3.5" />
          MIDI Learn
        </Button>
        {resolved && (
          <Button
            size="sm"
            variant="destructive"
            onClick={() => deleteBinding({ projectId, bindingId: resolved.binding.id })}
          >
            <Trash2 className="size-3.5" />
            {onStrip ? "Remove strip" : "Remove"}
          </Button>
        )}
      </div>

      {resolved && (
        <OtherBanks
          bindings={bindings}
          controlId={onStrip ? onStrip.strip.id : controlId}
          profile={profile}
          activeBank={activeBank}
          describe={describe}
        />
      )}

      <Separator />

      {/*
        Two arms, because "bank buttons can't be bound" and "this one is carrying a row that will
        never fire" are different things to be told, and only the second is actionable. The panel
        draws the second as dead; this says what to do about it, and *Remove* above is how.
      */}
      {descriptor.type === "bankButton" && (
        <p
          className={cn(
            "rounded-md p-2 text-[11px]",
            resolved ? "bg-destructive/10 text-destructive" : "bg-muted/60 text-muted-foreground",
          )}
        >
          Bank buttons switch the device&rsquo;s bank. The router answers one before it resolves a
          binding, so a row on this control can never fire — the library will not offer it.
          {resolved && ` This one holds ${describe(resolved.target)}; remove it.`}
        </p>
      )}

      <LiveCard descriptor={descriptor} state={state} pickup={pickup} resolved={resolved} />

      {editing && resolved && (
        <EditBindingSheet
          open={editing}
          onOpenChange={setEditing}
          projectId={projectId}
          binding={resolved.binding}
          profile={profile}
          onSave={async (target, bank, policy) => {
            await updateBinding({
              projectId,
              bindingId: resolved.binding.id,
              target,
              bank,
              bankPresent: true,
              takeoverPolicy: policy,
              takeoverPolicyPresent: true,
            }).unwrap()
            setEditing(false)
          }}
        />
      )}

      {learning && resolved && (
        <LearnModeOverlay
          open={learning}
          onOpenChange={setLearning}
          projectId={projectId}
          deviceTypeKey={profile.typeKey}
          target={resolved.binding.target}
          bank={resolved.binding.bank}
          takeoverPolicy={resolved.binding.takeoverPolicy}
          profile={profile}
          onCommitted={() => setLearning(false)}
        />
      )}
    </div>
  )
}

/** `CC 5 · touch CC 105 · ch 1 · motor` — how the desk addresses this control. */
function addressing(descriptor: ControlDescriptor): string {
  switch (descriptor.type) {
    case "fader": {
      const parts = [`CC ${descriptor.cc}`]
      if (descriptor.touchCc != null) parts.push(`touch CC ${descriptor.touchCc}`)
      if (descriptor.touchNote != null) parts.push(`touch note ${descriptor.touchNote}`)
      parts.push(`ch ${descriptor.channel}`)
      if (descriptor.hasMotor) parts.push("motor")
      return parts.join(" · ")
    }
    case "encoder": {
      const parts = [`CC ${descriptor.cc}`, `ch ${descriptor.channel}`]
      if (descriptor.ringCc != null) parts.push(`ring CC ${descriptor.ringCc}`)
      if (descriptor.pushNote != null) parts.push(`push note ${descriptor.pushNote}`)
      return parts.join(" · ")
    }
    case "button":
      return `Note ${descriptor.note} · ch ${descriptor.channel}`
    case "bankButton":
      return [
        descriptor.note != null ? `Note ${descriptor.note}` : `PC ${descriptor.programChange}`,
        `ch ${descriptor.channel}`,
        `bank ${descriptor.bankId}`,
      ].join(" · ")
  }
}

function BindingCard({
  binding,
  profile,
  onStrip,
  encoderBank,
  describe,
}: {
  binding: ControlSurfaceBinding
  profile: ControlSurfaceType
  onStrip: { strip: { id: string } } | null
  encoderBank: EncoderBankSelection
  describe: (target: BindingTarget) => string
}) {
  const strip =
    binding.target.type === "strip"
      ? profile.strips?.find((s) => s.id === binding.controlId)
      : undefined

  return (
    <div className="space-y-2 rounded-md border p-2.5">
      <div className="flex items-center gap-2 text-xs">
        <span className="font-medium">
          {strip ? "Strip binding" : "Binding"}
        </span>
        <Badge variant="outline" className="text-[10px]">
          {binding.bank == null ? "global" : `bank ${binding.bank}`}
        </Badge>
        {binding.health.type === "ok" ? (
          <Badge variant="secondary" className="text-[10px]">ok</Badge>
        ) : (
          <Badge variant="destructive" className="text-[10px]">dead</Badge>
        )}
      </div>

      {/* A strip's four effects, spelled out: they are the part of the row nothing else shows. */}
      {strip && binding.target.type === "strip" ? (
        <dl className="space-y-1 text-[11px]">
          <div className="pb-1 text-muted-foreground">
            {binding.target.target.key} · {binding.target.target.type}
          </div>
          {(["fader", "select", "encoder", "flash"] as const).map((role) => {
            const controlId =
              role === "fader"
                ? strip.fader
                : role === "select"
                  ? strip.select
                  : role === "encoder"
                    ? strip.encoder
                    : strip.flash
            if (!controlId) return null
            const label = profile.controls.find((c) => c.controlId === controlId)?.label ?? controlId
            const target =
              binding.target.type === "strip"
                ? deriveStripTarget(role, binding.target.target, encoderBank)
                : null
            return (
              <div key={role} className="flex justify-between gap-2">
                <dt className={cn("text-muted-foreground", onStrip && "shrink-0")}>{label}</dt>
                <dd className="truncate text-right">
                  {target ? describeTarget(target) : "—"}
                  {role === "encoder" && (
                    <span className="text-muted-foreground"> · from the encoder bank</span>
                  )}
                </dd>
              </div>
            )
          })}
        </dl>
      ) : (
        <p className="text-[11px]">{describe(binding.target)}</p>
      )}

      <div className="flex justify-between gap-2 text-[11px]">
        <span className="text-muted-foreground">Takeover</span>
        <span>{binding.takeoverPolicy === "PICKUP" ? "Pickup" : "Immediate"}</span>
      </div>
    </div>
  )
}

/**
 * What this control does on the *other* banks. Worth showing because the panel can only draw one
 * bank at a time, and "unbound" on screen is easy to read as "unbound everywhere".
 */
function OtherBanks({
  bindings,
  controlId,
  profile,
  activeBank,
  describe,
}: {
  bindings: readonly ControlSurfaceBinding[]
  controlId: string
  profile: ControlSurfaceType
  activeBank: string | null
  describe: (target: BindingTarget) => string
}) {
  const banks: (string | null)[] = [...profile.banks.map((b) => b.id), null]
  const others = banks.filter((b) => b !== activeBank)
  if (others.length === 0) return null

  return (
    <p className="text-[11px] text-muted-foreground">
      <span className="mr-1.5 font-medium">Other banks</span>
      {others
        .map((bank) => {
          const hit = bindings.find(
            (b) =>
              b.deviceTypeKey === profile.typeKey &&
              b.controlId === controlId &&
              b.bank === bank,
          )
          const name = bank ?? "Global"
          return `${name} — ${hit ? describe(hit.target) : "unbound"}`
        })
        .join(" · ")}
    </p>
  )
}

/**
 * What the control is doing now: three lines straight off the `surfaceControls` stream — what the
 * hardware was told, never a recomputation from DMX (D7) — and a fourth that is the opposite, the
 * value on the *stage* behind the binding.
 *
 * That fourth line is the one place this panel reads DMX, and deliberately: the other three answer
 * "what is the desk doing with this control", and it answers "and what came of it". A fader at 66%
 * over a dimmer reading 0 is a bound control writing into a park or a blackout, which is exactly
 * the question an operator asks the surface page.
 */
function LiveCard({
  descriptor,
  state,
  pickup,
  resolved,
}: {
  descriptor: ControlDescriptor
  state: ControlState | undefined
  pickup: PickupChange | undefined
  resolved: { target: BindingTarget } | null
}) {
  const selection = useDeskSelection()
  const isSelectionTarget =
    resolved?.target.type === "selectionProperty" ||
    resolved?.target.type === "selectTarget" ||
    resolved?.target.type === "locateSelection"

  const position = state?.physical ?? state?.value ?? null

  return (
    <div className="space-y-1 text-[11px]">
      <div className="text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
        Live
      </div>
      <p>
        {position == null ? (
          <span className="text-muted-foreground">Nothing fed back</span>
        ) : (
          <>
            Position {position} / 127 · {midiPercent(position)}%
          </>
        )}
      </p>
      {descriptor.type === "fader" && state?.touched && (
        <p className="text-muted-foreground">Touch held — motor feedback paused</p>
      )}
      {pickup?.state === "AWAITING_PICKUP" && pickup.target != null && (
        <p className="text-[var(--editor-warning)]">
          Awaiting pickup — move to {midiPercent(pickup.target)}% to engage
        </p>
      )}
      {state?.led !== "none" && state?.led != null && (
        <p className="text-muted-foreground">LED {state.led}</p>
      )}
      {resolved && <StageValue target={resolved.target} />}
      {isSelectionTarget && (
        <p className="flex items-center gap-1 text-muted-foreground">
          <Wand2 className="size-3" />
          {selection.length === 0
            ? "Nothing selected — a move is dropped"
            : `Acts on ${selection.map((t) => t.key).join(", ")}`}
        </p>
      )}
    </div>
  )
}

/**
 * `Movers.dimmer = 168` — what the binding's property is currently at on the rig.
 *
 * **A component, and four leaves, because the value hooks take a descriptor and hooks cannot be
 * conditional.** `useSliderValue` and `useGroupColourValues` want different shapes and subscribe to
 * different channel sets, so choosing between them inside one component would mean calling one of
 * them with a fabricated descriptor. `EffectPadDetail` is the same shape for the same reason.
 *
 * Only **slider** and **colour** are drawn, which is not a simplification: those are exactly the
 * two `PropertyChannelResolver` writes from a continuous control, so a binding on anything else has
 * no stage value to report. A `flash` is unwrapped first — its inner target is the property it
 * drives.
 */
function StageValue({ target }: { target: BindingTarget }) {
  const inner = effectiveTarget(target)
  if (inner.type === "fixtureProperty") {
    return (
      <FixtureStageValue
        fixtureKey={inner.fixtureKey}
        propertyName={inner.propertyName}
        axis={inner.colourAxis}
      />
    )
  }
  if (inner.type === "groupProperty") {
    return (
      <GroupStageValue
        groupName={inner.groupName}
        propertyName={inner.propertyName}
        axis={inner.colourAxis}
      />
    )
  }
  return null
}

/**
 * A bundled emitter — `white`, `amber`, `uv` — is not in the descriptor list (the fixture
 * descriptor folds it into the colour's `whiteChannel` …), but it is a slider the desk drives by
 * that name, so its stage line is drawn from a descriptor minted here off the colour's channel ref.
 * The name → channel-key rule is the one `hooks/useTargetProperties.ts` offers the chips by.
 */
const EMITTER_CHANNELS = {
  white: "whiteChannel",
  amber: "amberChannel",
  uv: "uvChannel",
} as const

function fixtureEmitterDescriptor(
  properties: readonly PropertyDescriptor[] | undefined,
  propertyName: string,
): SliderPropertyDescriptor | undefined {
  const key = EMITTER_CHANNELS[propertyName as keyof typeof EMITTER_CHANNELS]
  if (key == null) return undefined
  for (const property of properties ?? []) {
    if (property.type !== "colour") continue
    const channel = property[key]
    if (channel == null) continue
    return {
      type: "slider",
      name: propertyName,
      displayName: propertyName,
      category: propertyName as SliderPropertyDescriptor["category"],
      channel,
      min: 0,
      max: 255,
    }
  }
  return undefined
}

function groupEmitterDescriptor(
  properties: readonly GroupPropertyDescriptor[] | undefined,
  propertyName: string,
): GroupSliderPropertyDescriptor | undefined {
  const key = EMITTER_CHANNELS[propertyName as keyof typeof EMITTER_CHANNELS]
  if (key == null) return undefined
  for (const property of properties ?? []) {
    if (property.type !== "colour") continue
    const memberChannels = property.memberColourChannels
      .map((member) => member[key])
      .filter((channel): channel is ChannelRef => channel != null)
    if (memberChannels.length === 0) continue
    return {
      type: "slider",
      name: propertyName,
      displayName: propertyName,
      category: propertyName,
      min: 0,
      max: 255,
      memberChannels,
    }
  }
  return undefined
}

function StageLine({ label, value }: { label: string; value: string }) {
  return (
    <p className="font-mono text-[11px] text-muted-foreground">
      {label} = {value}
    </p>
  )
}

function FixtureStageValue({
  fixtureKey,
  propertyName,
  axis,
}: {
  fixtureKey: string
  propertyName: string
  axis: ColourAxis | null | undefined
}) {
  const { data: fixtures } = useFixtureListQuery()
  const properties = fixtures?.find((f) => f.key === fixtureKey)?.properties
  const property =
    properties?.find((p) => p.name === propertyName) ??
    fixtureEmitterDescriptor(properties, propertyName)
  // The axis is on the label only. The *Position* line above is already the desk's reading on
  // that axis (`ControlState.value` comes from `computeValue7Bit`); an HSV computed here would be
  // a second copy of the read rule, and the two would drift.
  const label = `${fixtureKey}.${propertyName}${axisSuffix(axis)}`
  if (property?.type === "slider") return <FixtureSliderValue property={property} label={label} />
  if (property?.type === "colour") return <FixtureColourValue property={property} label={label} />
  return null
}

function FixtureSliderValue({
  property,
  label,
}: {
  property: SliderPropertyDescriptor
  label: string
}) {
  return <StageLine label={label} value={String(useSliderValue(property))} />
}

function FixtureColourValue({
  property,
  label,
}: {
  property: ColourPropertyDescriptor
  label: string
}) {
  const { r, g, b } = useColourValue(property)
  return <StageLine label={label} value={`${r}, ${g}, ${b}`} />
}

function GroupStageValue({
  groupName,
  propertyName,
  axis,
}: {
  groupName: string
  propertyName: string
  axis: ColourAxis | null | undefined
}) {
  const { data: properties } = useGroupPropertiesQuery(groupName)
  const property =
    properties?.find((p) => p.name === propertyName) ??
    groupEmitterDescriptor(properties, propertyName)
  const label = `${groupName}.${propertyName}${axisSuffix(axis)}`
  if (property?.type === "slider") return <GroupSliderValue property={property} label={label} />
  if (property?.type === "colour") return <GroupColourValue property={property} label={label} />
  return null
}

function GroupSliderValue({
  property,
  label,
}: {
  property: GroupSliderPropertyDescriptor
  label: string
}) {
  // The group hook already answers "do the members agree", which is the same question D10 puts to
  // the motor: a mixed group is why the encoder ring beside this line is dark.
  const { min, max, isUniform } = useGroupSliderValues(property)
  return <StageLine label={label} value={isUniform ? String(min) : `${min}–${max} (mixed)`} />
}

function GroupColourValue({
  property,
  label,
}: {
  property: GroupColourPropertyDescriptor
  label: string
}) {
  const { avgR, avgG, avgB, isUniform } = useGroupColourValues(property)
  return (
    <StageLine
      label={label}
      value={`${avgR}, ${avgG}, ${avgB}${isUniform ? "" : " (mixed)"}`}
    />
  )
}

import { useMemo, useState } from "react"
import { cn } from "@/lib/utils"
import { describeHealth } from "@/lib/healthDescriptor"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Sliders, Circle, Square, Layers, Plus, Trash2, Pencil, AlertCircle, AlertTriangle } from "lucide-react"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { LearnModeOverlay } from "./LearnModeOverlay"
import { BindingTargetPicker } from "./BindingTargetPicker"
import { describeBindingTarget, type RecordBindingOptions } from "./recordOptions"
import {
  useSurfaceBindingsQuery,
  useCreateSurfaceBindingMutation,
  useUpdateSurfaceBindingMutation,
  useDeleteSurfaceBindingMutation,
  usePickupStates,
  pickupKey,
} from "@/store/surfaces"
import type {
  BindingTarget,
  ControlSurfaceBinding,
  ControlSurfaceType,
  ControlDescriptor,
  TakeoverPolicy,
  SurfaceDeviceInfo,
} from "@/store/surfaces"

interface BindingMatrixProps {
  projectId: number
  device: SurfaceDeviceInfo
  profile: ControlSurfaceType
  activeBank: string | null
  /** ID of a binding to highlight on mount (from `?binding=…` query param). */
  highlightBindingId: number | null
  /** Resolves a record-addressed binding's uuid to a name — see `SurfaceInspector`'s `describe`. */
  records: RecordBindingOptions
}

export function BindingMatrix({
  projectId,
  device,
  profile,
  activeBank,
  highlightBindingId,
  records,
}: BindingMatrixProps) {
  const { data: bindings } = useSurfaceBindingsQuery(projectId)
  const [createBinding] = useCreateSurfaceBindingMutation()
  const [updateBinding] = useUpdateSurfaceBindingMutation()
  const [deleteBinding] = useDeleteSurfaceBindingMutation()
  const pickupStates = usePickupStates()

  const [learnOpen, setLearnOpen] = useState(false)
  const [learnTarget, setLearnTarget] = useState<BindingTarget | null>(null)
  const [learnBank, setLearnBank] = useState<string | null>(null)
  const [learnPolicy, setLearnPolicy] = useState<TakeoverPolicy | null>(null)
  const [editingBinding, setEditingBinding] = useState<ControlSurfaceBinding | null>(null)
  const [newForControl, setNewForControl] = useState<ControlDescriptor | null>(null)

  // Bindings keyed by controlId — show the one matching the active bank first,
  // else global, else any in another bank (rendered muted).
  const bindingsByControl = useMemo(() => {
    const map = new Map<string, ControlSurfaceBinding[]>()
    for (const b of bindings ?? []) {
      if (b.deviceTypeKey !== device.typeKey) continue
      const list = map.get(b.controlId) ?? []
      list.push(b)
      map.set(b.controlId, list)
    }
    return map
  }, [bindings, device.typeKey])

  // Controls grouped for readability.
  const grouped = useMemo(() => groupControls(profile.controls), [profile.controls])

  return (
    <div className="space-y-6">
      {grouped.map((group) => (
        <section key={group.title}>
          <h3 className="text-xs font-medium uppercase tracking-wider text-muted-foreground mb-2">
            {group.title}
          </h3>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-32">Control</TableHead>
                <TableHead className="w-20">Kind</TableHead>
                <TableHead>Active binding ({activeBank ?? "global"})</TableHead>
                <TableHead className="w-24 text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {group.controls.map((control) => {
                const list = bindingsByControl.get(control.controlId) ?? []
                const active = resolveActive(list, activeBank)
                const pickup = pickupStates[pickupKey(device.displayKey, control.controlId)]
                const highlight = active?.id === highlightBindingId
                const deadReason = active ? describeHealth(active.health) : null
                const dead = deadReason != null
                return (
                  <TableRow
                    key={control.controlId}
                    className={cn(
                      highlight && "bg-accent/50",
                      dead && "outline outline-1 outline-destructive/60 bg-destructive/5",
                    )}
                  >
                    <TableCell className="font-mono text-xs">{control.label}</TableCell>
                    <TableCell>
                      <Badge variant="outline" className="text-[10px]">
                        <ControlKindIcon control={control} /> {control.type}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      {active ? (
                        <div className="flex items-center gap-2">
                          <BindingSummary binding={active} records={records} />
                          {dead && (
                            <Badge
                              variant="destructive"
                              className="text-[10px] gap-1"
                              title={deadReason ?? undefined}
                            >
                              <AlertTriangle className="size-3" />
                              dead
                            </Badge>
                          )}
                          {pickup && !dead && (
                            <Badge variant="secondary" className="text-[10px] gap-1">
                              <AlertCircle className="size-3" />
                              pickup @ {pickup.target}
                            </Badge>
                          )}
                        </div>
                      ) : (
                        <span className="text-muted-foreground text-xs">unbound</span>
                      )}
                      {dead && deadReason && (
                        <div className="mt-1 text-[10px] text-destructive">
                          {deadReason}
                        </div>
                      )}
                      {list.length > 1 && (
                        <div className="mt-1 text-[10px] text-muted-foreground">
                          {list.length - 1} other binding{list.length - 1 > 1 ? "s" : ""} on other banks
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        {active ? (
                          <>
                            {dead && (
                              <Button
                                size="sm"
                                variant="outline"
                                className="h-7 text-[10px] px-2"
                                onClick={() => setEditingBinding(active)}
                                title="Rebind this control to a valid target"
                              >
                                Rebind
                              </Button>
                            )}
                            <Button
                              size="icon"
                              variant="ghost"
                              className="size-7"
                              onClick={() => setEditingBinding(active)}
                            >
                              <Pencil className="size-3.5" />
                            </Button>
                            <Button
                              size="icon"
                              variant="ghost"
                              className="size-7 text-destructive"
                              onClick={() => deleteBinding({ projectId, bindingId: active.id })}
                            >
                              <Trash2 className="size-3.5" />
                            </Button>
                          </>
                        ) : control.type === "bankButton" ? (
                          <span className="text-[10px] text-muted-foreground">auto</span>
                        ) : (
                          <Button
                            size="icon"
                            variant="ghost"
                            className="size-7"
                            onClick={() => setNewForControl(control)}
                          >
                            <Plus className="size-3.5" />
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </section>
      ))}

      {/* Direct-pick create sheet */}
      {newForControl && (
        <CreateBindingSheet
          open={newForControl != null}
          onOpenChange={(next) => { if (!next) setNewForControl(null) }}
          projectId={projectId}
          device={device}
          control={newForControl}
          activeBank={activeBank}
          onLearn={(target, bank, policy) => {
            setLearnTarget(target)
            setLearnBank(bank)
            setLearnPolicy(policy)
            setNewForControl(null)
            setLearnOpen(true)
          }}
          onDirectCreate={async (target, bank, policy) => {
            await createBinding({
              projectId,
              deviceTypeKey: device.typeKey!,
              controlId: newForControl.controlId,
              bank,
              target,
              takeoverPolicy: policy,
            }).unwrap()
            setNewForControl(null)
          }}
        />
      )}

      {editingBinding && (
        <EditBindingSheet
          open={editingBinding != null}
          onOpenChange={(next) => { if (!next) setEditingBinding(null) }}
          projectId={projectId}
          binding={editingBinding}
          profile={profile}
          onSave={async (target, bank, policy) => {
            await updateBinding({
              projectId,
              bindingId: editingBinding.id,
              target,
              bank,
              bankPresent: true,
              takeoverPolicy: policy,
              takeoverPolicyPresent: true,
            }).unwrap()
            setEditingBinding(null)
          }}
        />
      )}

      {learnTarget && (
        <LearnModeOverlay
          open={learnOpen}
          onOpenChange={setLearnOpen}
          projectId={projectId}
          deviceTypeKey={device.typeKey}
          target={learnTarget}
          bank={learnBank}
          takeoverPolicy={learnPolicy}
          profile={profile}
          onCommitted={() => { setLearnTarget(null) }}
        />
      )}
    </div>
  )
}

function resolveActive(
  bindings: ControlSurfaceBinding[],
  activeBank: string | null,
): ControlSurfaceBinding | null {
  if (bindings.length === 0) return null
  const bankMatch = activeBank == null
    ? bindings.find((b) => b.bank == null)
    : bindings.find((b) => b.bank === activeBank)
  if (bankMatch) return bankMatch
  return bindings.find((b) => b.bank == null) ?? null
}

function ControlKindIcon({ control }: { control: ControlDescriptor }) {
  if (control.type === "fader") return <Sliders className="size-3" />
  if (control.type === "encoder") return <Circle className="size-3" />
  if (control.type === "bankButton") return <Layers className="size-3" />
  return <Square className="size-3" />
}

function BindingSummary({
  binding,
  records,
}: {
  binding: ControlSurfaceBinding
  records: RecordBindingOptions
}) {
  return (
    <div className="flex flex-col leading-tight">
      <span className="text-sm">{describeBindingTarget(binding.target, records)}</span>
      <span className="text-[10px] text-muted-foreground font-mono">
        {binding.bank ? `bank ${binding.bank}` : "global"}
        {binding.takeoverPolicy ? ` · ${binding.takeoverPolicy}` : ""}
      </span>
    </div>
  )
}

function groupControls(controls: ControlDescriptor[]): { title: string; controls: ControlDescriptor[] }[] {
  const faders = controls.filter((c) => c.type === "fader")
  const encoders = controls.filter((c) => c.type === "encoder")
  const buttons = controls.filter((c) => c.type === "button")
  const bankButtons = controls.filter((c) => c.type === "bankButton")
  const sections: { title: string; controls: ControlDescriptor[] }[] = []
  if (faders.length) sections.push({ title: "Faders", controls: faders })
  if (encoders.length) sections.push({ title: "Encoders", controls: encoders })
  if (buttons.length) sections.push({ title: "Buttons", controls: buttons })
  if (bankButtons.length) sections.push({ title: "Bank buttons", controls: bankButtons })
  return sections
}

function CreateBindingSheet({
  open,
  onOpenChange,
  projectId,
  device,
  control,
  activeBank,
  onLearn,
  onDirectCreate,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  projectId: number
  device: SurfaceDeviceInfo
  control: ControlDescriptor
  activeBank: string | null
  onLearn: (t: BindingTarget, bank: string | null, policy: TakeoverPolicy | null) => void
  onDirectCreate: (t: BindingTarget, bank: string | null, policy: TakeoverPolicy | null) => Promise<void>
}) {
  const continuous = control.type === "fader" || control.type === "encoder"
  const [target, setTarget] = useState<BindingTarget>(
    continuous
      ? { type: "groupProperty", groupName: "", propertyName: "dimmer" }
      : { type: "blackout" },
  )
  const [bank, setBank] = useState<string | null>(activeBank)
  const [policy, setPolicy] = useState<TakeoverPolicy | null>(null)

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-[420px] sm:max-w-[420px] flex flex-col gap-0 p-0">
        <SheetHeader className="p-4 border-b">
          <SheetTitle>New binding</SheetTitle>
          <SheetDescription>
            {device.displayName} · {control.label}
          </SheetDescription>
        </SheetHeader>
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          <div className="grid grid-cols-2 gap-2 items-end">
            <div>
              <label className="text-xs text-muted-foreground">Bank</label>
              <input
                className="w-full h-9 px-3 rounded-md border bg-background text-sm"
                value={bank ?? ""}
                onChange={(e) => setBank(e.target.value || null)}
                placeholder="global"
              />
            </div>
            <div className="text-xs text-muted-foreground">
              Empty = global (applies under any active bank).
            </div>
          </div>
          <BindingTargetPicker
            projectId={projectId}
            continuous={continuous}
            value={target}
            onChange={setTarget}
            policy={policy}
            onPolicyChange={setPolicy}
          />
        </div>
        <SheetFooter className="flex-row gap-2 p-4 border-t">
          <Button variant="outline" onClick={() => onLearn(target, bank, policy)}>
            MIDI Learn
          </Button>
          <Button onClick={() => onDirectCreate(target, bank, policy)}>
            Create
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

export function EditBindingSheet({
  open,
  onOpenChange,
  projectId,
  binding,
  profile,
  onSave,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  projectId: number
  binding: ControlSurfaceBinding
  profile: ControlSurfaceType
  onSave: (target: BindingTarget, bank: string | null, policy: TakeoverPolicy | null) => Promise<void>
}) {
  const control = profile.controls.find((c) => c.controlId === binding.controlId)
  const continuous = control?.type === "fader" || control?.type === "encoder"
  const [target, setTarget] = useState<BindingTarget>(binding.target)
  const [bank, setBank] = useState<string | null>(binding.bank)
  const [policy, setPolicy] = useState<TakeoverPolicy | null>(binding.takeoverPolicy)

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-[420px] sm:max-w-[420px] flex flex-col gap-0 p-0">
        <SheetHeader className="p-4 border-b">
          <SheetTitle>Edit binding</SheetTitle>
          <SheetDescription>
            {binding.deviceTypeKey} · {control?.label ?? binding.controlId}
          </SheetDescription>
        </SheetHeader>
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          <div>
            <label className="text-xs text-muted-foreground">Bank</label>
            <input
              className="w-full h-9 px-3 rounded-md border bg-background text-sm"
              value={bank ?? ""}
              onChange={(e) => setBank(e.target.value || null)}
              placeholder="global"
            />
          </div>
          <BindingTargetPicker
            projectId={projectId}
            continuous={!!continuous}
            value={target}
            onChange={setTarget}
            policy={policy}
            onPolicyChange={setPolicy}
          />
        </div>
        <SheetFooter className="p-4 border-t">
          <Button onClick={() => onSave(target, bank, policy)}>Save</Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

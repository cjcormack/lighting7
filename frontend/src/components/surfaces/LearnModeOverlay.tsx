import { useEffect, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Loader2, Radio } from "lucide-react"
import { lightingApi } from "@/api/lightingApi"
import { DESK_OFFLINE_LABEL } from "@/api/wsGesture"
import { controlLabel } from "./targetUtils"
import type {
  BindingTarget,
  LearnEvent,
  TakeoverPolicy,
  ControlSurfaceType,
} from "@/store/surfaces"

interface LearnModeOverlayProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** Restrict Learn to a specific device type, or null for any attached device. */
  deviceTypeKey: string | null
  /** The target the binding will be committed against once a control is captured. */
  target: BindingTarget
  /** Pre-selected bank for the new binding. */
  bank: string | null
  /** Optional takeover policy; null = inherit device-class default. */
  takeoverPolicy: TakeoverPolicy | null
  profile: ControlSurfaceType | null
  onCommitted: (bindingId: number) => void
}

type Phase =
  | { state: "idle" }
  | { state: "starting" }
  | { state: "waiting"; sessionId: string; deadlineMs: number }
  | {
      state: "captured"
      sessionId: string
      deviceTypeKey: string
      controlId: string
    }
  | { state: "committing"; sessionId: string }
  | { state: "error"; message: string }

export function LearnModeOverlay({
  open,
  onOpenChange,
  projectId,
  deviceTypeKey,
  target,
  bank,
  takeoverPolicy,
  profile,
  onCommitted,
}: LearnModeOverlayProps) {
  const [phase, setPhase] = useState<Phase>({ state: "idle" })

  useEffect(() => {
    if (!open) {
      setPhase({ state: "idle" })
      return
    }
    // A dropped frame produces no `started` event, so without this the dialog spins on its
    // loader forever with Cancel as the only way out.
    if (!lightingApi.surfaces.beginLearn(projectId, deviceTypeKey)) {
      setPhase({ state: "error", message: `${DESK_OFFLINE_LABEL} — MIDI Learn needs the desk.` })
      return
    }
    setPhase({ state: "starting" })
  }, [open, projectId, deviceTypeKey])

  useEffect(() => {
    if (!open) return
    // Events from other tabs' sessions must not hijack this dialog — filter
    // once `started` has told us our own sessionId.
    let ownSessionId: string | null = null
    const sub = lightingApi.surfaces.subscribeLearn((event: LearnEvent) => {
      if (event.type === "started") {
        ownSessionId = event.sessionId
        setPhase({
          state: "waiting",
          sessionId: event.sessionId,
          deadlineMs: event.deadlineMs,
        })
        return
      }
      if (ownSessionId != null && event.sessionId !== ownSessionId) return
      switch (event.type) {
        case "captured":
          setPhase({
            state: "captured",
            sessionId: event.sessionId,
            deviceTypeKey: event.deviceTypeKey,
            controlId: event.controlId,
          })
          break
        case "committed":
          onCommitted(event.bindingId)
          onOpenChange(false)
          break
        case "cancelled":
          onOpenChange(false)
          break
        case "error":
          setPhase({ state: "error", message: event.message })
          break
      }
    })
    return () => sub.unsubscribe()
  }, [open, onCommitted, onOpenChange])

  const commit = () => {
    if (phase.state !== "captured") return
    if (!lightingApi.surfaces.commitLearn(phase.sessionId, target, bank, takeoverPolicy)) {
      setPhase({ state: "error", message: `${DESK_OFFLINE_LABEL} — the binding was not saved.` })
      return
    }
    setPhase({ state: "committing", sessionId: phase.sessionId })
  }

  const cancel = () => {
    if (phase.state === "waiting" || phase.state === "captured" || phase.state === "committing") {
      lightingApi.surfaces.cancelLearn(phase.sessionId)
    }
    onOpenChange(false)
  }

  const capturedControlLabel = phase.state === "captured"
    ? controlLabel(profile, phase.controlId)
    : null

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) cancel(); else onOpenChange(next) }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Radio className="size-4" />
            MIDI Learn
          </DialogTitle>
          <DialogDescription>
            {deviceTypeKey
              ? `Move any control on the ${deviceTypeKey} to bind.`
              : "Move any control on any attached surface to bind."}
          </DialogDescription>
        </DialogHeader>

        <div className="py-4 text-sm">
          {phase.state === "starting" && (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Loader2 className="size-4 animate-spin" /> Starting session…
            </div>
          )}
          {phase.state === "waiting" && (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Loader2 className="size-4 animate-spin" /> Waiting for a control…
            </div>
          )}
          {phase.state === "captured" && (
            <div className="space-y-2">
              <p>Captured:</p>
              <div className="flex items-center gap-2">
                <Badge variant="secondary">{phase.deviceTypeKey}</Badge>
                <Badge variant="outline">{capturedControlLabel}</Badge>
              </div>
              <p className="text-xs text-muted-foreground">
                Click Bind to persist, or wiggle a different control to recapture.
              </p>
            </div>
          )}
          {phase.state === "committing" && (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Loader2 className="size-4 animate-spin" /> Binding…
            </div>
          )}
          {phase.state === "error" && (
            <p className="text-destructive">{phase.message}</p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={cancel}>Cancel</Button>
          <Button
            onClick={commit}
            disabled={phase.state !== "captured"}
          >
            Bind
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

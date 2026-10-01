import { useCallback, useEffect, useRef, useState } from 'react'
import { Flame, RotateCcw } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { cn } from '@/lib/utils'
import { formatError } from '@/lib/formatError'
import { spentAt } from '@/api/effectsApi'
import { useCurrentProjectQuery } from '@/store/projects'
import { useVisiblePatchListQuery } from '@/store/patches'
import { useArmEffectsMutation, useEffectsArm, useFireTriggerMutation, useReloadTriggerMutation } from '@/store/effects'
import { useVisSource } from '@/hooks/useVisSource'
import type { TriggerPropertyDescriptor } from '@/store/fixtures'
import { useArmCountdown } from './useArmCountdown'

/** How long a fire button must be held before it fires. Long enough that a brush is not a fire. */
export const HOLD_TO_FIRE_MS = 700

/** `21:42` from an ISO instant, in the operator's local time. */
function clock(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/**
 * The cannon's panel (stage-view plan session 9; `StacksLooks.dc.html` §3–4): the fixture panel of a
 * one-shot fixture, drawn wherever a fixture's controls are — the Stage view's docked panel, the
 * fixture sheet, the Fixtures view.
 *
 * - **Loaded and spent per tube** — machine-local: the physical cannon in this hall, from
 *   `effects.armed`.
 * - **Arm / disarm** — desk-wide, so arming here arms every cannon and every window shows ARMED.
 * - **Hold to fire** — a press held for [HOLD_TO_FIRE_MS]; a brush fires nothing. A spent tube's
 *   button is inert and says so; disarmed, the buttons say why they wait.
 * - **Reload…** — confirmed, since it tells the desk a tube is loaded that it cannot see.
 *
 * **Rehearsal**: while the programmer is blind — or in a window whose vis source is the programmer —
 * a fire is rehearsed: every window draws the burst, nothing reaches the wire, no tube is spent and
 * no arm is needed. That is how a cue event gets plotted before the night.
 */
export function CannonPanel({
  fixtureKey,
  triggers,
  canFire,
}: {
  fixtureKey: string
  triggers: readonly TriggerPropertyDescriptor[]
  /** False on a read-only surface or with the desk unreachable. */
  canFire: boolean
}) {
  const arm = useEffectsArm()
  const seconds = useArmCountdown(arm)
  const source = useVisSource()
  const { data: project } = useCurrentProjectQuery()
  const projectId = project?.id ?? 0
  const { data: patches } = useVisiblePatchListQuery(projectId, { skip: project == null })
  const patch = patches?.find((p) => p.key === fixtureKey)
  const [setArm, { isLoading: arming }] = useArmEffectsMutation()
  const [fire] = useFireTriggerMutation()
  const [reload, { isLoading: reloading }] = useReloadTriggerMutation()
  const [confirmReload, setConfirmReload] = useState(false)

  const rehearsing = arm.rehearsal || source === 'programmer'
  const ready = canFire && project != null && patch != null

  const toggleArm = () => {
    if (!ready) return
    setArm({ projectId, on: !arm.armed })
      .unwrap()
      .catch((e: unknown) => toast.error(`Couldn't ${arm.armed ? 'disarm' : 'arm'}: ${formatError(e)}`))
  }

  const fireTube = useCallback(
    (trigger: TriggerPropertyDescriptor) => {
      if (project == null || patch == null) return
      fire({ projectId: project.id, patchId: patch.id, trigger: trigger.name, rehearse: source === 'programmer' })
        .unwrap()
        .catch((e: unknown) => toast.error(`${trigger.displayName}: ${formatError(e)}`))
    },
    [fire, project, patch, source],
  )

  const spentTubes = triggers.filter((t) => spentAt(arm, fixtureKey, t.name) != null)

  return (
    <div className="space-y-3" data-testid="cannon-panel">
      <div className="grid grid-cols-2 gap-2">
        {triggers.map((t) => {
          const spent = spentAt(arm, fixtureKey, t.name)
          return (
            <div key={t.name} className="flex flex-col rounded-md border px-3 py-2">
              <span className="text-sm font-semibold">{t.displayName}</span>
              <span className={cn('text-xs', spent != null ? 'text-red-500 dark:text-red-400' : 'text-muted-foreground')}>
                {spent != null ? `spent · ${clock(spent)}` : 'loaded'}
              </span>
            </div>
          )
        })}
      </div>

      <Button
        type="button"
        variant={arm.armed ? 'destructive' : 'outline'}
        className="h-10 w-full font-bold uppercase tracking-wider"
        disabled={!ready || arming}
        onClick={toggleArm}
        title={arm.armed ? 'Disarm the desk: every cannon, every window' : 'Arm the desk for 60 s: every cannon, every window'}
      >
        <Flame className="size-4" />
        {arm.armed ? `Disarm · ${seconds ?? 0} s` : 'Arm · 60 s'}
      </Button>

      <div className="grid grid-cols-2 gap-2">
        {triggers.map((t) => {
          const spent = spentAt(arm, fixtureKey, t.name) != null
          const live = rehearsing || arm.armed
          return (
            <HoldToFireButton
              key={t.name}
              label={
                spent && !rehearsing
                  ? `${t.label} spent`
                  : !live
                    ? `Arm to fire ${t.label}`
                    : rehearsing
                      ? `Hold to rehearse ${t.label}`
                      : `Hold to fire ${t.label}`
              }
              disabled={!ready || !live || (spent && !rehearsing)}
              armed={arm.armed && !rehearsing && !spent}
              onFire={() => fireTube(t)}
            />
          )
        })}
      </div>

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="gap-1.5"
        disabled={!ready || spentTubes.length === 0 || reloading}
        onClick={() => setConfirmReload(true)}
      >
        <RotateCcw className="size-3.5" />
        Reload…
      </Button>

      <p className="text-[11px] leading-snug text-muted-foreground">
        The fire channels are triggers: no Look, template, effect or Record can hold them. Arming is
        desk-wide, for every signed-in role at the desk; in Blind, or with this window&apos;s vis
        source on the programmer, a fire is rehearsed — confetti on every window, nothing on the wire.
      </p>

      <AlertDialog open={confirmReload} onOpenChange={setConfirmReload}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reload {spentTubes.map((t) => t.displayName).join(' and ')}?</AlertDialogTitle>
            <AlertDialogDescription>
              Only once the tube is physically reloaded: the desk cannot see the cannon, and a tube it
              believes loaded is one it will fire.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (project == null || patch == null) return
                reload({ projectId: project.id, patchId: patch.id })
                  .unwrap()
                  .catch((e: unknown) => toast.error(`Couldn't reload: ${formatError(e)}`))
              }}
            >
              Reloaded
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

/**
 * A button that fires only when held for [HOLD_TO_FIRE_MS], filling as it is held; releasing early,
 * or the pointer leaving, cancels. Keyboard: Space/Enter held does the same.
 */
export function HoldToFireButton({
  label,
  disabled,
  armed,
  onFire,
}: {
  label: string
  disabled: boolean
  /** Draws the red, live border. */
  armed: boolean
  onFire: () => void
}) {
  const [progress, setProgress] = useState(0)
  const started = useRef<number | null>(null)
  const raf = useRef<number | null>(null)
  const fired = useRef(false)

  const stop = useCallback(() => {
    started.current = null
    if (raf.current != null) cancelAnimationFrame(raf.current)
    raf.current = null
    setProgress(0)
  }, [])

  useEffect(() => stop, [stop])
  useEffect(() => {
    if (disabled) stop()
  }, [disabled, stop])

  const tick = useCallback(() => {
    if (started.current == null) return
    const p = Math.min(1, (performance.now() - started.current) / HOLD_TO_FIRE_MS)
    setProgress(p)
    if (p >= 1) {
      if (!fired.current) {
        fired.current = true
        onFire()
      }
      started.current = null
      raf.current = null
      return
    }
    raf.current = requestAnimationFrame(tick)
  }, [onFire])

  const start = () => {
    if (disabled || started.current != null) return
    fired.current = false
    started.current = performance.now()
    raf.current = requestAnimationFrame(tick)
  }

  return (
    <button
      type="button"
      disabled={disabled}
      onPointerDown={(e) => {
        // Captured so a finger sliding off the button still delivers its release; optional because
        // a synthetic or already-ended pointer has nothing to capture.
        try {
          e.currentTarget.setPointerCapture?.(e.pointerId)
        } catch {
          // Nothing to capture: the release will still arrive on the button.
        }
        start()
      }}
      onPointerUp={stop}
      onPointerCancel={stop}
      onLostPointerCapture={stop}
      onKeyDown={(e) => {
        if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) {
          e.preventDefault()
          start()
        }
      }}
      onKeyUp={(e) => {
        if (e.key === ' ' || e.key === 'Enter') stop()
      }}
      onContextMenu={(e) => e.preventDefault()}
      className={cn(
        'relative h-12 select-none overflow-hidden rounded-md border text-sm font-bold transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-50',
        armed ? 'border-red-500 text-foreground' : 'border-input text-muted-foreground',
      )}
      style={{ touchAction: 'none' }}
    >
      <span
        aria-hidden
        className="absolute inset-y-0 left-0 bg-red-500/30"
        style={{ width: `${progress * 100}%` }}
      />
      <span className="relative">{label}</span>
    </button>
  )
}

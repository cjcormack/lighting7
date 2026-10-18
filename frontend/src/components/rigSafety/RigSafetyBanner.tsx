import { useState } from 'react'
import { Loader2, Power, ShieldAlert, ShieldCheck, Timer, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { CommandRun } from '@/api/rigSafetyApi'
import { blackoutReason, clockLabel, coolingLeft, lampsCoolingLeft, rigBanner, runOutcome, runProgress, runTitle, unitLine } from '@/lib/rigSafety'
import { useCurrentProjectQuery } from '@/store/projects'
import { useCancelBulkCommandMutation, useReleaseRigSafeMutation, useRigSafety } from '@/store/rigSafety'
import { QuitDeskDialog } from './QuitDeskDialog'
import { UndoRigSafeDialog } from './UndoRigSafeDialog'
import { useNow } from './useNow'

/**
 * The end of the night on every window (venue-visit plan S6, D9, D14, D18): one row under the app
 * header, beside the venue bands, never on the header itself (its 375px budget is spent).
 *
 * - **A run going** — its progress, the next lamp waiting on its restrike counted down, the units on
 *   *Details*, and *Cancel*.
 * - **Cooling** — made safe, but a lamp's fan must run on before the mains is cut: counted down, with
 *   *Undo* and *Quit desk…* (quitting cuts no fixture's power).
 * - **Ready to switch off** — green: *Undo*, and *Quit desk…* where this window may quit.
 * - **Blacked out, not ready** — amber, with why (a lamp not known to be off, or output that moved),
 *   and how long the lamps still need before the mains is cut: *Undo* and *Release blackout*.
 * - **A run that ended with something not done** — what happened, until this window dismisses it.
 *
 * Immersive windows draw [RigSafetyImmersiveChip] on the `ShowHeader` instead.
 */
export function RigSafetyBanner() {
  const rig = useRigSafety()
  const { data: project } = useCurrentProjectQuery()
  const [dismissed, setDismissed] = useState<ReadonlySet<string>>(() => new Set())
  const [details, setDetails] = useState(false)
  const [quitting, setQuitting] = useState(false)
  const [undoing, setUndoing] = useState(false)
  const [cancel] = useCancelBulkCommandMutation()
  const [release] = useReleaseRigSafeMutation()
  const banner = rigBanner(rig, dismissed)
  const lampsCooling = banner?.kind === 'blackout' && rig.lampsOff.some((l) => (l.coolRemainingMs ?? 0) > 0)
  const now = useNow(banner?.kind === 'run' || banner?.kind === 'cooling' || lampsCooling)
  if (banner == null || project == null || rig.projectId !== project.id) return null
  const projectId = project.id

  const tone = banner.kind === 'ready' ? 'green' : banner.kind === 'run' || banner.kind === 'cooling' ? 'neutral' : 'amber'
  const Icon =
    banner.kind === 'ready'
      ? ShieldCheck
      : banner.kind === 'run'
        ? Loader2
        : banner.kind === 'cooling'
          ? Timer
          : banner.kind === 'blackout'
            ? ShieldAlert
            : TriangleAlert
  const run: CommandRun | null = banner.kind === 'run' || banner.kind === 'finished' ? banner.run : null

  return (
    <div
      role="status"
      aria-label="Rig safety"
      data-rig-safety={banner.kind}
      className={cn(
        'shrink-0 border-b px-3 py-1.5 text-sm sm:px-4',
        tone === 'green' && 'border-emerald-600/30 bg-emerald-500/10',
        tone === 'amber' && 'border-amber-600/30 bg-amber-500/10',
        tone === 'neutral' && 'border-border bg-muted/60',
      )}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="flex min-w-0 flex-1 items-center gap-2">
          <Icon
            className={cn(
              'size-4 shrink-0',
              banner.kind === 'run' && 'animate-spin text-muted-foreground',
              tone === 'green' && 'text-emerald-700 dark:text-emerald-300',
              tone === 'amber' && 'text-amber-700 dark:text-amber-300',
            )}
          />
          <span className="min-w-0">
            {banner.kind === 'ready' && (
              <>
                <strong>Ready to switch off</strong> —{' '}
                {rig.undo.length > 0 ? `${rig.undo.length === 1 ? '1 lamp' : `${rig.undo.length} lamps`} off, ` : ''}
                blacked out.
              </>
            )}
            {banner.kind === 'cooling' && (
              <>
                <strong>Lamps off, cooling</strong> — safe to power down in{' '}
                <span className="tabular-nums">{clockLabel(coolingLeft(rig, now))}</span>. Blacked out.
              </>
            )}
            {banner.kind === 'blackout' && (
              <>
                <strong>Blacked out by Make rig safe</strong>, not ready to switch off — {blackoutReason(rig)}.
                {lampsCooling && lampsCoolingLeft(rig, now) > 0 && (
                  <>
                    {' '}
                    Lamps still cooling: mains safe in{' '}
                    <span className="tabular-nums">{clockLabel(lampsCoolingLeft(rig, now))}</span>.
                  </>
                )}
              </>
            )}
            {banner.kind === 'run' && (
              <>
                <strong>{runTitle(banner.run)}</strong> — {runProgress(banner.run, rig.receivedAt, now)}
              </>
            )}
            {banner.kind === 'finished' && (
              <>
                <strong>{runTitle(banner.run)}</strong> finished — {runOutcome(banner.run)}
                {banner.run.withoutCommand ? ` · ${banner.run.withoutCommand} selected had no such command` : ''}
              </>
            )}
          </span>
        </span>
        <span className="flex shrink-0 flex-wrap items-center gap-2">
          {run != null && run.units.length > 0 && (
            <Button size="sm" variant="ghost" className="h-7" aria-expanded={details} onClick={() => setDetails((d) => !d)}>
              {details ? 'Hide details' : 'Details'}
            </Button>
          )}
          {banner.kind === 'run' && (
            <Button size="sm" variant="outline" className="h-7" onClick={() => void cancel({ projectId })}>
              Cancel
            </Button>
          )}
          {(banner.kind === 'ready' || banner.kind === 'cooling' || banner.kind === 'blackout') && (
            <Button size="sm" variant="outline" className="h-7" onClick={() => setUndoing(true)}>
              Undo
            </Button>
          )}
          {banner.kind === 'blackout' && (
            <Button size="sm" variant="outline" className="h-7" onClick={() => void release({ projectId })}>
              Release blackout
            </Button>
          )}
          {(banner.kind === 'ready' || banner.kind === 'cooling' || banner.kind === 'blackout') && rig.quitAllowed && (
            <Button size="sm" variant={banner.kind === 'ready' ? 'default' : 'outline'} className="h-7 gap-1.5" onClick={() => setQuitting(true)}>
              <Power className="size-3.5" />
              Quit desk…
            </Button>
          )}
          {banner.kind === 'finished' && (
            <Button
              size="sm"
              variant="ghost"
              className="h-7"
              onClick={() => setDismissed((d) => new Set(d).add(banner.run.id))}
            >
              Dismiss
            </Button>
          )}
        </span>
      </div>
      {details && run != null && (
        <ul data-testid="rig-run-units" className="mt-1 grid gap-x-6 gap-y-0.5 pl-6 text-xs sm:grid-cols-2">
          {run.units.map((u) => (
            <li key={u.fixture} className="flex justify-between gap-3">
              <span className="truncate">{u.fixtureName}</span>
              <span className="shrink-0 text-muted-foreground">{unitLine(u, rig.receivedAt, now)}</span>
            </li>
          ))}
        </ul>
      )}
      {quitting && <QuitDeskDialog open={quitting} onOpenChange={setQuitting} />}
      {undoing && <UndoRigSafeDialog projectId={projectId} open={undoing} onOpenChange={setUndoing} />}
    </div>
  )
}

/**
 * The end-of-night state on the `ShowHeader` while immersive: the app header and the banner under it
 * are not drawn then, and a window that says nothing about a blacked-out rig looks like a broken one.
 * A readout, never a control.
 */
export function RigSafetyImmersiveChip() {
  const rig = useRigSafety()
  const running = rig.run != null && !rig.run.finished
  const cooling = !running && !rig.ready && rig.coolingRemainingMs != null
  const now = useNow(cooling)
  if (!rig.ready && !rig.blackout && !running) return null
  const label = running
    ? 'Bulk command running'
    : rig.ready
      ? 'Ready to switch off'
      : cooling
        ? `Cooling · ${clockLabel(coolingLeft(rig, now))}`
        : 'Blacked out'
  return (
    <span
      data-rig-safety-chip
      title={label}
      className={cn(
        'inline-flex h-6 shrink-0 items-center gap-1 rounded-md border px-1.5 text-[11px] font-semibold',
        rig.ready && !running && 'border-emerald-500/70 text-emerald-700 dark:text-emerald-300',
        !rig.ready && !running && !cooling && 'border-amber-500/70 text-amber-700 dark:text-amber-300',
        (running || cooling) && 'text-muted-foreground',
      )}
    >
      {running ? (
        <Loader2 className="size-3 animate-spin" />
      ) : rig.ready ? (
        <ShieldCheck className="size-3" />
      ) : cooling ? (
        <Timer className="size-3" />
      ) : (
        <ShieldAlert className="size-3" />
      )}
      {label}
    </span>
  )
}

import { useState } from 'react'
import { ChevronDown, Lightbulb, RotateCcw, ShieldCheck } from 'lucide-react'
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
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'
import type { CommandRole } from '@/api/rigSafetyApi'
import type { CueTarget } from '@/api/cuesApi'
import { roleLabel } from '@/lib/rigSafety'
import { useCurrentProjectQuery } from '@/store/projects'
import { useSelectionPair } from '@/store/selection'
import { useIsDeskConnected } from '@/store/status'
import { useMakeRigSafeMutation, useRigSafety, useRunBulkCommandMutation } from '@/store/rigSafety'
import { UndoRigSafeDialog } from './UndoRigSafeDialog'

type Scope = 'rig' | 'selection'

/** What a bulk command does, said before it runs. */
const ROLE_SENTENCE: Record<CommandRole, string> = {
  LAMP_ON:
    'Strikes the lamps one at a time, a few seconds apart, to spread the inrush current. A lamp that went off too recently waits out its restrike time and is struck when it is up.',
  LAMP_OFF: 'Douses every discharge lamp. A lamp cannot be struck again until it has cooled — up to 8 minutes on a MAC 250.',
  RESET: 'Re-homes every unit that has a reset. Heads swing and beams move while they run.',
}

/**
 * The Grand Master panel's **Rig** row (venue-visit plan S6, D9, D18, D19): *Make rig safe…*, a
 * *Lamps* menu of bulk commands over the selection or the whole rig, and *Undo* while there is
 * something to undo. Every one asks first; how a run goes is the banner's, on every window.
 */
export function RigSafetyControls() {
  const rig = useRigSafety()
  const connected = useIsDeskConnected()
  const { data: project } = useCurrentProjectQuery()
  const { targets } = useSelectionPair()
  const [makeSafe] = useMakeRigSafeMutation()
  const [runBulk] = useRunBulkCommandMutation()
  const [asking, setAsking] = useState<null | { kind: 'safe' } | { kind: 'bulk'; role: CommandRole; scope: Scope }>(null)
  const [undoing, setUndoing] = useState(false)
  const [safeScope, setSafeScope] = useState<Scope>('rig')

  const running = rig.run != null && !rig.run.finished
  const projectId = project?.id
  const disabled = !connected || projectId == null || running
  const reason = !connected ? DESK_OFFLINE_LABEL : running ? 'A bulk command is running — see the banner' : undefined
  const scopeTargets = (scope: Scope): CueTarget[] | null => (scope === 'selection' ? targets : null)
  const canUndo = rig.undo.length > 0 || rig.blackout

  const confirm = () => {
    const now = asking
    setAsking(null)
    if (now == null || projectId == null) return
    // Refusals reach the screen through errorToastMiddleware; progress through the banner.
    if (now.kind === 'safe') void makeSafe({ projectId, targets: scopeTargets(safeScope) })
    else void runBulk({ projectId, role: now.role, targets: scopeTargets(now.scope) })
  }

  const selectionWords = targets.length === 1 ? '1 selected target' : `${targets.length} selected targets`

  return (
    <div data-testid="rig-safety-controls" className="flex flex-wrap items-center gap-2 border-b bg-background px-4 py-2" title={reason}>
      <div className="shrink-0 text-[9px] font-bold uppercase tracking-wider text-muted-foreground">Rig</div>
      <Button
        size="sm"
        variant="outline"
        disabled={disabled}
        onClick={() => {
          setSafeScope('rig')
          setAsking({ kind: 'safe' })
        }}
        className="gap-1.5"
      >
        <ShieldCheck className="size-3.5" />
        Make rig safe…
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button size="sm" variant="outline" disabled={disabled} className="gap-1.5">
            <Lightbulb className="size-3.5" />
            Lamps
            <ChevronDown className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {(['LAMP_ON', 'LAMP_OFF', 'RESET'] as const).map((role, i) => (
            <div key={role}>
              {i > 0 && <DropdownMenuSeparator />}
              <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">{roleLabel(role)}</DropdownMenuLabel>
              <DropdownMenuItem
                disabled={targets.length === 0}
                onSelect={() => setAsking({ kind: 'bulk', role, scope: 'selection' })}
              >
                {targets.length === 0 ? 'Selection — nothing selected' : `Selection (${selectionWords})`}
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => setAsking({ kind: 'bulk', role, scope: 'rig' })}>Whole rig</DropdownMenuItem>
            </div>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      {canUndo && (
        <Button size="sm" variant="outline" disabled={disabled} onClick={() => setUndoing(true)} className="gap-1.5">
          <RotateCcw className="size-3.5" />
          Undo rig safe
        </Button>
      )}

      <AlertDialog open={asking != null} onOpenChange={(open) => !open && setAsking(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {asking?.kind === 'safe' && 'Make the rig safe?'}
              {asking?.kind === 'bulk' && `${roleLabel(asking.role)} — ${asking.scope === 'rig' ? 'whole rig' : selectionWords}?`}
            </AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="space-y-3">
                {asking?.kind === 'safe' && (
                  <>
                    <p>
                      Lamps off every unit with a lamp, waits for them to go out, then blacks out the rig. The desk keeps
                      running: every window counts down while the lamps cool, then shows <em>Ready to switch off</em>{' '}
                      until something sends output again, and Undo strikes the lamps again.
                    </p>
                    {targets.length > 0 && (
                      <ToggleGroup
                        type="single"
                        size="sm"
                        value={safeScope}
                        onValueChange={(v) => v && setSafeScope(v as Scope)}
                        aria-label="Which lamps"
                      >
                        <ToggleGroupItem value="rig">Every lamp</ToggleGroupItem>
                        <ToggleGroupItem value="selection">The selection&apos;s</ToggleGroupItem>
                      </ToggleGroup>
                    )}
                  </>
                )}
                {asking?.kind === 'bulk' && <p>{ROLE_SENTENCE[asking.role]}</p>}
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={confirm}>
              {asking?.kind === 'safe' && 'Make rig safe'}
              {asking?.kind === 'bulk' && roleLabel(asking.role)}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {undoing && projectId != null && <UndoRigSafeDialog projectId={projectId} open={undoing} onOpenChange={setUndoing} />}
    </div>
  )
}

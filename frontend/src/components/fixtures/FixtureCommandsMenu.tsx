import { useEffect, useState } from 'react'
import { ChevronDown, Wrench } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
import { formatError } from '@/lib/formatError'
import { useCurrentProjectQuery } from '@/store/projects'
import { useVisiblePatchListQuery } from '@/store/patches'
import { useRunFixtureCommandMutation } from '@/store/commands'
import { useGetParkStateListQuery, type ParkState } from '@/store/park'
import type { CommandAttributeDescriptor } from '@/store/fixtures'
import { useRigSafety } from '@/store/rigSafety'
import { clockLabel, restrikeLeft } from '@/lib/rigSafety'
import { useNow } from '@/components/rigSafety/useNow'

/** `3 s`, `1.5 s` — a hold as the confirm names it. */
export function holdLabel(ms: number): string {
  const s = ms / 1000
  return `${Number.isInteger(s) ? s : s.toFixed(1)} s`
}

/**
 * A precondition's reason as it reads mid-sentence: `Open gobo` → `open gobo`, but an initialism keeps
 * its case (`CTC filter in`). Joined with semicolons, since a reason may hold a comma of its own.
 */
export function alongsideSentence(whys: readonly string[]): string {
  return whys
    .map((why) => (/^[A-Z][a-z]/.test(why) ? why.charAt(0).toLowerCase() + why.slice(1) : why))
    .join('; ')
}

/**
 * The first channel a command would hold that is parked, or null. The desk refuses such a command
 * (`COMMAND_PARKED`) — a park outranks the hold, so the unit would never see it — and since session 7
 * also cuts a hold short when a park lands mid-hold; the menu says so before the press rather than
 * after it. Its own channel first, then its preconditions' (which are on the command's universe).
 */
export function parkedChannelOf(command: CommandAttributeDescriptor, parks: readonly ParkState[]): number | null {
  const { universe } = command.channel
  const parked = new Set(parks.filter((p) => p.universe === universe).map((p) => p.channel))
  const held = [command.channel.channelNo, ...(command.alongside ?? []).map((a) => a.channel.channelNo)]
  return held.find((c) => parked.has(c)) ?? null
}

/** Whole seconds left on a hold that ends at [endsAt] (`Date.now()` millis), ticking while it runs. */
function useSecondsLeft(endsAt: number | null): number | null {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (endsAt == null) return
    setNow(Date.now())
    const id = setInterval(() => setNow(Date.now()), 200)
    return () => clearInterval(id)
  }, [endsAt])
  if (endsAt == null) return null
  return Math.max(0, Math.ceil((endsAt - now) / 1000))
}

/**
 * The fixture panel's **Commands** menu (fixture optics plan session 7, §4, D13): a fixture's resets,
 * lamp strike and lamp off, drawn wherever the fixture's controls are. A command is not a control —
 * no Look, cue or programmer value can hold one — so it lives here, behind a confirm that names the
 * unit and the command and says what it does, how long the desk holds it, and what else it sets
 * (the MAC 250's reset wants its colour wheel, gobo and prism just so).
 *
 * While the desk holds it, the button counts the hold down; the request answers when the hold ends,
 * and one cut short (a project switch, a repatch) says so. Only this window counts: a command is a
 * REST call, not a socket frame. Another command on the same unit waits (`COMMAND_BUSY`); Blind and
 * remote access with fixture commands turned off refuse it, by name, as a toast. A command with a
 * parked channel — its own or a precondition's — is greyed out in the menu, naming the channel.
 *
 * A *Lamp on* inside the lamp's restrike time (venue-visit plan S6, D19) says so, with the time left,
 * in the menu and the confirm — from the desk's restrike ledger on `rigSafety.state` — and is still
 * offered: the operator may know the lamp better than the manual does.
 */
export function FixtureCommandsMenu({
  fixtureKey,
  fixtureName,
  commands,
  canRun,
}: {
  fixtureKey: string
  fixtureName: string
  commands: readonly CommandAttributeDescriptor[]
  /** False on a read-only surface or with the desk unreachable. */
  canRun: boolean
}) {
  const { data: project } = useCurrentProjectQuery()
  const projectId = project?.id ?? 0
  const { data: patches } = useVisiblePatchListQuery(projectId, { skip: project == null })
  const patch = patches?.find((p) => p.key === fixtureKey)
  const [run] = useRunFixtureCommandMutation()
  const { data: parks } = useGetParkStateListQuery()
  const [asking, setAsking] = useState<CommandAttributeDescriptor | null>(null)
  const [running, setRunning] = useState<{ command: CommandAttributeDescriptor; endsAt: number } | null>(null)
  const secondsLeft = useSecondsLeft(running?.endsAt ?? null)
  const rig = useRigSafety()
  const lampOff = rig.lampsOff.find((l) => l.fixture === fixtureKey)
  const now = useNow(lampOff != null && lampOff.restrikeRemainingMs > 0, lampOff ? rig.receivedAt + lampOff.restrikeRemainingMs : undefined)
  const restrikeOf = (c: CommandAttributeDescriptor) => (c.role === 'LAMP_ON' ? restrikeLeft(rig, fixtureKey, now) : 0)

  const ready = canRun && project != null && patch != null && running == null

  const start = (command: CommandAttributeDescriptor) => {
    if (project == null || patch == null) return
    setRunning({ command, endsAt: Date.now() + command.holdMs })
    run({ projectId: project.id, patchId: patch.id, command: command.name })
      .unwrap()
      .then((done) => {
        if (done.completed) toast.success(`${command.displayName} on ${fixtureName}: done`)
        else toast.warning(`${command.displayName} on ${fixtureName} was cut short before its ${holdLabel(command.holdMs)} hold ended`)
      })
      .catch((e: unknown) => toast.error(`${command.displayName} on ${fixtureName}: ${formatError(e)}`))
      .finally(() => setRunning(null))
  }

  const choose = (command: CommandAttributeDescriptor) => {
    if (!ready) return
    if (command.confirm) setAsking(command)
    else start(command)
  }

  return (
    <div data-testid="fixture-commands">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="outline" size="sm" disabled={!ready} className="gap-1.5">
            <Wrench className="size-3.5" />
            {running != null ? `${running.command.displayName} · ${secondsLeft ?? 0} s` : 'Commands'}
            {running == null && <ChevronDown className="size-3.5" />}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
            Held for a few seconds, then released — never stored
          </DropdownMenuLabel>
          {commands.map((c) => {
            const parked = parkedChannelOf(c, parks ?? [])
            const restrike = restrikeOf(c)
            return (
              <DropdownMenuItem key={c.name} disabled={parked != null} onSelect={() => choose(c)}>
                <span className="flex flex-1 flex-col">
                  <span>{c.displayName}</span>
                  {parked != null && (
                    <span className="text-xs text-muted-foreground">Channel {parked} is parked — unpark it first</span>
                  )}
                  {parked == null && restrike > 0 && (
                    <span className="text-xs text-amber-700 dark:text-amber-300">Still cooling — restrike in {clockLabel(restrike)}</span>
                  )}
                </span>
                <span className="text-xs text-muted-foreground">{holdLabel(c.holdMs)}</span>
              </DropdownMenuItem>
            )
          })}
        </DropdownMenuContent>
      </DropdownMenu>

      <AlertDialog open={asking != null} onOpenChange={(open) => !open && setAsking(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {asking?.displayName} on {fixtureName}?
            </AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="space-y-2">
                <p>{asking?.description}</p>
                {asking != null && restrikeOf(asking) > 0 && (
                  <p data-testid="restrike-warning" className="text-amber-800 dark:text-amber-200">
                    This lamp went off too recently to restrike reliably: {clockLabel(restrikeOf(asking))} to go.
                  </p>
                )}
                <p>
                  The desk holds it for {asking != null ? holdLabel(asking.holdMs) : ''}, then gives the channel back.
                  {asking?.alongside != null && asking.alongside.length > 0 &&
                    ` For the hold it also sets: ${alongsideSentence(asking.alongside.map((a) => a.why))}.`}
                </p>
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const command = asking
                setAsking(null)
                if (command != null) start(command)
              }}
            >
              {asking?.displayName}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

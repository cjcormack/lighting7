import { useEffect, useState } from 'react'
import { Loader2, Plus, X } from 'lucide-react'
import { toast } from 'sonner'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatError } from '@/lib/formatError'
import { useAuthStatusQuery } from '@/store/auth'
import {
  useLazyOscAddressesQuery,
  useOscSettingsQuery,
  useOscState,
  useSaveOscSettingsMutation,
  type OscSettings,
  type OscSettingsRequest,
} from '@/store/osc'
import type { OscAddressWarning, OscLogRow, OscOutcome, OscState } from '@/api/oscWsApi'
import { useCurrentProjectQuery } from '@/store/projects'
import { describeAddressChange } from '@/lib/oscAddresses'
import { openCheatSheetWindow, printOscCheatSheet } from '@/lib/oscCheatSheet'

/**
 * The OSC tab (lighting7 OSC plan §4, `docs/osc-engineering.md`): whether the desk is listening for
 * QLab and other show-control senders, and the log of what each message did. Every window on the
 * desk's own listener sees the status and the log, from `osc.state` alone, so the operator at the
 * desk can see that a QLab cue did nothing (D12); an admin also sees the settings and the senders,
 * from `GET /install/osc`, and may allow a refused sender in one step.
 */
export function OscPanel() {
  const { data: authStatus } = useAuthStatusQuery()
  const isAdmin = authStatus?.user?.role === 'ADMIN'
  const state = useOscState()
  const { data: settings, error } = useOscSettingsQuery(undefined, { skip: !isAdmin })
  const [allowing, setAllowing] = useState<string | null>(null)

  return (
    <div className="max-w-4xl space-y-4">
      <StatusCard state={state} />
      {isAdmin && error != null && (
        <Alert variant="destructive">
          <AlertDescription>Couldn&rsquo;t load the OSC settings: {formatError(error)}</AlertDescription>
        </Alert>
      )}
      {isAdmin && settings != null && (
        <SettingsCard settings={settings} allowing={allowing} onAllowed={() => setAllowing(null)} />
      )}
      {state != null && state.refusedSenders.length > 0 && (
        <RefusedCard state={state} isAdmin={isAdmin} onAllow={setAllowing} />
      )}
      <CheatSheetCard />
      {(state?.warnings?.length ?? 0) > 0 && <WarningsCard warnings={state!.warnings!} />}
      <LogCard rows={state?.recent ?? []} />
    </div>
  )
}

function StatusCard({ state }: { state: OscState | null }) {
  if (state == null) {
    return (
      <Card className="p-4">
        <p className="text-sm text-muted-foreground">Waiting for the desk…</p>
      </Card>
    )
  }
  const status = !state.enabled ? 'Off' : listeningOn(state) ?? 'Not listening'
  return (
    <Card className="p-4 space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="text-sm font-medium" data-testid="osc-status">{status}</div>
        <div className="text-xs text-muted-foreground">
          {state.received} received · {state.dropped} dropped · {state.refused} refused
        </div>
      </div>
      {state.error && (
        <Alert variant="destructive">
          <AlertDescription>{state.error}</AlertDescription>
        </Alert>
      )}
      <p className="text-xs text-muted-foreground">
        A QLab Network cue sends an address such as <code>/lighting7/stack/act-1/cue/5/go</code> to
        this computer, over UDP or TCP. Only the senders listed here are heard.
      </p>
    </Card>
  )
}

function listeningOn(state: OscState): string | null {
  const on = [state.listening && 'UDP', state.tcpListening && 'TCP'].filter(Boolean)
  return on.length === 0 ? null : `Listening on ${on.join(' and ')} ${state.port}`
}

interface DraftSender {
  id?: number
  name: string
  address: string
  replies?: boolean
}

/** A sender as the desk compares it, so a draft that only spelled `replies: false` out is not dirty. */
const comparable = (list: DraftSender[]) =>
  JSON.stringify(list.map((s) => ({ id: s.id, name: s.name, address: s.address, replies: s.replies === true })))

function SettingsCard({
  settings,
  allowing,
  onAllowed,
}: {
  settings: OscSettings
  /** A refused address the admin chose to allow, which this card adds as a new sender. */
  allowing: string | null
  onAllowed: () => void
}) {
  const [save, { isLoading: saving }] = useSaveOscSettingsMutation()
  const [port, setPort] = useState(String(settings.port))
  const [bindAddress, setBindAddress] = useState(settings.bindAddress ?? '')
  const [senders, setSenders] = useState<DraftSender[]>(settings.senders)
  const [problem, setProblem] = useState<string | null>(null)

  // A save answers the stored document; the draft follows it.
  useEffect(() => {
    setPort(String(settings.port))
    setBindAddress(settings.bindAddress ?? '')
    setSenders(settings.senders)
  }, [settings])

  useEffect(() => {
    if (allowing == null) return
    setSenders((list) => (list.some((s) => s.address === allowing) ? list : [...list, { name: '', address: allowing }]))
    onAllowed()
  }, [allowing, onAllowed])

  const draft = (): OscSettingsRequest => ({
    enabled: settings.enabled,
    port: Number(port),
    bindAddress: bindAddress.trim() || null,
    senders: senders.map((s) => ({ id: s.id, name: s.name.trim(), address: s.address.trim(), replies: s.replies === true })),
  })

  const submit = async (body: OscSettingsRequest) => {
    setProblem(null)
    try {
      await save(body).unwrap()
      toast.success('OSC settings saved')
    } catch (err) {
      setProblem(formatError(err))
    }
  }

  const dirty =
    port !== String(settings.port) ||
    (bindAddress.trim() || null) !== (settings.bindAddress ?? null) ||
    comparable(senders) !== comparable(settings.senders)

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-2">
        <div>
          <div className="text-sm font-medium">Listener</div>
          <p className="text-xs text-muted-foreground">On this computer only; never synced with the show.</p>
        </div>
        {/* The stored document with only the switch flipped, so an unsaved draft never rides along
            and the label is what the desk holds. */}
        <Button
          variant={settings.enabled ? 'outline' : 'default'}
          onClick={() =>
            void submit({
              enabled: !settings.enabled,
              port: settings.port,
              bindAddress: settings.bindAddress ?? null,
              senders: settings.senders,
            })
          }
          disabled={saving}
        >
          {settings.enabled ? 'Disable' : 'Enable'}
        </Button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-1.5">
          <Label htmlFor="osc-port">Port, UDP and TCP</Label>
          <Input id="osc-port" inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value)} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="osc-bind">Interface</Label>
          <Input
            id="osc-bind"
            value={bindAddress}
            placeholder="Every interface"
            onChange={(e) => setBindAddress(e.target.value)}
          />
        </div>
      </div>
      <div className="space-y-2">
        <Label>Senders</Label>
        {senders.length === 0 && (
          <p className="text-xs text-muted-foreground">No senders yet: every packet is refused.</p>
        )}
        {senders.length > 0 && (
          <p className="text-xs text-muted-foreground">
            A sender with <em>Replies</em> is answered with <code>/lighting7/reply</code>: the address, its
            outcome and what it did, to the port it sent from.
          </p>
        )}
        {senders.map((sender, i) => (
          <div key={sender.id ?? `new-${i}`} className="flex items-center gap-2">
            <Input
              aria-label="Sender name"
              placeholder="QLab"
              value={sender.name}
              onChange={(e) => setSenders(senders.map((s, j) => (j === i ? { ...s, name: e.target.value } : s)))}
            />
            <Input
              aria-label="Sender address"
              placeholder="192.168.1.20"
              value={sender.address}
              onChange={(e) => setSenders(senders.map((s, j) => (j === i ? { ...s, address: e.target.value } : s)))}
            />
            <label
              className="flex shrink-0 items-center gap-1.5 text-xs"
              title="Answer each message with /lighting7/reply, saying what it did"
            >
              <input
                type="checkbox"
                aria-label={`Replies to ${sender.name || sender.address || 'sender'}`}
                checked={sender.replies === true}
                onChange={(e) => setSenders(senders.map((s, j) => (j === i ? { ...s, replies: e.target.checked } : s)))}
              />
              Replies
            </label>
            <Button
              variant="ghost"
              size="icon"
              aria-label={`Remove ${sender.name || sender.address || 'sender'}`}
              onClick={() => setSenders(senders.filter((_, j) => j !== i))}
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
        ))}
        <Button variant="outline" size="sm" onClick={() => setSenders([...senders, { name: '', address: '' }])}>
          <Plus className="h-4 w-4" /> Add sender
        </Button>
      </div>
      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
      <div className="flex justify-end">
        <Button onClick={() => void submit(draft())} disabled={!dirty || saving}>
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Save'}
        </Button>
      </div>
    </Card>
  )
}

function RefusedCard({
  state,
  isAdmin,
  onAllow,
}: {
  state: OscState
  isAdmin: boolean
  onAllow: (address: string) => void
}) {
  return (
    <Card className="p-4 space-y-2">
      <div className="text-sm font-medium">Refused senders</div>
      <p className="text-xs text-muted-foreground">Packets from these addresses were dropped unread.</p>
      <ul className="space-y-1">
        {state.refusedSenders.map((sender) => (
          <li key={sender.address} className="flex items-center justify-between gap-2 text-sm">
            <span className="font-mono">{sender.address}</span>
            <span className="text-xs text-muted-foreground">
              {sender.count} · last {timeOf(sender.lastAt)}
            </span>
            {isAdmin && (
              <Button variant="outline" size="sm" onClick={() => onAllow(sender.address)}>
                Allow this sender
              </Button>
            )}
          </li>
        ))}
      </ul>
    </Card>
  )
}

const OUTCOME_WORDS: Record<OscOutcome, string> = {
  DONE: 'Done',
  NOT_FOUND: 'Not found',
  AMBIGUOUS: 'Ambiguous',
  REFUSED: 'Refused',
  FAILED: 'Failed',
  BAD_MESSAGE: 'Bad message',
}

function OutcomeChip({ outcome }: { outcome: OscOutcome }) {
  const variant = outcome === 'DONE' ? 'secondary' : outcome === 'FAILED' ? 'destructive' : 'outline'
  return <Badge variant={variant}>{OUTCOME_WORDS[outcome]}</Badge>
}

/** The addresses the current show answers to, as a printable page for the sound department. */
function CheatSheetCard() {
  const { data: project } = useCurrentProjectQuery()
  const [read, { isFetching }] = useLazyOscAddressesQuery()
  const open = async () => {
    if (project == null) return
    const win = openCheatSheetWindow()
    if (win == null) {
      toast.error('The browser blocked the cheat sheet’s window')
      return
    }
    try {
      printOscCheatSheet(win, await read(project.id).unwrap(), project.name)
    } catch (err) {
      win.close()
      toast.error(`Couldn’t read the addresses: ${formatError(err)}`)
    }
  }
  return (
    <Card className="p-4 space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm font-medium">Addresses</div>
        <Button variant="outline" size="sm" disabled={project == null || isFetching} onClick={() => void open()}>
          {isFetching && <Loader2 className="size-3.5 animate-spin" />}
          Print cheat sheet
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        Every address {project != null ? <em>{project.name}</em> : 'this show'} answers to, for the sound
        department. A stack&rsquo;s, a cue&rsquo;s, a Look&rsquo;s and a busk pad&rsquo;s menu each have{' '}
        <em>Copy OSC address</em>.
      </p>
    </Card>
  )
}

/** Addresses a sender has used that a change since moved: newest first, as the desk keeps them. */
function WarningsCard({ warnings }: { warnings: OscAddressWarning[] }) {
  return (
    <Card className="p-4 space-y-2">
      <div className="text-sm font-medium">Addresses that moved</div>
      <ul className="divide-y">
        {warnings.map((warning) => (
          <li key={warning.id} className="grid grid-cols-[auto_1fr] gap-x-3 py-1.5 text-sm">
            <span className="text-xs text-muted-foreground tabular-nums">{timeOf(warning.at)}</span>
            <ul className="space-y-0.5">
              {warning.changes.map((change) => (
                <li key={change.address} className="text-xs break-all">
                  {describeAddressChange(change)}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function LogCard({ rows }: { rows: OscLogRow[] }) {
  return (
    <Card className="p-4 space-y-2">
      <div className="text-sm font-medium">Received</div>
      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground">Nothing received since the desk started.</p>
      ) : (
        <ul className="divide-y">
          {rows.map((row, i) => (
            <li key={`${row.at}-${i}`} className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 py-1.5 text-sm">
              <span className="text-xs text-muted-foreground tabular-nums">{timeOf(row.at)}</span>
              <span className="flex flex-wrap items-center gap-2">
                <OutcomeChip outcome={row.outcome} />
                <span className="font-mono text-xs break-all">
                  {row.address}
                  {row.args.length > 0 && ` ${row.args.join(' ')}`}
                </span>
                <span className="text-xs text-muted-foreground">{row.sender}</span>
              </span>
              <span />
              <span className="text-xs text-muted-foreground">{row.detail}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function timeOf(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleTimeString()
}

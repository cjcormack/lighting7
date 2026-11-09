import { useRef, useState } from 'react'
import type { OscState } from '@/api/oscWsApi'
import { Copy, Loader2, RefreshCw } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { formatError } from '@/lib/formatError'
import { isRemoteMode } from '@/lib/remoteMode'
import { oscAddressMatches, oscAddressSections, type OscAddress, type OscAddressList as AddressList } from '@/lib/oscAddresses'
import { useOscAddressesQuery } from '@/store/osc'
import { useCurrentProjectQuery } from '@/store/projects'
import { copyOscAddress } from './CopyOscAddressMenu'

/**
 * Every address the current show answers to (lighting7 venue-rig-fixes plan D4), on screen for
 * whoever sets up QLab: by kind, filterable, each with a copy button, then the messages that are
 * ambiguous and the records that have no address. Read each time it mounts and on Refresh, since no
 * frame says an address moved. Absent in remote mode, where the desk refuses the listing.
 */
export function OscAddressList({ state }: { state: OscState | null }) {
  const { data: project } = useCurrentProjectQuery()
  if (isRemoteMode()) return null
  return (
    <Card className="p-4 space-y-3">
      {project == null ? (
        <>
          <div className="text-sm font-medium">Addresses</div>
          <p className="text-xs text-muted-foreground">No show is open.</p>
        </>
      ) : (
        <AddressListBody projectId={project.id} projectName={project.name} listening={listeningLabel(state)} />
      )}
    </Card>
  )
}

/** What the desk listens on, for the header: nothing before the first `osc.state` or when it binds neither. */
function listeningLabel(state: OscState | null): string | null {
  if (state == null) return null
  if (!state.enabled) return 'OSC is off'
  const udp = state.listening
  const tcp = state.tcpListening === true
  if (udp && tcp) return `port ${state.port} · UDP and TCP`
  if (udp) return `port ${state.port} · UDP`
  if (tcp) return `port ${state.port} · TCP`
  return null
}

function AddressListBody({ projectId, projectName, listening }: { projectId: number; projectName: string; listening: string | null }) {
  const { data, isFetching, isError, error, refetch } = useOscAddressesQuery(projectId, { refetchOnMountOrArgChange: true })
  const [query, setQuery] = useState('')

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-sm font-medium">
          Addresses · <em>{projectName}</em>
        </div>
        <div className="flex items-center gap-2">
          {listening != null && <span className="text-xs text-muted-foreground">{listening}</span>}
          <Button variant="outline" size="sm" disabled={isFetching} onClick={() => void refetch()}>
            {isFetching ? <Loader2 className="size-3.5 animate-spin" /> : <RefreshCw className="size-3.5" />}
            Refresh
          </Button>
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        A rename, a renumber or a moved pad changes an address. A stack&rsquo;s, a cue&rsquo;s, a Look&rsquo;s and a busk
        pad&rsquo;s menu each have <em>Copy OSC address</em>.
      </p>
      <Input
        aria-label="Filter addresses"
        placeholder="Filter by address, name or what it does"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {isError && (
        <Alert variant="destructive">
          <AlertDescription>
            Couldn&rsquo;t read the addresses: {formatError(error)}
            {data != null && ' — the list below may be out of date.'}
          </AlertDescription>
        </Alert>
      )}
      {data == null && !isError && <p className="text-xs text-muted-foreground">Reading the addresses…</p>}
      {data != null && <Sections list={data} query={query} />}
    </>
  )
}

function Sections({ list, query }: { list: AddressList; query: string }) {
  const q = query.trim().toLowerCase()
  const sections = oscAddressSections(list, list.addresses.filter((a) => oscAddressMatches(a, q)))
  const ambiguous = list.ambiguous.filter(
    (a) => q === '' || a.message.toLowerCase().includes(q) || a.addresses.some((x) => x.toLowerCase().includes(q)),
  )
  const unaddressed = list.unaddressed.filter(
    (u) => q === '' || u.name.toLowerCase().includes(q) || u.reason.toLowerCase().includes(q),
  )
  if (sections.length === 0 && ambiguous.length === 0 && unaddressed.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        {q === '' ? 'This show answers to no addresses yet.' : `No address matches “${query.trim()}”.`}
      </p>
    )
  }
  return (
    <div className="space-y-4">
      {sections.map((section) => (
        <section key={section.title} aria-label={section.title} className="space-y-1">
          <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{section.title}</h3>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-muted-foreground">
                <th className="py-1 pr-3 font-normal">Address</th>
                <th className="py-1 pr-3 font-normal">Args</th>
                <th className="py-1 pr-3 font-normal">Reaches</th>
                <th className="w-8 py-1" />
              </tr>
            </thead>
            <tbody className="divide-y">
              {section.addresses.map((address) => (
                <AddressRow key={address.address} address={address} />
              ))}
            </tbody>
          </table>
        </section>
      ))}
      {ambiguous.length > 0 && (
        <section aria-label="Ambiguous" className="space-y-1">
          <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Ambiguous</h3>
          <ul className="space-y-1 text-xs">
            {ambiguous.map((a) => (
              <li key={a.message}>
                {a.message} — <span className="font-mono break-all">{a.addresses.join(', ')}</span> answer to none of them
              </li>
            ))}
          </ul>
        </section>
      )}
      {unaddressed.length > 0 && (
        <section aria-label="No address" className="space-y-1">
          <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">No address</h3>
          <ul className="space-y-1 text-xs">
            {unaddressed.map((u) => (
              <li key={`${u.kind}-${u.id}`}>
                {u.name}: {u.reason}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function AddressRow({ address }: { address: OscAddress }) {
  const cell = useRef<HTMLElement>(null)
  return (
    <tr className="align-top">
      <td className="py-1 pr-3">
        <code ref={cell} className="font-mono text-xs break-all">
          {address.address}
        </code>
      </td>
      <td className="py-1 pr-3 text-xs whitespace-nowrap">{address.args === '–' ? '' : address.args}</td>
      <td className="py-1 pr-3 text-xs" title={address.does}>
        {address.reaches}
      </td>
      <td className="py-0.5">
        <Button
          variant="ghost"
          size="icon"
          className="size-7"
          aria-label={`Copy ${address.address}`}
          onClick={() => copyOscAddress(address.address, cell.current)}
        >
          <Copy className="size-3.5" />
        </Button>
      </td>
    </tr>
  )
}

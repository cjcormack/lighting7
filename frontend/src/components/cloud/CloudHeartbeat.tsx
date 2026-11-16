import { useState } from 'react'
import { useCloudHeartbeatQuery } from '@/store/cloud'

/** How often an open disclosure refetches: the desk builds a heartbeat every few tens of seconds. */
const REFRESH_MS = 15_000

/**
 * *What the desk sends*: the last heartbeat, as JSON, so the owner can see exactly what leaves the
 * desk. Fetched only while open.
 */
export function CloudHeartbeat() {
  const [open, setOpen] = useState(false)
  return (
    <details
      className="text-sm"
      data-testid="cloud-heartbeat"
      onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
    >
      <summary className="cursor-pointer text-muted-foreground">What the desk sends</summary>
      {open && <HeartbeatBody />}
    </details>
  )
}

function HeartbeatBody() {
  const { data, isLoading, error } = useCloudHeartbeatQuery(undefined, { pollingInterval: REFRESH_MS })
  if (isLoading) return <p className="mt-2 text-xs text-muted-foreground">Loading…</p>
  if (error != null) return <p className="mt-2 text-xs text-destructive">Could not read the last heartbeat.</p>
  if (data?.health == null) {
    return <p className="mt-2 text-xs text-muted-foreground">No heartbeat sent yet: one goes once the desk is online.</p>
  }
  return (
    <div className="mt-2 space-y-1">
      <p className="text-xs text-muted-foreground">
        The heartbeat built {data.sentAt != null ? new Date(data.sentAt).toLocaleString() : ''}. The show’s name and
        its controllers’ addresses are the only names in it; nothing the show holds is ever sent.
      </p>
      <pre className="max-h-80 overflow-auto rounded-md bg-muted p-3 font-mono text-xs" data-testid="cloud-heartbeat-json">
        {JSON.stringify(data.health, null, 2)}
      </pre>
    </div>
  )
}

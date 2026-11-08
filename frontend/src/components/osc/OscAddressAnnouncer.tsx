import { useEffect } from 'react'
import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'
import { describeAddressChange, newestWarningId, unseenWarnings } from '@/lib/oscAddresses'

/**
 * Says so when a change moved an OSC address a sender has used since the desk started — a rename, a
 * renumber, an insert or delete relabelling auto numbers, a pad moved, a speed master deleted
 * (lighting7 OSC plan §4). The desk works it out and sends it in `osc.state`; every window on its
 * own listener toasts each new one, since whoever made the change is the one to warn. A window
 * opened later announces nothing it missed: the OSC tab lists them. The desk numbers them from its
 * boot's clock, so a window that outlives a desk restart still reads the next as new. Mounted once
 * by `Layout`.
 */
export function OscAddressAnnouncer() {
  useEffect(() => {
    let seen = newestWarningId(lightingApi.osc.current()?.warnings)
    let first = lightingApi.osc.current() == null
    const sub = lightingApi.osc.subscribe((state) => {
      // The connect snapshot carries warnings from before this window; they are not news.
      if (first) {
        first = false
        seen = Math.max(seen, newestWarningId(state.warnings))
        return
      }
      for (const warning of unseenWarnings(state.warnings, seen)) {
        const n = warning.changes.length
        toast.warning(n === 1 ? 'An OSC address moved' : `${n} OSC addresses moved`, {
          id: `osc-address-warning-${warning.id}`,
          description: (
            <ul className="space-y-0.5">
              {warning.changes.map((change) => (
                <li key={change.address} className="break-all">
                  {describeAddressChange(change)}
                </li>
              ))}
            </ul>
          ),
          duration: 20_000,
        })
      }
      seen = Math.max(seen, newestWarningId(state.warnings))
    })
    return () => sub.unsubscribe()
  }, [])
  return null
}

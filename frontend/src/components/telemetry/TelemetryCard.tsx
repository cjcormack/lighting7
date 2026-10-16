import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router'
import { toast } from 'sonner'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { useTelemetryQuery, useUpdateTelemetryMutation } from '@/store/telemetry'

/**
 * The install's telemetry switch (Sentry plan D1), for an admin: one choice for the desk and every
 * browser on it. Turning it off stops both at once — the desk stops its own Sentry and refuses its
 * browsers' envelopes. See `docs/observability-engineering.md`.
 */
export function TelemetryCard() {
  const { data: telemetry } = useTelemetryQuery()
  const [update, { isLoading: saving }] = useUpdateTelemetryMutation()
  const { hash } = useLocation()
  const card = useRef<HTMLDivElement>(null)
  const loaded = telemetry != null
  // `/install#telemetry` (⌘K's *Telemetry…*) lands here, once the card has drawn.
  useEffect(() => {
    if (loaded && hash === '#telemetry') card.current?.scrollIntoView({ block: 'start' })
  }, [loaded, hash])
  if (telemetry == null) return null

  const onToggle = async () => {
    const enabled = !telemetry.enabled
    // A failure is errorToastMiddleware's to report.
    const answer = await update({ enabled }).unwrap().catch(() => null)
    if (enabled && answer?.enabled === false) toast.warning('sentry.enabled = false in local.conf keeps telemetry off on this machine')
  }

  return (
    <Card ref={card} id="telemetry" className="p-4 max-w-2xl space-y-2 scroll-mt-4">
      <div className="flex items-center justify-between gap-4">
        <h3 className="text-sm font-medium">Send diagnostics</h3>
        <button
          type="button"
          role="switch"
          aria-checked={telemetry.enabled}
          aria-label="Send diagnostics"
          disabled={saving}
          onClick={() => void onToggle()}
          className={cn(
            'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border-2 border-transparent transition-colors',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
            telemetry.enabled ? 'bg-primary' : 'bg-input',
          )}
        >
          <span
            className={cn(
              'pointer-events-none block h-5 w-5 rounded-full bg-background shadow transition-transform',
              telemetry.enabled ? 'translate-x-5' : 'translate-x-0',
            )}
          />
        </button>
      </div>
      <p className="text-sm text-muted-foreground">
        Sends errors, logs, performance traces and metrics from this desk and its browsers to the
        lighting7 developers&apos; Sentry. Tokens and passwords are removed first.
      </p>
    </Card>
  )
}

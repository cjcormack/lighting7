import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/utils'

interface FeatureErrorBoundaryProps {
  /** Named in the message, in operator words: "the Stage view", "Lux", "the script editor". */
  feature: string
  /** Positioning for the fallback card. Overlays need to place theirs; page content doesn't. */
  className?: string
  children: ReactNode
}

interface FeatureErrorBoundaryState {
  error: Error | null
}

/**
 * Contains a render or chunk-load failure to the feature it wraps, instead of unmounting the desk.
 *
 * This exists because of `React.lazy`. A statically bundled component can only fail by throwing
 * during render; a lazily imported one can also fail by never arriving. The desk is unusually
 * exposed to that second case: the Windows updater overwrites the running install's frontend
 * statics in place, so a session that booted before an update and navigates to a lazy route after
 * one asks for a hashed chunk filename that no longer exists. The `import()` rejects, `React.lazy`
 * rethrows during render, and with no boundary above it React unmounts the whole tree — a blank
 * screen on a desk that may be mid-show, in place of one view that failed to open.
 *
 * A reload is the fix rather than a retry: the chunk is gone, not slow, so only a fresh
 * `index.html` names assets that still exist. It stays operator-triggered — reloading a live desk
 * out from under someone is not a decision this component gets to make.
 *
 * It catches ordinary render errors below it too, which is incidental but wanted: containing a
 * page crash to the page area beats blanking the desk for that as well.
 */
export class FeatureErrorBoundary extends Component<
  FeatureErrorBoundaryProps,
  FeatureErrorBoundaryState
> {
  state: FeatureErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): FeatureErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // The desk's diagnostics are the browser console; nothing else collects these.
    console.error(`[${this.props.feature}] failed to render`, error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <Card className={cn('m-4 p-4 flex flex-col gap-3', this.props.className)}>
        <div className="flex items-start gap-2">
          <AlertTriangle className="size-5 shrink-0 text-destructive" />
          <div className="min-w-0">
            <p className="font-medium">{this.props.feature} could not be loaded.</p>
            <p className="text-sm text-muted-foreground">
              This usually means the desk was updated while this page was open. Reloading will pick
              up the new version. The show keeps running either way: the lights are the backend&apos;s.
            </p>
            <p className="mt-1 text-xs text-muted-foreground break-words">{error.message}</p>
          </div>
        </div>
        <div>
          <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
            Reload
          </Button>
        </div>
      </Card>
    )
  }
}

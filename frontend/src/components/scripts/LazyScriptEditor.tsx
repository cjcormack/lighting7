import { lazy, Suspense } from 'react'
import { Loader2 } from 'lucide-react'
import { FeatureErrorBoundary } from '@/components/FeatureErrorBoundary'
import type { ScriptEditorProps } from './ScriptEditor'

export type { ScriptEditorProps, ScriptEditorScript } from './ScriptEditor'

/**
 * The `kotlin-playground` widget behind a lazy boundary.
 *
 * The widget and its bundled CodeMirror are ~510 kB of the app, and only three surfaces mount
 * it: the Scripts page, the FX Library's effect editors, and the cue trigger editor inside
 * Show. Two of those are routes, but `CueTriggerEditor` is not — it is a sheet inside a page
 * that is otherwise cheap — so splitting at the routes alone would leave the widget in the main
 * chunk. The boundary therefore sits at the widget's own component, which is the only place
 * every consumer passes through.
 *
 * Import `LazyScriptEditor`, not `ScriptEditor`: a static import of the latter from anywhere
 * reachable at boot puts the widget straight back in the entry chunk.
 */
const ScriptEditorImpl = lazy(() =>
  import('./ScriptEditor').then((m) => ({ default: m.ScriptEditor })),
)

/**
 * Reserves the editor's box while the chunk loads, so the surrounding sheet or page doesn't
 * reflow when it arrives. The real editor is content-sized, so this is a plausible floor rather
 * than an exact match.
 */
function ScriptEditorFallback() {
  return (
    <div className="flex min-h-48 items-center justify-center" aria-busy="true">
      <Loader2 className="size-6 animate-spin text-muted-foreground" />
    </div>
  )
}

export function LazyScriptEditor(props: ScriptEditorProps) {
  return (
    // Every surface that mounts the editor is a sheet over a page the operator still needs, so a
    // chunk that never arrives has to stay inside the sheet rather than take the desk down with it.
    <FeatureErrorBoundary feature="The script editor" className="m-2">
      <Suspense fallback={<ScriptEditorFallback />}>
        <ScriptEditorImpl {...props} />
      </Suspense>
    </FeatureErrorBoundary>
  )
}

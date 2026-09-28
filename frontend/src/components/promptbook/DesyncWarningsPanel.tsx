import { TriangleAlert } from 'lucide-react'
import type { DesyncWarning } from '../../lib/promptBook/desync'

/**
 * Pre-flight desync summary — advisory only, never blocks or reorders.
 * Rendered at the top of the cue panel so issues are seen before the show runs.
 */
export function DesyncWarningsPanel({
  warnings,
  onWarningClick,
}: {
  warnings: DesyncWarning[]
  onWarningClick: (warning: DesyncWarning) => void
}) {
  if (warnings.length === 0) return null
  return (
    <div className="mx-3 mt-3 rounded-md border border-red-500/40 bg-red-950/20 px-3 py-2">
      <div className="flex items-center gap-1.5 text-xs font-bold text-red-500">
        <TriangleAlert className="size-3.5" />
        {warnings.length} issue{warnings.length > 1 ? 's' : ''} before you run
      </div>
      <ul className="mt-1.5 space-y-1">
        {warnings.map((w, i) => (
          <li key={`${w.kind}-${w.cueId}-${i}`}>
            <button
              type="button"
              onClick={() => onWarningClick(w)}
              className="text-left text-xs leading-snug text-red-200/90 hover:text-red-100 hover:underline"
            >
              {w.message}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

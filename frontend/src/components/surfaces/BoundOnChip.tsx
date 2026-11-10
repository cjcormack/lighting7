import { useNavigate } from 'react-router'
import { Sliders } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { surfaceUseKey, useSurfaceUses, useSurfaceUsesProjectId, type SurfaceSubject, type SurfaceUse } from '@/store/surfaces'

/** The chip's words: `Bound on Button 36 · A`, and `+2` for the rest; null when nothing is bound. */
export function boundOnText(uses: readonly SurfaceUse[]): string | null {
  if (uses.length === 0) return null
  const more = uses.length - 1
  return `Bound on ${uses[0].label}${more > 0 ? ` +${more}` : ''}`
}

/** One use in full, for the chip's hover: `X-Touch Compact · Button 36 · A — GO · Act 1 (from its job)`. */
export function describeUse(use: SurfaceUse): string {
  const does = use.does ? ` — ${use.does}` : ''
  return `${use.deviceLabel} · ${use.label}${does}${use.fromJob ? ' (from its job)' : ''}`
}

/** The Surfaces page on the control a use names, viewing its layer. */
export function surfaceLink(projectId: number, use: SurfaceUse): string {
  const params = new URLSearchParams({ type: use.deviceTypeKey, control: use.controlId })
  if (use.layer != null) params.set('layer', use.layer)
  return `/projects/${projectId}/settings/surfaces?${params.toString()}`
}

/** What the desk says acts on [subjects] in the current project ([projectId], when given, must be it). */
export function useBoundOn(subjects: readonly SurfaceSubject[], projectId?: number): { projectId: number | null; uses: readonly SurfaceUse[] } {
  const uses = useSurfaceUses(subjects.map(surfaceUseKey), projectId)
  const current = useSurfaceUsesProjectId()
  return { projectId: uses.length > 0 ? current : null, uses }
}

/**
 * *Bound on Button 36 · A* (surface-setup plan §4, D): the controls acting on a record, a group or a
 * fixture, from the desk's index (`surfaceUses.state`). Silent when none is; a click opens the
 * Surfaces page on the first.
 */
export function BoundOnChip({ subjects, projectId, className }: { subjects: readonly SurfaceSubject[]; projectId?: number; className?: string }) {
  const bound = useBoundOn(subjects, projectId)
  if (bound.projectId == null) return null
  return <BoundOnLink projectId={bound.projectId} uses={bound.uses} className={className} />
}

function BoundOnLink({ projectId, uses, className }: { projectId: number; uses: readonly SurfaceUse[]; className?: string }) {
  const navigate = useNavigate()
  const text = boundOnText(uses)
  if (text == null) return null
  return (
    <TooltipProvider delayDuration={200}>
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            className={cn('inline-flex', className)}
            onClick={(e) => {
              e.stopPropagation()
              e.preventDefault()
              navigate(surfaceLink(projectId, uses[0]))
            }}
          >
            <Badge variant="outline" className="h-5 cursor-pointer gap-1 px-1.5 text-[10px] font-normal hover:bg-accent">
              <Sliders className="size-2.5" />
              {text}
            </Badge>
          </button>
        </TooltipTrigger>
        <TooltipContent>
          <ul className="space-y-0.5 text-xs">
            {uses.map((use) => (
              <li key={`${use.deviceTypeKey}|${use.controlId}|${use.part}|${use.layer ?? ''}`}>{describeUse(use)}</li>
            ))}
          </ul>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}

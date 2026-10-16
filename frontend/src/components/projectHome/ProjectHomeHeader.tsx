import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { ProjectAction } from '@/lib/projectActions'
import { ProjectActionsMenu } from '../projectActions/ProjectActions'
import { VenueTag } from '../venue/VenueTag'

/**
 * A project home's header (navigation plan §5.1, §5.2): the name and description, then **On the
 * rig** or **Make current**, then the ⋮ with every project action. A show's home and a venue's page
 * share it. *Make current* is the action's own: refused, it is drawn disabled with the reason.
 */
export function ProjectHomeHeader({
  project,
  actions,
}: {
  project: { name: string; description: string | null; isCurrent: boolean; kind?: string }
  actions: readonly ProjectAction[]
}) {
  const makeCurrent = actions.find((a) => a.id === 'make-current' && a.visible)
  return (
    <div className="flex flex-wrap items-start gap-x-4 gap-y-2" data-project-home-header="">
      <div className="min-w-0 flex-1">
        <h1 className="flex items-center gap-2 text-2xl font-bold sm:text-3xl">
          <span className="min-w-0 break-words">{project.name}</span>
          {project.kind === 'VENUE' && <VenueTag className="shrink-0" />}
        </h1>
        {project.description && <p className="mt-1 text-muted-foreground">{project.description}</p>}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {project.isCurrent ? (
          <Badge
            data-on-rig=""
            className="h-8 gap-1.5 border-green-600/40 bg-green-500/15 px-3 text-sm text-green-800 dark:text-green-300"
            variant="outline"
          >
            <span aria-hidden className="size-2 rounded-full bg-green-500" />
            On the rig
          </Badge>
        ) : makeCurrent ? (
          <Button
            size="sm"
            data-make-current=""
            disabled={!makeCurrent.enabled}
            title={makeCurrent.reason ?? undefined}
            onClick={makeCurrent.run}
          >
            Make current
          </Button>
        ) : null}
        <ProjectActionsMenu actions={actions} label={`${project.name} actions`} />
      </div>
      {makeCurrent && !makeCurrent.enabled && makeCurrent.reason && (
        <p className="w-full text-sm text-muted-foreground" data-make-current-refusal="">
          {makeCurrent.reason}, so it can&rsquo;t be made current.
        </p>
      )}
    </div>
  )
}

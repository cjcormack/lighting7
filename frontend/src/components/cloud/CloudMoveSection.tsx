import { useState } from 'react'
import { Loader2, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatError } from '@/lib/formatError'
import { formatRepoUrl } from '@/lib/formatRepoUrl'
import {
  useCloudMoveMutation,
  useCloudMovePlanQuery,
  type CloudMovePlanRow,
  type CloudMoveRow,
} from '@/store/cloudSync'

/**
 * *Move to the cloud* on the Cloud tab: every project syncing with GitHub, venues first, moved with its
 * history to a cloud repo, a row per project with how it went, and a re-run for the ones that failed.
 * The desk's own listener only: over remote access it says so and moves nothing.
 */
export function CloudMoveSection({ remote }: { remote: boolean }) {
  const { data: plan, error, isLoading } = useCloudMovePlanQuery(undefined, { skip: remote })
  const [move, { isLoading: moving, error: moveError, reset }] = useCloudMoveMutation()
  const [results, setResults] = useState<CloudMoveRow[]>([])

  if (remote) {
    return (
      <Section>
        <p className="text-sm text-muted-foreground">
          Projects can only be moved to the cloud from the desk itself, never over remote access.
        </p>
      </Section>
    )
  }
  if (isLoading) {
    return (
      <Section>
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" /> Looking for GitHub-synced projects…
        </p>
      </Section>
    )
  }
  const planned = plan?.projects ?? []
  if (planned.length === 0 && results.length === 0 && error == null) return null
  const pushEnabled = plan?.pushEnabled !== false
  const rows = planned.filter((row) => !results.some((result) => result.projectId === row.projectId))

  const run = async (projectIds: number[]) => {
    reset()
    try {
      const moved = await move(projectIds).unwrap()
      setResults((previous) => [
        ...previous.filter((row) => !moved.some((next) => next.projectId === row.projectId)),
        ...moved,
      ])
    } catch {
      // Shown below.
    }
  }
  const failed = results.filter((row) => row.status === 'FAILED').map((row) => row.projectId)

  return (
    <Section>
      <p className="text-sm text-muted-foreground">
        These projects still sync with GitHub. Moving one pushes its whole history to a cloud repo, every
        commit unchanged, then syncs it there. GitHub keeps its copy.
      </p>
      {error != null && <ErrorAlert error={error} />}
      {rows.length > 0 && (
        <ul className="divide-y rounded-md border text-sm" data-testid="cloud-move-plan">
          {rows.map((row) => (
            <PlanRow key={row.projectId} row={row} />
          ))}
        </ul>
      )}
      {results.length > 0 && (
        <ul className="divide-y rounded-md border text-sm" data-testid="cloud-move-results">
          {results.map((row) => (
            <ResultRow key={row.projectId} row={row} />
          ))}
        </ul>
      )}
      {!pushEnabled ? (
        <p className="text-sm text-muted-foreground">
          This desk only pulls (sync.push = false in local.conf), so it cannot move a project: move it from a desk
          that pushes.
        </p>
      ) : (
        <div className="flex gap-2">
          {failed.length > 0 ? (
            <Button disabled={moving} onClick={() => void run(failed)}>
              {moving ? 'Moving…' : failed.length === 1 ? 'Try it again' : `Try the ${failed.length} that failed again`}
            </Button>
          ) : (
            rows.length > 0 && (
              <Button disabled={moving} onClick={() => void run(rows.map((row) => row.projectId))}>
                {moving ? 'Moving…' : rows.length === 1 ? 'Move to the cloud' : `Move ${rows.length} projects to the cloud`}
              </Button>
            )
          )}
        </div>
      )}
      {moveError != null && <ErrorAlert error={moveError} />}
    </Section>
  )
}

function Section({ children }: { children: React.ReactNode }) {
  return (
    <section className="space-y-3" data-testid="cloud-move">
      <h3 className="text-sm font-medium">Move GitHub-synced projects to the cloud</h3>
      {children}
    </section>
  )
}

function PlanRow({ row }: { row: CloudMovePlanRow }) {
  return (
    <li className="flex items-center justify-between gap-2 px-3 py-2">
      <span className="min-w-0">
        <span className="font-medium">{row.name}</span>
        <span className="ml-2 text-muted-foreground">{formatRepoUrl(row.repoUrl)}</span>
        {row.cloudHasRepo && (
          <span className="block text-xs text-muted-foreground">
            Already on the cloud{row.cloudRepoName != null ? ` as ${row.cloudRepoName}` : ''}, moved from another desk.
          </span>
        )}
      </span>
      {row.kind === 'VENUE' && <Badge variant="secondary">Venue</Badge>}
    </li>
  )
}

function ResultRow({ row }: { row: CloudMoveRow }) {
  let line: string
  switch (row.status) {
    case 'MOVED':
      line = `Moved to ${row.repoName ?? 'its cloud repo'}.`
      break
    case 'MOVED_DIVERGED':
      line = `Moved to ${row.repoName ?? 'its cloud repo'}. Another desk changed it there too: its next sync merges the two.`
      break
    case 'FAILED':
      line = `Not moved: ${row.message ?? row.code ?? 'the move failed'}. It still syncs with GitHub.`
      break
  }
  return (
    <li className="space-y-1 px-3 py-2" data-status={row.status}>
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">{row.name}</span>
        <Badge variant={row.status === 'FAILED' ? 'destructive' : 'secondary'}>
          {row.status === 'FAILED' ? 'Failed' : 'Moved'}
        </Badge>
      </div>
      <p className={row.status === 'FAILED' ? 'text-xs text-destructive' : 'text-xs text-muted-foreground'}>{line}</p>
      {row.fromWorkingTree === true && row.status !== 'FAILED' && (
        <p className="text-xs text-muted-foreground">
          GitHub could not be reached first, so what this desk holds was moved.
        </p>
      )}
      {row.verifyError != null && (
        <p className="text-xs text-destructive">Its first sync with the cloud failed: {row.verifyError.error}</p>
      )}
    </li>
  )
}

function ErrorAlert({ error }: { error: unknown }) {
  return (
    <Alert variant="destructive">
      <TriangleAlert className="h-4 w-4" />
      <AlertDescription>{formatError(error)}</AlertDescription>
    </Alert>
  )
}

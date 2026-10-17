import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { OffRigDot } from '@/components/NotOnRig'
import { useSyncNow } from '@/components/cloudSync/useSyncNow'
import type { ProjectDetail } from '@/api/projectApi'
import { syncLineOf } from '@/lib/projectHome'
import { cn } from '@/lib/utils'
import {
  OFF_RIG_LABEL,
  filterNavItems,
  homeSectionsOf,
  navItems,
  useIsNavAdmin,
  type HomeCounts,
  type ShownNavItem,
} from '@/navigation'
import { useHomeCounts } from './useHomeCounts'

/**
 * The pieces a show's home and a venue's home share (navigation plan §5.1, §5.2): a status line, the
 * Sync line and the sections. Each home draws its own status block from them.
 */

/** One fact of a home's status block: a label, the words, and the line's buttons. */
export function StatusRow({
  label,
  className,
  children,
  actions,
  fact,
}: {
  label: string
  className?: string
  children: ReactNode
  actions?: ReactNode
  fact: string
}) {
  return (
    <div className={cn('flex flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2', className)} data-status-row={fact}>
      <dt className="w-14 shrink-0 text-[10px] font-bold uppercase tracking-wider text-muted-foreground">{label}</dt>
      {/* A basis rather than flex-1, so on a phone the buttons wrap under the words rather than squeeze them. */}
      <dd className="min-w-0 flex-[1_1_12rem]">{children}</dd>
      {actions && <dd className="flex max-w-full flex-wrap items-center gap-2">{actions}</dd>}
    </div>
  )
}

/**
 * The Sync line, an admin's (every cloud-sync route is): the repo, its state and when, and *Sync
 * now* through `useSyncNow` — a venue's run toasts the versions it fetched and pushed.
 */
export function SyncRow({ projectId, isVenue = false }: { projectId: number; isVenue?: boolean }) {
  const { config, run, isSyncing, disabledReason } = useSyncNow(projectId, { isVenue })
  const now = useMinuteClock()
  return (
    <StatusRow
      label="Sync"
      fact="sync"
      actions={
        config?.repoUrl != null && (
          <Button
            variant="outline"
            size="sm"
            className="h-7"
            disabled={disabledReason != null || isSyncing}
            onClick={run}
          >
            <RefreshCw className={cn('size-3.5', isSyncing && 'animate-spin')} />
            {isSyncing ? 'Syncing…' : 'Sync now'}
          </Button>
        )
      }
    >
      <span className={cn('min-w-0 break-words', config?.synced !== true && 'text-muted-foreground')}>
        {config ? syncLineOf(config, now) : 'Reading…'}
      </span>
      {config?.repoUrl != null && disabledReason != null && (
        <span className="block text-xs text-muted-foreground" data-sync-refusal="">
          {disabledReason}
        </span>
      )}
    </StatusRow>
  )
}

/** The time, moved on once a minute, for *2 min ago*. */
function useMinuteClock(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(timer)
  }, [])
  return now
}

/** The registry's sections less Desk, as cards — the sidebar's rows for this project, never a hand-picked list. */
export function Sections({ project }: { project: ProjectDetail }) {
  const isAdmin = useIsNavAdmin()
  const counts = useHomeCounts(project)
  const sections = homeSectionsOf(filterNavItems(navItems, project.isCurrent, isAdmin, project.kind === 'VENUE'))
  return (
    <div className="space-y-5">
      {sections.map((section) => (
        <section key={section.section} aria-labelledby={`home-section-${section.section}`} data-home-section={section.section}>
          <h2
            id={`home-section-${section.section}`}
            className="mb-2 text-xs font-bold uppercase tracking-wider text-muted-foreground"
          >
            {section.label}
          </h2>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {section.items.map((item) => (
              <SectionCard key={item.id} item={item} projectId={project.id} counts={counts} />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function SectionCard({ item, projectId, counts }: { item: ShownNavItem; projectId: number; counts: HomeCounts }) {
  const navigate = useNavigate()
  const count = item.homeCount?.(counts)
  return (
    <button
      type="button"
      data-home-card={item.id}
      data-off-rig={item.offRig ? '' : undefined}
      title={item.offRig ? OFF_RIG_LABEL : undefined}
      onClick={() => navigate(item.path(projectId))}
      className={cn(
        'flex min-h-16 flex-col items-start gap-1 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent/50',
        item.offRig && 'text-muted-foreground',
      )}
    >
      <span className="flex w-full items-center gap-2 text-sm font-medium">
        <item.icon className={cn('size-4 shrink-0', !item.offRig && 'text-muted-foreground')} />
        <span className="min-w-0 flex-1 truncate">{item.label}</span>
        {item.offRig && <OffRigDot />}
      </span>
      {item.offRig ? (
        <span className="text-xs">{OFF_RIG_LABEL}</span>
      ) : (
        count != null && (
          <span className="text-xs tabular-nums text-muted-foreground" data-home-count="">
            {count}
          </span>
        )
      )}
    </button>
  )
}

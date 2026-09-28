import { useCallback, useEffect, useRef, useState } from "react"
import { Loader2, AlertCircle, AlertTriangle, Info } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { lightingApi } from "@/api/lightingApi"
import {
  useLazyCloudSyncActivityQuery,
  type SyncLogEntry,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"

/**
 * Append `next` onto `prev`, dropping any items already present in `prev` (matched by
 * `.id`). Used by the activity feed to merge WS appends and paginated fetches without
 * duplicates.
 */
function mergeUniqueById<T extends { id: number }>(prev: T[], next: T[]): T[] {
  if (next.length === 0) return prev
  const seen = new Set(prev.map((e) => e.id))
  const added = next.filter((e) => !seen.has(e.id))
  return added.length === 0 ? prev : [...prev, ...added]
}

const ACTIVITY_PAGE_SIZE = 50
// Match the backend per-project cap (`SyncLogger.MAX_ENTRIES_PER_PROJECT`); a long-lived
// tab on a chatty project would otherwise grow unbounded.
const ACTIVITY_MAX_ENTRIES = 500

export function ActivityPanel({ projectId }: { projectId: number }) {
  const [entries, setEntries] = useState<SyncLogEntry[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const oldestIdRef = useRef<number | null>(null)
  const loadingRef = useRef(false)
  const sentinelRef = useRef<HTMLDivElement | null>(null)

  const [fetchActivity, { isFetching }] = useLazyCloudSyncActivityQuery()

  const loadOlder = useCallback(async () => {
    if (loadingRef.current || !hasMore) return
    loadingRef.current = true
    setError(null)
    try {
      const page = await fetchActivity({
        projectId,
        limit: ACTIVITY_PAGE_SIZE,
        beforeId: oldestIdRef.current ?? undefined,
      }).unwrap()
      setEntries((prev) => mergeUniqueById(prev, page).slice(0, ACTIVITY_MAX_ENTRIES))
      if (page.length < ACTIVITY_PAGE_SIZE) setHasMore(false)
      const last = page[page.length - 1]
      if (last) oldestIdRef.current = last.id
    } catch (err) {
      setError(formatError(err))
    } finally {
      loadingRef.current = false
    }
  }, [fetchActivity, hasMore, projectId])

  // First page on mount. The parent remounts us per project via `key=`, so this
  // effectively runs once per project session.
  useEffect(() => {
    void loadOlder()
    // Only fire on mount; `loadOlder` depends on `hasMore` and we don't want a re-fetch
    // when it flips false at the end of pagination.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const sub = lightingApi.cloudSync.subscribeLogAppended((event) => {
      if (event.projectId !== projectId) return
      setEntries((prev) => mergeUniqueById([event.entry], prev).slice(0, ACTIVITY_MAX_ENTRIES))
    })
    return () => sub.unsubscribe()
  }, [projectId])

  useEffect(() => {
    const node = sentinelRef.current
    if (!node) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          void loadOlder()
        }
      },
      { rootMargin: "120px" },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [loadOlder])

  return (
    <Card className="p-4 space-y-3">
      <div>
        <h2 className="text-sm font-semibold">Activity</h2>
        <p className="text-xs text-muted-foreground">
          Sync events for this project (most recent first). Updates live while connected.
        </p>
      </div>
      {entries.length === 0 && isFetching ? (
        <div className="flex justify-center py-6">
          <Loader2 className="size-5 animate-spin" />
        </div>
      ) : entries.length === 0 ? (
        <p className="text-xs text-muted-foreground italic">
          No activity yet — events will appear here as syncs and snapshots run.
        </p>
      ) : (
        <div className="max-h-96 overflow-y-auto border rounded-md divide-y">
          {entries.map((entry) => (
            <ActivityRow key={entry.id} entry={entry} />
          ))}
          <div ref={sentinelRef} className="px-3 py-2 text-center text-xs text-muted-foreground">
            {error ? (
              <span className="text-destructive">Failed to load older activity: {error}</span>
            ) : isFetching ? (
              <span className="inline-flex items-center gap-2">
                <Loader2 className="size-3 animate-spin" />
                Loading…
              </span>
            ) : hasMore ? (
              <button
                type="button"
                onClick={() => void loadOlder()}
                className="hover:text-foreground"
              >
                Load older
              </button>
            ) : (
              <span className="italic">No older entries</span>
            )}
          </div>
        </div>
      )}
    </Card>
  )
}

function ActivityRow({ entry }: { entry: SyncLogEntry }) {
  const meta = activityLevelMeta(entry.level)
  return (
    <div className="flex items-start gap-2 px-3 py-2 text-xs">
      <meta.Icon className={`size-3.5 mt-0.5 shrink-0 ${meta.iconClassName}`} />
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex items-center gap-2 flex-wrap">
          <Badge variant={meta.badgeVariant} className="text-[10px] font-mono">
            {entry.event}
          </Badge>
          <span className="text-muted-foreground">
            {new Date(entry.ts).toLocaleString()}
          </span>
        </div>
        <div className={meta.messageClassName}>{entry.message}</div>
      </div>
    </div>
  )
}

function activityLevelMeta(level: SyncLogEntry["level"]) {
  switch (level) {
    case "ERROR":
      return {
        Icon: AlertCircle,
        iconClassName: "text-destructive",
        badgeVariant: "destructive" as const,
        messageClassName: "text-destructive break-words",
      }
    case "WARN":
      return {
        Icon: AlertTriangle,
        iconClassName: "text-amber-600",
        badgeVariant: "secondary" as const,
        messageClassName: "text-amber-700 dark:text-amber-400 break-words",
      }
    case "INFO":
    default:
      return {
        Icon: Info,
        iconClassName: "text-muted-foreground",
        badgeVariant: "outline" as const,
        messageClassName: "break-words",
      }
  }
}

import { useEffect, useMemo, useState } from "react"
import { Loader2 } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  useCloudSyncLogQuery,
  useLazyCloudSyncLogQuery,
  type CommitInfo,
} from "@/store/cloudSync"
import { formatError } from "@/lib/formatError"

const HISTORY_PAGE_SIZE = 30

export function HistoryPanel({ projectId }: { projectId: number }) {
  const { data: firstPage, isLoading } = useCloudSyncLogQuery({
    projectId,
    limit: HISTORY_PAGE_SIZE,
  })
  const [olderPages, setOlderPages] = useState<CommitInfo[]>([])
  const [hasMore, setHasMore] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [fetchOlder, { isFetching }] = useLazyCloudSyncLogQuery()

  // Reset accumulated older pages only when HEAD actually moves (a new snapshot lands,
  // or a sync rewrites the tip). RTK Query hands us a fresh array reference on every
  // refetch even when the data is byte-identical — keying on the newest sha avoids
  // dropping pagination state for refetches that don't move HEAD.
  const headSha = firstPage?.[0]?.sha ?? null
  useEffect(() => {
    setOlderPages([])
    setHasMore(true)
    setError(null)
  }, [headSha])

  const commits = useMemo<CommitInfo[]>(() => {
    if (!firstPage) return []
    const seen = new Set(firstPage.map((c) => c.sha))
    const merged = [...firstPage]
    for (const c of olderPages) {
      if (!seen.has(c.sha)) {
        seen.add(c.sha)
        merged.push(c)
      }
    }
    return merged
  }, [firstPage, olderPages])

  const loadOlder = async () => {
    const last = commits[commits.length - 1]
    if (!last) return
    setError(null)
    try {
      const page = await fetchOlder({
        projectId,
        limit: HISTORY_PAGE_SIZE,
        before: last.sha,
      }).unwrap()
      setOlderPages((prev) => [...prev, ...page])
      if (page.length < HISTORY_PAGE_SIZE) setHasMore(false)
    } catch (err) {
      setError(formatError(err))
    }
  }

  return (
    <Card className="p-4 space-y-3">
      <div>
        <h2 className="text-sm font-semibold">History</h2>
        <p className="text-xs text-muted-foreground">
          Recent snapshots (most recent first). Walk the same history outside the app
          with <code className="text-xs">git log</code> in the working tree.
        </p>
      </div>
      {isLoading ? (
        <div className="flex justify-center py-6">
          <Loader2 className="size-5 animate-spin" />
        </div>
      ) : commits.length === 0 ? (
        <p className="text-xs text-muted-foreground italic">
          No snapshots yet — click &ldquo;Take snapshot&rdquo; above to create the first one.
        </p>
      ) : (
        <>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-24">Commit</TableHead>
                <TableHead className="hidden sm:table-cell w-44">When</TableHead>
                <TableHead className="hidden md:table-cell w-48">Attribution</TableHead>
                <TableHead>Message</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {commits.map((commit) => (
                <TableRow key={commit.sha}>
                  <TableCell className="font-mono text-xs">{commit.shortSha}</TableCell>
                  <TableCell className="hidden sm:table-cell text-xs text-muted-foreground">
                    {new Date(commit.whenMs).toLocaleString()}
                  </TableCell>
                  <TableCell className="hidden md:table-cell text-xs">
                    <AttributionBadge commit={commit} />
                  </TableCell>
                  <TableCell className="text-xs">
                    {commit.message.split("\n")[0]}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <div className="flex items-center justify-center gap-3 pt-1">
            {error && (
              <span className="text-xs text-destructive">{error}</span>
            )}
            {hasMore ? (
              <Button
                variant="outline"
                size="sm"
                onClick={loadOlder}
                disabled={isFetching}
              >
                {isFetching ? (
                  <>
                    <Loader2 className="size-3 mr-1 animate-spin" /> Loading…
                  </>
                ) : (
                  "Load older"
                )}
              </Button>
            ) : (
              <span className="text-xs text-muted-foreground italic">
                No older snapshots
              </span>
            )}
          </div>
        </>
      )}
    </Card>
  )
}

function AttributionBadge({ commit }: { commit: CommitInfo }) {
  if (!commit.installShortUuid) {
    return <span className="text-muted-foreground">{commit.authorName}</span>
  }
  if (commit.installFriendlyName) {
    return (
      <Badge variant="secondary" className="text-[10px]">
        by {commit.installFriendlyName}
      </Badge>
    )
  }
  return (
    <Badge
      variant="outline"
      className="text-[10px] text-muted-foreground"
      title="No friendly name in installs.json — peer never published its registry entry."
    >
      (unknown @ {commit.installShortUuid})
    </Badge>
  )
}

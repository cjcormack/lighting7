import { useNavigate } from "react-router"
import { ChevronRight } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { TableCell, TableRow } from "@/components/ui/table"
import { type SyncConfig } from "@/store/cloudSync"
import { formatRepoUrl } from "@/lib/formatRepoUrl"

/** One row of the hub's project table — sync state at a glance, click to open its Sync tab. */
export function ProjectSyncRow({
  projectId,
  projectName,
  isActive,
  config,
}: {
  projectId: number
  projectName: string
  isActive: boolean
  /** Slice of the batch `useCloudSyncConfigsQuery()` map; undefined when this project has never had a sync_config row. */
  config: SyncConfig | undefined
}) {
  const navigate = useNavigate()
  const repoLabel = formatRepoUrl(config?.repoUrl ?? null)
  const onOpen = () => navigate(`/projects/${projectId}/settings/sync`)

  return (
    <TableRow className="cursor-pointer hover:bg-accent/50" onClick={onOpen}>
      <TableCell>
        <div className="font-medium text-sm flex items-center gap-2">
          {projectName}
          {isActive && (
            <Badge variant="default" className="text-[10px] px-1.5 py-0">active</Badge>
          )}
        </div>
      </TableCell>
      <TableCell>
        {config?.synced ? (
          <Badge variant="secondary" className="text-[10px]">synced</Badge>
        ) : (
          <Badge variant="outline" className="text-[10px] text-muted-foreground">not synced</Badge>
        )}
      </TableCell>
      <TableCell className="hidden md:table-cell text-xs text-muted-foreground font-mono truncate max-w-[260px]">
        {repoLabel ?? <span className="italic">—</span>}
      </TableCell>
      <TableCell className="hidden sm:table-cell text-xs text-muted-foreground">
        {config?.branch ?? "—"}
      </TableCell>
      <TableCell className="hidden lg:table-cell text-xs text-muted-foreground">
        {config?.lastSyncedAt
          ? new Date(config.lastSyncedAt).toLocaleString()
          : <span className="italic">never</span>}
      </TableCell>
      <TableCell className="text-right">
        <Button variant="ghost" size="sm" onClick={(e) => { e.stopPropagation(); onOpen() }}>
          Open
          <ChevronRight className="size-3.5 ml-1" />
        </Button>
      </TableCell>
    </TableRow>
  )
}

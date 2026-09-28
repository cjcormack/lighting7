import { useMemo, useState } from "react"
import { Card } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Loader2, Plus } from "lucide-react"
import { useStageRegionListQuery } from "@/store/stageRegions"
import { EditStageRegionSheet } from "./EditStageRegionSheet"
import type { StageRegionDto } from "@/api/stageRegionApi"
import { bySortOrder, formatTriple } from "@/lib/utils"

type Editing = StageRegionDto | "new" | null

export function StageRegionsContent({ projectId }: { projectId: number }) {
  const { data: regions, isLoading } = useStageRegionListQuery(projectId)
  const [editing, setEditing] = useState<Editing>(null)

  const sortedRegions = useMemo(
    () => (regions ? [...regions].sort(bySortOrder) : []),
    [regions],
  )

  const editingRegion: StageRegionDto | null =
    editing && editing !== "new" ? editing : null

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold">Stage Regions</h2>
          <p className="text-xs text-muted-foreground">
            Volumes that describe areas of the stage (downstage, vocal riser, balcony…). Used by
            scripts and the 3D view.
          </p>
        </div>
        <Button onClick={() => setEditing("new")} size="sm" className="gap-1.5 shrink-0">
          <Plus className="size-4" />
          <span className="hidden sm:inline">Add region</span>
        </Button>
      </div>

      {isLoading ? (
        <div className="flex justify-center py-6">
          <Loader2 className="size-5 animate-spin" />
        </div>
      ) : sortedRegions.length === 0 ? (
        <p className="text-sm text-muted-foreground py-4">
          No stage regions yet. Click &ldquo;Add region&rdquo; to define one.
        </p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead className="hidden sm:table-cell">Centre (m)</TableHead>
              <TableHead className="hidden md:table-cell">Size (w×d×h m)</TableHead>
              <TableHead className="hidden lg:table-cell">Yaw</TableHead>
              <TableHead className="w-[3rem] text-right">Sort</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sortedRegions.map((r) => (
              <TableRow
                key={r.id}
                className="cursor-pointer hover:bg-accent/50"
                onClick={() => setEditing(r)}
              >
                <TableCell className="font-medium">{r.name}</TableCell>
                <TableCell className="hidden sm:table-cell font-mono tabular-nums text-xs text-muted-foreground">
                  {formatTriple(r.centerX, r.centerY, r.centerZ)}
                </TableCell>
                <TableCell className="hidden md:table-cell font-mono tabular-nums text-xs text-muted-foreground">
                  {formatTriple(r.widthM, r.depthM, r.heightM, "×")}
                </TableCell>
                <TableCell className="hidden lg:table-cell font-mono tabular-nums text-xs text-muted-foreground">
                  {r.yawDeg != null ? `${r.yawDeg.toFixed(0)}°` : "—"}
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums text-xs text-muted-foreground">
                  {r.sortOrder}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <EditStageRegionSheet
        open={editing != null}
        onOpenChange={(open) => {
          if (!open) setEditing(null)
        }}
        region={editingRegion}
        projectId={projectId}
      />
    </Card>
  )
}

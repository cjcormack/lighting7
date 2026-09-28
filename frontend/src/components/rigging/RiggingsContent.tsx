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
import { useRiggingListQuery } from "@/store/riggings"
import { EditRiggingSheet } from "./EditRiggingSheet"
import type { RiggingDto } from "@/api/riggingApi"
import { bySortOrder, formatTriple, formatRotation } from "@/lib/utils"

type Editing = RiggingDto | "new" | null

export function RiggingsContent({ projectId }: { projectId: number }) {
  const { data: riggings, isLoading } = useRiggingListQuery(projectId)
  const [editing, setEditing] = useState<Editing>(null)

  const sortedRiggings = useMemo(
    () => (riggings ? [...riggings].sort(bySortOrder) : []),
    [riggings],
  )

  const editingRigging: RiggingDto | null =
    editing && editing !== "new" ? editing : null

  return (
    <Card className="p-4 space-y-4">
      <div className="flex items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold">Riggings</h2>
          <p className="text-xs text-muted-foreground">
            Trusses, bars, booms, pipes and stands that fixtures hang from. Used by patches to anchor 3D placement.
          </p>
        </div>
        <Button onClick={() => setEditing("new")} size="sm" className="gap-1.5 shrink-0">
          <Plus className="size-4" />
          <span className="hidden sm:inline">Add rigging</span>
        </Button>
      </div>

      {isLoading ? (
        <div className="flex justify-center py-6">
          <Loader2 className="size-5 animate-spin" />
        </div>
      ) : sortedRiggings.length === 0 ? (
        <p className="text-sm text-muted-foreground py-4">
          No riggings yet. Click &ldquo;Add rigging&rdquo; to define one.
        </p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead className="hidden sm:table-cell">Kind</TableHead>
              <TableHead className="hidden md:table-cell">Position (m)</TableHead>
              <TableHead className="hidden lg:table-cell">Rotation (Y/P/R)</TableHead>
              <TableHead className="w-[3rem] text-right">Sort</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sortedRiggings.map((r) => (
              <TableRow
                key={r.id}
                className="cursor-pointer hover:bg-accent/50"
                onClick={() => setEditing(r)}
              >
                <TableCell className="font-medium">{r.name}</TableCell>
                <TableCell className="hidden sm:table-cell font-mono tabular-nums text-xs text-muted-foreground">
                  {r.kind ?? "—"}
                </TableCell>
                <TableCell className="hidden md:table-cell font-mono tabular-nums text-xs text-muted-foreground">
                  {formatTriple(r.positionX, r.positionY, r.positionZ)}
                </TableCell>
                <TableCell className="hidden lg:table-cell font-mono tabular-nums text-xs text-muted-foreground">
                  {formatRotation(r.yawDeg, r.pitchDeg, r.rollDeg)}
                </TableCell>
                <TableCell className="text-right font-mono tabular-nums text-xs text-muted-foreground">
                  {r.sortOrder}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <EditRiggingSheet
        open={editing != null}
        onOpenChange={(open) => {
          if (!open) setEditing(null)
        }}
        rigging={editingRigging}
        projectId={projectId}
      />
    </Card>
  )
}

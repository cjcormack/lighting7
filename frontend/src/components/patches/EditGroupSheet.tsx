import { useState, useEffect } from 'react'
import {
  Sheet,
  SheetContent,
  SheetBody,
  SheetHeader,
  SheetTitle,
  SheetFooter,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { GripVertical, Trash2, Plus, X } from 'lucide-react'
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
  sortableKeyboardCoordinates,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import {
  usePatchGroupDetailQuery,
  useUpdatePatchGroupMutation,
  useDeletePatchGroupMutation,
  useUpdatePatchMutation,
} from '@/store/patches'
import type { FixturePatch, PatchGroupMember, RigOwner } from '@/api/patchApi'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { VENUE_ADMIN_ORDER_REASON } from '@/components/groups/useGroupMemberReorder'

interface EditGroupSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  groupId: number | null
  groupName: string
  projectId: number
  patches: FixturePatch[]
  /** A venue's rig opened by an operator: the sheet shows the group and changes nothing. */
  readOnly?: boolean
  /** Whose the group is, from the group list, so a venue's group opens locked before its detail loads. */
  owner?: RigOwner
}

/** Why a venue's group cannot be changed from a show (its PUT and DELETE would be 409 `VENUE_OWNED`). */
const VENUE_GROUP_REASON = 'The venue’s group: change it, its members and its order in the venue.'

export function EditGroupSheet({ open, onOpenChange, groupId, groupName: initialName, projectId, patches, readOnly = false, owner }: EditGroupSheetProps) {
  const { data: groupDetail } = usePatchGroupDetailQuery(
    { projectId, groupId: groupId ?? 0 },
    { skip: !groupId },
  )
  const [updateGroup, { isLoading: isUpdating }] = useUpdatePatchGroupMutation()
  const [deleteGroup, { isLoading: isDeleting }] = useDeletePatchGroupMutation()
  const [updatePatch] = useUpdatePatchMutation()

  const [name, setName] = useState(initialName)
  const [members, setMembers] = useState<PatchGroupMember[]>([])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  useEffect(() => {
    if (groupDetail) {
      setName(groupDetail.name)
      setMembers([...groupDetail.members].sort((a, b) => a.sortOrder - b.sortOrder))
    }
  }, [groupDetail])

  useEffect(() => {
    setName(initialName)
  }, [initialName])

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return

    setMembers((prev) => {
      const oldIndex = prev.findIndex((m) => m.patchId === active.id)
      const newIndex = prev.findIndex((m) => m.patchId === over.id)
      return arrayMove(prev, oldIndex, newIndex)
    })
  }

  if (!groupId) return null

  // Read-only, with the reason (venue-visit plan S7): a venue's group seen from a show, and a venue's
  // rig for an operator. The group and its order are still shown. The owner comes from the list the
  // sheet was opened from, so nothing is editable for a moment before the detail says whose it is;
  // with neither, the sheet stays locked until the detail loads.
  const groupOwner = groupDetail?.owner ?? owner
  const lockedReason = groupOwner === 'VENUE'
    ? VENUE_GROUP_REASON
    : readOnly
      ? VENUE_ADMIN_ORDER_REASON
      : null
  const locked = lockedReason != null || (groupDetail == null && owner == null)

  const originalOrder = groupDetail?.members.map(m => m.patchId) ?? []
  const currentOrder = members.map(m => m.patchId)
  const orderChanged = JSON.stringify(originalOrder) !== JSON.stringify(currentOrder)
  const nameChanged = name !== (groupDetail?.name ?? initialName)
  const hasChanges = orderChanged || nameChanged
  const isValid = name.trim().length > 0

  const handleSave = async () => {
    const body: Record<string, unknown> = {}
    if (nameChanged) body.name = name.trim()
    if (orderChanged) body.memberOrder = currentOrder
    // Reported by errorToastMiddleware; keep the sheet open on a save that didn't land, or the
    // operator loses their edits with the toast as the only trace.
    try {
      await updateGroup({ projectId, groupId, ...body }).unwrap()
    } catch {
      return
    }
    onOpenChange(false)
  }

  const handleDelete = async () => {
    try {
      await deleteGroup({ projectId, groupId }).unwrap()
    } catch {
      return
    }
    onOpenChange(false)
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{locked ? 'Group' : 'Edit Group'}</SheetTitle>
        </SheetHeader>

        <SheetBody>
          <div className="space-y-1.5">
            <Label htmlFor="group-name">Group Name</Label>
            <Input
              id="group-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoFocus={!locked}
              disabled={locked}
            />
          </div>

          <Separator />

          <div className="space-y-1.5">
            <Label>Members ({members.length})</Label>
            <p className="text-xs text-muted-foreground">
              {lockedReason ?? 'Effects and a spread over the group run in this order. Drag to reorder.'}
            </p>
          </div>

          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <SortableContext
              items={members.map((m) => m.patchId)}
              strategy={verticalListSortingStrategy}
              disabled={locked}
            >
              <div className="space-y-1">
                {members.map((member, index) => (
                  <SortableMemberRow
                    key={member.patchId}
                    member={member}
                    position={index + 1}
                    locked={locked}
                    onRemove={groupId && !locked ? async () => {
                      await updatePatch({ projectId, patchId: member.patchId, removeFromGroupId: groupId })
                        .unwrap()
                        .catch(ignoreReportedError)
                    } : undefined}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>

          {members.length === 0 && (
            <p className="text-sm text-muted-foreground text-center py-4">
              No members yet.
            </p>
          )}

          {!locked && <AddFixturesToGroup
            members={members}
            patches={patches}
            onAdd={async (patchId) => {
              await updatePatch({ projectId, patchId, addToGroup: groupDetail?.name ?? initialName })
                .unwrap()
                .catch(ignoreReportedError)
            }}
          />}

        </SheetBody>

        <SheetFooter className="flex-row justify-between">
          {locked ? <span /> : <Button
            variant="destructive"
            size="sm"
            onClick={handleDelete}
            disabled={isDeleting}
          >
            <Trash2 className="size-3.5 mr-1.5" />
            {isDeleting ? 'Deleting...' : 'Delete'}
          </Button>}
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isUpdating}>
              {locked ? 'Close' : 'Cancel'}
            </Button>
            {!locked && (
              <Button onClick={handleSave} disabled={!isValid || !hasChanges || isUpdating}>
                {isUpdating ? 'Saving...' : 'Save'}
              </Button>
            )}
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function AddFixturesToGroup({
  members,
  patches,
  onAdd,
}: {
  members: PatchGroupMember[]
  patches: FixturePatch[]
  onAdd: (patchId: number) => Promise<void>
}) {
  const [search, setSearch] = useState('')
  const memberPatchIds = new Set(members.map(m => m.patchId))

  const available = patches.filter(p => {
    if (memberPatchIds.has(p.id)) return false
    if (!search) return true
    const q = search.toLowerCase()
    return p.displayName.toLowerCase().includes(q) || p.key.toLowerCase().includes(q)
  })

  return (
    <div className="space-y-1.5">
      <Label>Add Fixtures</Label>
      <Input
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search fixtures..."
        className="h-8 text-xs"
      />
      {available.length > 0 ? (
        <div className="max-h-40 overflow-y-auto space-y-0.5">
          {available.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => onAdd(p.id)}
              className="flex items-center gap-2 w-full px-2 py-1.5 rounded text-left text-xs hover:bg-accent transition-colors"
            >
              <Plus className="size-3 text-muted-foreground shrink-0" />
              <span className="font-medium truncate">{p.displayName}</span>
              <span className="text-muted-foreground truncate">{p.key}</span>
            </button>
          ))}
        </div>
      ) : (
        <p className="text-xs text-muted-foreground text-center py-2">
          {search ? 'No matching fixtures.' : 'All fixtures are already in this group.'}
        </p>
      )}
    </div>
  )
}

function SortableMemberRow({
  member,
  position,
  locked,
  onRemove,
}: {
  member: PatchGroupMember
  /** Its place in the group's order, 1-based: the order the group's effects and a spread run in. */
  position: number
  locked: boolean
  onRemove?: () => void
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: member.patchId, disabled: locked })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className="flex items-center gap-2 p-2 rounded-md border bg-card"
    >
      {!locked && (
        <button
          type="button"
          className="touch-none cursor-grab active:cursor-grabbing text-muted-foreground/40 hover:text-muted-foreground"
          aria-label={`Move ${member.fixtureName}`}
          {...attributes}
          {...listeners}
        >
          <GripVertical className="size-4" />
        </button>
      )}
      <span className="w-5 shrink-0 text-right text-xs tabular-nums text-muted-foreground">{position}</span>
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium truncate">{member.fixtureName}</div>
        <div className="text-[11px] text-muted-foreground truncate">{member.fixtureKey}</div>
      </div>
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          className="text-muted-foreground/40 hover:text-destructive shrink-0"
        >
          <X className="size-3.5" />
        </button>
      )}
    </div>
  )
}

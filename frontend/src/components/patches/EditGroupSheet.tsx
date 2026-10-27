import { useMemo, useState } from 'react'
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
import type { ColourOwner, FixturePatch, PatchGroupMember, RigOwner } from '@/api/patchApi'
import { ColourSwatchGrid } from '@/components/fixtureColours/ColourSwatches'
import { readColour, type FixtureColour } from '@/lib/fixtureColours'
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

/**
 * A venue's group in a show: its name and members are the venue's (a PUT naming them, and the DELETE,
 * would be 409 `VENUE_OWNED`), its order the show's to set (group-order plan D5).
 */
const VENUE_GROUP_REASON = 'The venue’s group: its name and members are set in the venue. Drag to reorder it for this show.'

export function EditGroupSheet({ open, onOpenChange, groupId, groupName: initialName, projectId, patches, readOnly = false, owner }: EditGroupSheetProps) {
  const { data: groupDetail } = usePatchGroupDetailQuery(
    { projectId, groupId: groupId ?? 0 },
    { skip: !groupId },
  )
  const [updateGroup, { isLoading: isUpdating }] = useUpdatePatchGroupMutation()
  const [deleteGroup, { isLoading: isDeleting }] = useDeletePatchGroupMutation()
  const [updatePatch] = useUpdatePatchMutation()

  // The drafts are overlays on the stored group, so a refetch (a recolour, a member renamed) shows
  // through them and never throws an unsaved rename or reorder away; each opening starts clean.
  const [nameDraft, setNameDraft] = useState<string | null>(null)
  const [orderDraft, setOrderDraft] = useState<number[] | null>(null)
  const [opened, setOpened] = useState({ open, groupId })
  if (opened.open !== open || opened.groupId !== groupId) {
    setOpened({ open, groupId })
    setNameDraft(null)
    setOrderDraft(null)
  }
  const name = nameDraft ?? groupDetail?.name ?? initialName
  const stored = useMemo(
    () => [...(groupDetail?.members ?? [])].sort((a, b) => a.sortOrder - b.sortOrder),
    [groupDetail],
  )
  const members = useMemo(() => withOrder(stored, orderDraft), [stored, orderDraft])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const ids = members.map((m) => m.patchId)
    setOrderDraft(arrayMove(ids, ids.indexOf(Number(active.id)), ids.indexOf(Number(over.id))))
  }

  if (!groupId) return null

  // Locked, with the reason (venue-visit plan S7): a venue's group seen from a show, whose order alone
  // is the show's to change (group-order plan D14), and a venue's rig for an operator, where nothing
  // is. The owner comes from the list the sheet was opened from, so nothing is editable for a moment
  // before the detail says whose it is; with neither, the sheet stays locked until the detail loads.
  const groupOwner = groupDetail?.owner ?? owner
  const venueGroup = groupOwner === 'VENUE'
  const lockedReason = readOnly
    ? VENUE_ADMIN_ORDER_REASON
    : venueGroup
      ? VENUE_GROUP_REASON
      : null
  const unknownOwner = groupDetail == null && owner == null
  const locked = lockedReason != null || unknownOwner
  const orderLocked = readOnly || unknownOwner
  const orderYours = venueGroup && groupDetail?.orderOwner === 'SHOW'

  const originalOrder = stored.map((m) => m.patchId)
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

  // A colour is written as it is picked, alone, so a venue's group takes the show's recolour where
  // the rest of it is locked (tables-colours plan D10). Only a venue looked at by an operator refuses.
  const colourEditable = !readOnly && groupOwner != null
  // The colour it already draws is no pick: on a venue's group it would pin the venue's colour as
  // the show's own.
  const setColour = (colour: FixtureColour | null) => {
    if (colour === readColour(groupDetail?.colour)) return
    void updateGroup({ projectId, groupId, colour }).unwrap().catch(ignoreReportedError)
  }

  const takeVenueOrder = () => {
    setOrderDraft(null)
    void updateGroup({ projectId, groupId, memberOrder: null }).unwrap().catch(ignoreReportedError)
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
              onChange={(e) => setNameDraft(e.target.value)}
              autoFocus={!locked}
              disabled={locked}
            />
          </div>

          <GroupColourField
            colour={readColour(groupDetail?.colour)}
            owner={groupDetail?.colourOwner ?? null}
            venueGroup={groupOwner === 'VENUE'}
            disabled={!colourEditable}
            onPick={setColour}
          />

          <Separator />

          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <Label>Members ({members.length})</Label>
              {orderYours && <span className="text-[10px] text-primary">Your order</span>}
              <span className="flex-1" />
              {orderYours && !orderLocked && (
                <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" aria-label="Take venue’s order" onClick={takeVenueOrder}>
                  Take venue’s
                </Button>
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              {lockedReason ?? 'FX and a fan over the group run in this order. Drag to reorder.'}
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
              disabled={orderLocked}
            >
              <div className="space-y-1">
                {members.map((member, index) => (
                  <SortableMemberRow
                    key={member.patchId}
                    member={member}
                    position={index + 1}
                    locked={orderLocked}
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
              {orderLocked ? 'Close' : 'Cancel'}
            </Button>
            {!orderLocked && (
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

/**
 * The group's colour (tables-colours plan D7, D9): it marks the group's row and tile and never its
 * members — *Colour from group* on the patch list copies it onto them. On a venue's group in a show
 * a pick is the show's recolour, drawn *yours*, and *Take venue's* takes the venue's back (D10).
 */
function GroupColourField({
  colour,
  owner,
  venueGroup,
  disabled,
  onPick,
}: {
  colour: FixtureColour | null
  owner: ColourOwner | null
  venueGroup: boolean
  disabled: boolean
  onPick: (colour: FixtureColour | null) => void
}) {
  const yours = owner === 'SHOW'
  const clear = !venueGroup ? (colour != null ? 'None' : null) : yours ? 'Take venue’s' : null
  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <Label>Colour</Label>
        {yours && <span className="text-[10px] text-primary">yours</span>}
        <span className="flex-1" />
        {clear != null && !disabled && (
          <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onClick={() => onPick(null)}>
            {clear}
          </Button>
        )}
      </div>
      <ColourSwatchGrid value={colour} onPick={onPick} disabled={disabled} />
      <p className="text-xs text-muted-foreground">
        Marks the group, never its members — <span className="font-medium">Colour from group</span> on the
        patch list copies it onto them.
      </p>
    </div>
  )
}

/** [stored] members in [order], a member the draft does not name (just added) at the end. */
function withOrder(stored: PatchGroupMember[], order: readonly number[] | null): PatchGroupMember[] {
  if (order == null) return stored
  const byId = new Map(stored.map((m) => [m.patchId, m]))
  const placed = order.flatMap((id) => byId.get(id) ?? [])
  return [...placed, ...stored.filter((m) => !order.includes(m.patchId))]
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

import { useMemo, useState } from 'react'
import { GripVertical, Loader2 } from 'lucide-react'
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import { SortableContext, arrayMove, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { CompactFixtureCard, MultiElementCompactCard } from './CompactFixtureCard'
import { useFixtureListQuery, type Fixture } from '../../store/fixtures'
import type { SpreadOrder, GroupDetail, GroupMember } from '../../api/groupsApi'
import { ORDER_OPTION_ENTRIES } from '../fx/fxConstants'
import { useVisibleGroupMembers } from './useVisibleGroupMembers'
import { useGroupMemberReorder } from './useGroupMemberReorder'

interface GroupMembersSectionProps {
  members: GroupMember[] | undefined
  isLoading: boolean
  onFixtureClick: (fixtureKey: string) => void
  /**
   * `wrap` (a group card): the members' cards, in the group's order. `list` (the group sheet's
   * *Members* view, venue-visit plan S7): the members numbered in the group's order — the order its
   * effects and a spread over it run in — dragged to reorder where the group is this show's to
   * order, with each member's step under a chosen strategy. Needs `groupName`.
   */
  layout?: 'wrap' | 'list'
  groupName?: string
  /** The group's `orderSteps` and `defaultOrder`, for the list's steps. */
  orderSteps?: GroupDetail['orderSteps']
  defaultOrder?: string
}

export function GroupMembersSection({
  members: allMembers,
  isLoading,
  onFixtureClick,
  layout = 'wrap',
  groupName,
  orderSteps,
  defaultOrder,
}: GroupMembersSectionProps) {
  const { data: fixtureList } = useFixtureListQuery()
  // An infrastructure head stays in the group (the group still drives it) but is not shown.
  const members = useVisibleGroupMembers(allMembers)

  // Build a key→fixture lookup (single subscription shared across all member cards)
  const fixtureMap = useMemo(() => {
    const map = new Map<string, Fixture>()
    fixtureList?.forEach((f) => map.set(f.key, f))
    return map
  }, [fixtureList])

  // Group elements by their parent fixture
  const { regularFixtures, multiElementFixtures } = useMemo(() => {
    if (!members) {
      return { regularFixtures: [], multiElementFixtures: new Map<string, GroupMember[]>() }
    }

    const regular: GroupMember[] = []
    const multiElement = new Map<string, GroupMember[]>()

    for (const member of members) {
      const isElement = member.tags.includes('element')

      if (isElement) {
        // Extract parent fixture key (before ".element-N")
        const dotIndex = member.fixtureKey.lastIndexOf('.element-')
        const parentKey = dotIndex > 0 ? member.fixtureKey.substring(0, dotIndex) : member.fixtureKey
        const existing = multiElement.get(parentKey) ?? []
        multiElement.set(parentKey, [...existing, member])
      } else {
        regular.push(member)
      }
    }

    return {
      regularFixtures: regular,
      multiElementFixtures: multiElement,
    }
  }, [members])

  if (layout === 'list' && groupName != null && !isLoading && members && members.length > 0) {
    return (
      <OrderedMembers
        groupName={groupName}
        members={members}
        fixtureMap={fixtureMap}
        orderSteps={orderSteps}
        defaultOrder={defaultOrder}
        onFixtureClick={onFixtureClick}
      />
    )
  }

  if (isLoading) {
    return (
      <div className="pt-3 border-t">
        <div className="flex justify-center py-4">
          <Loader2 className="size-4 animate-spin" />
        </div>
      </div>
    )
  }

  if (!members || members.length === 0) {
    return null
  }

  const hasContent = regularFixtures.length > 0 || multiElementFixtures.size > 0

  if (!hasContent) {
    return null
  }

  return (
    <div className="pt-3 border-t">
      <h4 className="text-sm font-medium text-muted-foreground mb-2">Members</h4>
      <div className="flex flex-wrap gap-2">
        {/* Regular fixtures */}
        {regularFixtures.map((member) => (
          <CompactFixtureCard
            key={member.fixtureKey}
            fixtureKey={member.fixtureKey}
            fixtureName={member.fixtureName}
            tags={member.tags}
            fixture={fixtureMap.get(member.fixtureKey)}
            onClick={() => onFixtureClick(member.fixtureKey)}
          />
        ))}

        {/* Multi-element fixtures (show parent with element count) */}
        {Array.from(multiElementFixtures.entries()).map(([parentKey, elements]) => (
          <MultiElementCompactCard
            key={parentKey}
            parentKey={parentKey}
            elementCount={elements.length}
            fixture={fixtureMap.get(parentKey)}
            onClick={() => onFixtureClick(parentKey)}
          />
        ))}
      </div>
    </div>
  )
}

const ORDER_VALUES = new Set<string>(ORDER_OPTION_ENTRIES.map((o) => o.value))

/**
 * The group sheet's member list (venue-visit plan S7): every shown member in the group's order,
 * numbered by its place in the whole group (an infrastructure head is still counted, so a number
 * can skip), with its **step** under the strategy *Runs as* names — the desk's
 * `orderSteps`, so shared steps (a `CENTRE_OUT` pair) read alike. A drag writes the new
 * order at once, optimistically, rolled back if the desk refuses it; a venue's group in a show,
 * and a venue's rig for an operator, are read-only and say why.
 *
 * It nests its own `DndContext`, as Patches' Edit Group sheet does: the drag reorders a list within
 * itself and never ends on one of the shell's droppables (frontend CLAUDE.md §"Busk drag and drop").
 */
function OrderedMembers({
  groupName,
  members,
  fixtureMap,
  orderSteps,
  defaultOrder,
  onFixtureClick,
}: {
  groupName: string
  members: GroupMember[]
  fixtureMap: Map<string, Fixture>
  orderSteps?: GroupDetail['orderSteps']
  defaultOrder?: string
  onFixtureClick: (fixtureKey: string) => void
}) {
  const reorder = useGroupMemberReorder(groupName)
  const [order, setOrder] = useState<SpreadOrder>(() =>
    defaultOrder != null && ORDER_VALUES.has(defaultOrder) ? (defaultOrder as SpreadOrder) : 'NORMAL',
  )
  const steps = orderSteps?.[order]
  const orderName = ORDER_OPTION_ENTRIES.find((o) => o.value === order)?.label ?? order
  const editable = reorder.kind === 'editable'

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const keys = members.map((m) => m.fixtureKey)
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (reorder.kind !== 'editable' || over == null || active.id === over.id) return
    const from = keys.indexOf(String(active.id))
    const to = keys.indexOf(String(over.id))
    if (from < 0 || to < 0) return
    reorder.write(arrayMove(keys, from, to))
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <h4 className="text-sm font-medium text-muted-foreground">Order</h4>
        {orderSteps != null && (
          <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
            Runs as
            <Select value={order} onValueChange={(v) => setOrder(v as SpreadOrder)}>
              <SelectTrigger className="h-7 w-32 text-xs" aria-label="Runs as">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ORDER_OPTION_ENTRIES.map((o) => (
                  <SelectItem key={o.value} value={o.value} className="text-xs">
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        {reorder.kind === 'readOnly'
          ? reorder.reason
          : `FX and a fan over ${groupName} run in this order${editable ? '. Drag to reorder.' : '.'}`}
        {steps != null && ` Step is when each member comes round under ${orderName}.`}
      </p>
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        <SortableContext items={keys} strategy={verticalListSortingStrategy} disabled={!editable}>
          <ol className="space-y-1" aria-label={`${groupName} members in order`}>
            {members.map((member) => (
              <OrderedMemberRow
                key={member.fixtureKey}
                member={member}
                fixture={fixtureMap.get(member.fixtureKey)}
                step={steps?.[member.index]}
                draggable={editable}
                onClick={() => onFixtureClick(member.fixtureKey)}
              />
            ))}
          </ol>
        </SortableContext>
      </DndContext>
    </div>
  )
}

function OrderedMemberRow({
  member,
  fixture,
  step,
  draggable,
  onClick,
}: {
  member: GroupMember
  fixture: Fixture | undefined
  step: number | undefined
  draggable: boolean
  onClick: () => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: member.fixtureKey,
    disabled: !draggable,
  })
  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={cn('flex items-center gap-2', isDragging && 'relative z-10 opacity-60')}
      data-member-key={member.fixtureKey}
    >
      {draggable && (
        <button
          type="button"
          className="touch-none cursor-grab text-muted-foreground/50 hover:text-muted-foreground active:cursor-grabbing"
          aria-label={`Move ${member.fixtureName}`}
          {...attributes}
          {...listeners}
        >
          <GripVertical className="size-4" />
        </button>
      )}
      <span className="w-5 shrink-0 text-right text-xs tabular-nums text-muted-foreground" title="Place in the group’s order">
        {member.index + 1}
      </span>
      {/* The card fills the row, so the step sits beside it rather than across a gap (its own
          300px cap is for the card grid). */}
      <div className="flex min-w-0 flex-1 [&>*]:!max-w-none">
        <CompactFixtureCard
          fixtureKey={member.fixtureKey}
          fixtureName={member.fixtureName}
          tags={member.tags}
          fixture={fixture}
          onClick={onClick}
        />
      </div>
      {step !== undefined && (
        <span className="w-12 shrink-0 text-right text-xs tabular-nums text-muted-foreground" title="Step">
          step {step}
        </span>
      )}
    </li>
  )
}

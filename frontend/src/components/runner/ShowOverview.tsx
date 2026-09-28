import { useState, useRef, useEffect, useCallback, useMemo } from 'react'
import {
  ArrowRight,
  GripVertical,
  RotateCcw,
  X,
  Plus,
  SeparatorHorizontal,
  MoreVertical,
  Pencil,
  ArrowDownAZ,
  Trash2,
} from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { MarkerRow } from '@/components/runner/MarkerRow'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
  verticalListSortingStrategy,
  arrayMove,
  useSortable,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import {
  useCreateProjectCueStackMutation,
  useSaveProjectCueStackMutation,
  useDeleteProjectCueStackMutation,
  useReorderCueStacksMutation,
  useSortCueStackByCueNumberMutation,
} from '@/store/cueStacks'
import { CueStackForm } from '@/components/cues/CueStackForm'
import { InlineEditField } from '@/components/InlineEditField'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import type { CueStack, CueStackInput } from '@/api/cueStacksApi'

// ── Sortable STACK entry row ────────────────────────────────────────────────

interface SortableStackEntryProps {
  stack: CueStack
  index: number
  isActive: boolean
  onDrill: (stackId: number) => void
  onEdit: (stack: CueStack) => void
  onRename: (stack: CueStack, name: string) => void
  onSort: (stackId: number) => void
  onRemove: (stack: CueStack) => void
  locked: boolean
}

function SortableStackEntry({
  stack,
  index,
  isActive,
  onDrill,
  onEdit,
  onRename,
  onSort,
  onRemove,
  locked,
}: SortableStackEntryProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: stack.id,
    disabled: locked,
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 50 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  const cueCount = stack.cues.filter((c) => c.cueType === 'STANDARD').length

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      className={cn(
        'flex items-center w-full gap-3 px-4 py-2.5 bg-muted border rounded border-l-[3px] border-l-transparent transition-colors hover:bg-muted/70 hover:border-muted-foreground/20 text-left cursor-pointer',
        isActive && 'border-l-green-500 bg-green-500/[0.08]',
      )}
      onClick={() => onDrill(stack.id)}
    >
      {locked ? (
        // Keeps the column, drops the affordance — the rows must not shift when the lock changes.
        <div className="size-4" />
      ) : (
        <div
          {...listeners}
          className="flex items-center justify-center text-muted-foreground hover:text-foreground cursor-grab"
          onClick={(e) => e.stopPropagation()}
        >
          <GripVertical className="size-4" />
        </div>
      )}
      <span className="hidden @[560px]:block w-6 text-center font-mono text-xs text-muted-foreground shrink-0">
        {index + 1}
      </span>
      {/* The field wraps its text rather than filling the row, so clicking the space beside a
          short stack name drills into the stack instead of opening the rename editor. */}
      <span className="flex flex-1 min-w-0">
        <InlineEditField
          value={stack.name}
          onCommit={(next) => {
            const trimmed = next.trim()
            if (trimmed === '') return false
            onRename(stack, trimmed)
          }}
          ariaLabel="stack name"
          disabled={locked || !stack.canEdit}
          title={!locked && stack.canEdit ? 'Click to rename' : undefined}
          className={cn(
            'min-w-0 max-w-full truncate text-sm font-medium text-foreground',
            isActive && 'text-green-300 font-semibold',
          )}
        />
      </span>
      {isActive && (
        <Badge
          variant="outline"
          className="text-xs px-1.5 py-0 gap-1 border-green-500/40 text-green-400 bg-green-500/10 shrink-0"
        >
          <span className="size-1.5 rounded-full bg-green-400 animate-pulse" />
          Live
        </Badge>
      )}
      <span className="hidden @[560px]:block text-xs text-muted-foreground shrink-0">
        {cueCount} cues &middot; {stack.loop ? 'Loop' : 'Sequential'}
      </span>
      {stack.loop && (
        <Badge variant="outline" className="hidden @[560px]:inline-flex text-xs px-1.5 py-0 gap-1">
          <RotateCcw className="size-2.5" />
          Loop
        </Badge>
      )}
      {!locked && (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="size-6 text-muted-foreground shrink-0"
            aria-label="Stack actions"
            onClick={(e) => e.stopPropagation()}
          >
            <MoreVertical className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
          <DropdownMenuItem onClick={() => onEdit(stack)}>
            <Pencil className="size-3.5 mr-2" /> Stack settings
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => onSort(stack.id)}>
            <ArrowDownAZ className="size-3.5 mr-2" /> Sort by cue number
          </DropdownMenuItem>
          <DropdownMenuItem
            className="text-destructive focus:text-destructive"
            onClick={() => onRemove(stack)}
          >
            <Trash2 className="size-3.5 mr-2" /> Delete stack
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      )}
      <ArrowRight className="size-4 text-muted-foreground shrink-0" />
    </div>
  )
}

// ── Sortable SEPARATOR entry row ────────────────────────────────────────────

interface SortableSeparatorEntryProps {
  stack: CueStack
  projectId: number
  onRemove: (stack: CueStack) => void
  locked: boolean
}

function SortableSeparatorEntry({
  stack,
  projectId,
  onRemove,
  locked,
}: SortableSeparatorEntryProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: stack.id,
    disabled: locked,
  })
  const [saveStack] = useSaveProjectCueStackMutation()

  const [localLabel, setLocalLabel] = useState(stack.label ?? stack.name)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    setLocalLabel(stack.label ?? stack.name)
  }, [stack.label, stack.name])

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])

  const handleChange = (value: string) => {
    setLocalLabel(value)
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      if (value.trim()) {
        saveStack({
          projectId,
          stackId: stack.id,
          name: value.trim(),
          loop: false,
          type: 'SEPARATOR',
          label: value.trim(),
        })
      }
    }, 400)
  }

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 50 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  // Locked, a separator is just a labelled divider — the same one every other surface draws, so
  // render `MarkerRow` rather than a third spelling of it. The wrapper keeps the unlocked row's
  // padding and grip-column spacer and suppresses `MarkerRow`'s own, so nothing shifts sideways as
  // the lock flips. Leaving the field and the delete live made this the one row a stray click could
  // still rename or remove from a running show.
  if (locked) {
    return (
      <div
        ref={setNodeRef}
        style={style}
        {...attributes}
        className="flex items-center gap-2.5 py-1.5 px-4"
      >
        <div className="size-4 shrink-0" />
        <MarkerRow name={localLabel} className="min-w-0 flex-1 px-0 py-0" />
      </div>
    )
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      className="flex items-center gap-2.5 py-1.5 px-4"
    >
      <div
        {...listeners}
        className="flex items-center justify-center text-muted-foreground hover:text-foreground cursor-grab"
      >
        <GripVertical className="size-4" />
      </div>
      <div className="flex-1 h-px bg-border" />
      <Input
        value={localLabel}
        onChange={(e) => handleChange(e.target.value)}
        onClick={(e) => e.stopPropagation()}
        className="h-7 w-auto min-w-[120px] max-w-[200px] text-center text-xs font-medium text-muted-foreground bg-card border-border"
      />
      <div className="flex-1 h-px bg-border" />
      <Button
        variant="ghost"
        size="icon"
        className="size-5 text-muted-foreground hover:text-destructive shrink-0"
        onClick={() => onRemove(stack)}
      >
        <X className="size-3.5" />
      </Button>
    </div>
  )
}

// ── Main ShowOverview ────────────────────────────────────────────────────────

interface ShowOverviewProps {
  projectId: number
  stacks: CueStack[]
  activeStackId: number | null
  onDrillStack: (stackId: number) => void
  /**
   * Show-safe mode: no reordering, no creating, no renaming, no deleting.
   *
   * Dragging is switched off through dnd-kit's own `disabled` on each sortable rather than by
   * unmounting the `DndContext` — `useSortable` needs that ancestor, so removing it to disable
   * dragging breaks every row. The affordances are hidden rather than disabled: a list of
   * greyed-out buttons reads as breakage, absence reads as "not now".
   */
  locked?: boolean
  /**
   * A running show is unlocked. Tints this header with the chrome above it — the overview's header
   * is the same row as the drill-down's navigation row, one level up.
   */
  unlockedWarning?: boolean
}

export function ShowOverview({
  projectId,
  stacks,
  activeStackId,
  onDrillStack,
  locked = false,
  unlockedWarning = false,
}: ShowOverviewProps) {
  const stackRows = stacks.filter((s) => s.type === 'STACK')
  const totalCues = stackRows.reduce(
    (n, s) => n + s.cues.filter((c) => c.cueType === 'STANDARD').length,
    0,
  )

  const [createStack, { isLoading: creating }] = useCreateProjectCueStackMutation()
  const [saveStack, { isLoading: saving }] = useSaveProjectCueStackMutation()
  const [deleteStack] = useDeleteProjectCueStackMutation()
  const [reorderStacks] = useReorderCueStacksMutation()
  const [sortByCueNumber] = useSortCueStackByCueNumberMutation()

  // Create / edit stack sheet
  const [formOpen, setFormOpen] = useState(false)
  const [editingStack, setEditingStack] = useState<CueStack | null>(null)

  // Delete confirmation
  const [pendingDelete, setPendingDelete] = useState<CueStack | null>(null)

  const handleCreateStack = useCallback(() => {
    setEditingStack(null)
    setFormOpen(true)
  }, [])

  const handleEditStack = useCallback((stack: CueStack) => {
    setEditingStack(stack)
    setFormOpen(true)
  }, [])

  const handleSaveStack = useCallback(
    async (input: CueStackInput) => {
      if (editingStack) {
        await saveStack({ projectId, stackId: editingStack.id, ...input }).unwrap()
      } else {
        const created = await createStack({ projectId, ...input }).unwrap()
        onDrillStack(created.id) // jump straight into the new stack
      }
    },
    [editingStack, projectId, saveStack, createStack, onDrillStack],
  )

  // Inline rename from the table row. PUT is the only stack write route, so echo back the
  // fields it rewrites (`loop`) unchanged; sortOrder and type it leaves alone.
  const handleRenameStack = useCallback(
    (stack: CueStack, name: string) => {
      if (name === stack.name) return
      saveStack({
        projectId,
        stackId: stack.id,
        name,
        loop: stack.loop,
      })
    },
    [saveStack, projectId],
  )

  const handleAddSeparator = useCallback(() => {
    createStack({ projectId, name: 'New Separator', loop: false, type: 'SEPARATOR', label: 'New Separator' })
  }, [createStack, projectId])

  const handleRemove = useCallback((stack: CueStack) => {
    if (stack.type === 'SEPARATOR') {
      // Separators carry no cues — delete without a confirmation prompt.
      deleteStack({ projectId, stackId: stack.id })
    } else {
      setPendingDelete(stack)
    }
  }, [deleteStack, projectId])

  const confirmDelete = useCallback(() => {
    if (pendingDelete) deleteStack({ projectId, stackId: pendingDelete.id })
    setPendingDelete(null)
  }, [pendingDelete, deleteStack, projectId])

  // dnd-kit
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event
      if (!over || active.id === over.id) return

      const oldIndex = stacks.findIndex((s) => s.id === active.id)
      const newIndex = stacks.findIndex((s) => s.id === over.id)
      if (oldIndex === -1 || newIndex === -1) return

      const reordered = arrayMove(stacks, oldIndex, newIndex)
      reorderStacks({ projectId, stackIds: reordered.map((s) => s.id) })
    },
    [stacks, projectId, reorderStacks],
  )

  const pendingDeleteCueCount = useMemo(
    () => pendingDelete?.cues.filter((c) => c.cueType === 'STANDARD').length ?? 0,
    [pendingDelete],
  )

  return (
    <div className="@container flex-1 flex flex-col overflow-hidden">
      <div
        className={cn(
          'flex h-12 shrink-0 items-center gap-4 border-b px-4 transition-colors',
          unlockedWarning && UNLOCKED_WARNING_CLASS,
        )}
      >
        <span className="text-lg font-semibold">Show</span>
        <span className="hidden @[420px]:inline text-sm text-muted-foreground">
          {stackRows.length} stacks &middot; {totalCues} cues
        </span>
        <div className="flex-1" />
        {/* Neither a stack nor a separator is a captured state, so both keep their create buttons
            (D9) — but only where creating is allowed at all. */}
        {!locked && (
          <>
            <Button
              variant="outline"
              size="sm"
              onClick={handleAddSeparator}
              aria-label="Add separator"
            >
              <SeparatorHorizontal className="size-3.5" />
              <span className="ml-1.5 hidden @[600px]:inline">Add Separator</span>
            </Button>
            <Button size="sm" onClick={handleCreateStack} aria-label="Create stack">
              <Plus className="size-3.5" />
              <span className="ml-1.5 hidden @[600px]:inline">Create Stack</span>
            </Button>
          </>
        )}
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-1.5">
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={stacks.map((s) => s.id)} strategy={verticalListSortingStrategy}>
            {stacks.map((stack, idx) => {
              if (stack.type === 'SEPARATOR') {
                return (
                  <SortableSeparatorEntry
                    key={stack.id}
                    stack={stack}
                    projectId={projectId}
                    onRemove={handleRemove}
                    locked={locked}
                  />
                )
              }
              return (
                <SortableStackEntry
                  key={stack.id}
                  stack={stack}
                  index={idx}
                  isActive={activeStackId !== null && stack.id === activeStackId}
                  onDrill={onDrillStack}
                  onEdit={handleEditStack}
                  onRename={handleRenameStack}
                  onSort={(stackId) => sortByCueNumber({ projectId, stackId })}
                  onRemove={handleRemove}
                  locked={locked}
                />
              )
            })}
          </SortableContext>
        </DndContext>

        {stacks.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 text-muted-foreground gap-2">
            <p className="text-sm">No cue stacks yet.</p>
            <Button variant="outline" size="sm" onClick={handleCreateStack}>
              <Plus className="size-3.5 mr-1.5" />
              Create your first stack
            </Button>
          </div>
        )}
      </div>

      <CueStackForm
        open={formOpen}
        onOpenChange={setFormOpen}
        stack={editingStack}
        onSave={handleSaveStack}
        isSaving={creating || saving}
      />

      <Dialog open={pendingDelete != null} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete stack?</DialogTitle>
          </DialogHeader>
          <DialogDescription>
            {pendingDeleteCueCount > 0
              ? `This permanently deletes "${pendingDelete?.name}" and its ${pendingDeleteCueCount} cue${pendingDeleteCueCount === 1 ? '' : 's'}.`
              : `This permanently deletes the empty stack "${pendingDelete?.name}".`}
          </DialogDescription>
          <DialogFooter>
            <Button variant="outline" onClick={() => setPendingDelete(null)}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={confirmDelete}>
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

import { useState, useCallback, useEffect, useRef } from 'react'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'
import { Label } from '@/components/ui/label'
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
  horizontalListSortingStrategy,
  arrayMove,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import {
  parseExtendedColour,
  serializeExtendedColour,
  isPaletteRef,
  type ExtendedChannelFlags,
  type ExtendedColour,
} from './colourUtils'
import { FxColourEditor } from './FxColourEditor'
import { useRouteProjectId } from './useRouteProjectId'

interface FxColourListPickerProps {
  value: string
  /**
   * [release] is false for a frame of a swatch's gesture and true for its end — the picker or a
   * slider let go, a field left, a recent tapped — and for an add, a removal and a reorder, so the
   * host's live push lands the last move and floors the rest.
   */
  onChange: (value: string, release: boolean) => void
  label?: string
  description?: string
  extendedChannels?: Partial<ExtendedChannelFlags>
}

interface ColourItem {
  id: string
  colour: ExtendedColour
}

let nextId = 0
function makeId(): string {
  return `colour-${nextId++}`
}

export function FxColourListPicker({
  value,
  onChange,
  label,
  description,
  extendedChannels,
}: FxColourListPickerProps) {
  const projectId = useRouteProjectId()
  const [items, setItems] = useState<ColourItem[]>(() => parseColourList(value))
  const [editingIndex, setEditingIndex] = useState<number | null>(null)
  // A reorder drag is live (dnd-kit's own, on the swatches).
  const [sorting, setSorting] = useState(false)
  const editing = editingIndex !== null || sorting

  // The last string this picker pushed up, used to tell "the parent echoed our
  // own edit back" from "the parent handed us a different list". It can't be
  // re-derived from `items`, whose serialisation need not match the text it was
  // parsed from (`red` comes back `#ff0000`). The colours would survive a
  // re-parse but the item ids would not, and SortableColourSwatch keys on them
  // while owning the hex field's state — so the swatch being typed into would
  // remount mid-keystroke.
  const lastEmitted = useRef(value)
  // The list a swatch's drag last drew, for its release to land; null when no drag has moved since.
  const dragged = useRef<ColourItem[] | null>(null)

  // `items` is otherwise seeded only at mount, but this instance can outlive the
  // value it was seeded from: a parameter input keys on `param.name`, so a
  // host that swaps the value under a mounted picker — `FxEditor` after a
  // Revert, a template form switching effects — reuses this picker, and the
  // previous swatches would stay on screen and get written back on the next edit.
  //
  // Not while a swatch is open or a reorder is live, though: a late copy of an earlier value would
  // re-mint the ids and close the swatch under the drag. The value is read again once it closes.
  useEffect(() => {
    if (editing || value === lastEmitted.current) return
    lastEmitted.current = value
    setItems(parseColourList(value))
  }, [value, editing])

  // A swatch opening starts with no drag. Not its close: its body lands a drag the close cut short as
  // it unmounts, which an exit animation can put after this.
  useEffect(() => {
    if (editingIndex !== null) dragged.current = null
  }, [editingIndex])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor)
  )

  const emitChange = useCallback(
    (newItems: ColourItem[], release: boolean) => {
      dragged.current = release ? null : newItems
      setItems(newItems)
      const newValue = newItems.map((i) => serializeExtendedColour(i.colour)).join(',')
      lastEmitted.current = newValue
      onChange(newValue, release)
    },
    [onChange]
  )
  const handleRelease = useCallback(() => {
    const pending = dragged.current
    if (pending != null) emitChange(pending, true)
  }, [emitChange])

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      setSorting(false)
      const { active, over } = event
      if (!over || active.id === over.id) return

      const oldIndex = items.findIndex((i) => i.id === active.id)
      const newIndex = items.findIndex((i) => i.id === over.id)
      if (oldIndex === -1 || newIndex === -1) return

      emitChange(arrayMove(items, oldIndex, newIndex), true)
    },
    [items, emitChange]
  )

  const handleColourChange = useCallback(
    (index: number, colour: ExtendedColour, release: boolean) => {
      const newItems = [...items]
      newItems[index] = { ...newItems[index], colour }
      emitChange(newItems, release)
    },
    [items, emitChange]
  )

  const handleAdd = useCallback(() => {
    const newItems = [...items, { id: makeId(), colour: { hex: '#ffffff', white: 0, amber: 0, uv: 0 } }]
    emitChange(newItems, true)
    setEditingIndex(newItems.length - 1)
  }, [items, emitChange])

  const handleRemove = useCallback(
    (index: number) => {
      const newItems = items.filter((_, i) => i !== index)
      emitChange(newItems, true)
      if (editingIndex === index) setEditingIndex(null)
      else if (editingIndex !== null && editingIndex > index) setEditingIndex(editingIndex - 1)
    },
    [items, emitChange, editingIndex]
  )

  return (
    <div>
      {label && <Label className="text-xs mb-1.5 block">{label}</Label>}
      {description && (
        <p className="text-[11px] text-muted-foreground mb-1">{description}</p>
      )}
      {/* An explicit ordered list of literal colours: Colour Cycle takes no palette (terminology
          plan D14), and Palette Steps is the FX that steps through palettes. */}
      <div className="flex items-center gap-1 flex-wrap">
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragStart={() => setSorting(true)}
          onDragEnd={handleDragEnd}
          onDragCancel={() => setSorting(false)}
        >
          <SortableContext
            items={items.map((i) => i.id)}
            strategy={horizontalListSortingStrategy}
          >
            {items.map((item, index) => (
              <SortableColourSwatch
                key={item.id}
                item={item}
                isEditing={editingIndex === index}
                onEdit={() => setEditingIndex(editingIndex === index ? null : index)}
                onRemove={() => handleRemove(index)}
                onColourChange={(colour, release) => handleColourChange(index, colour, release)}
                onRelease={handleRelease}
                extendedChannels={extendedChannels}
                projectId={projectId}
              />
            ))}
          </SortableContext>
        </DndContext>
        <button
          type="button"
          onClick={handleAdd}
          className="w-7 h-7 rounded border border-dashed border-border flex items-center justify-center text-muted-foreground hover:text-foreground hover:border-foreground/50 transition-colors text-sm"
          title="Add colour"
        >
          +
        </button>
      </div>
    </div>
  )
}

function SortableColourSwatch({
  item,
  isEditing,
  onEdit,
  onRemove,
  onColourChange,
  onRelease,
  extendedChannels,
  projectId,
}: {
  item: ColourItem
  isEditing: boolean
  onEdit: () => void
  onRemove: () => void
  onColourChange: (colour: ExtendedColour, release: boolean) => void
  onRelease: () => void
  extendedChannels?: Partial<ExtendedChannelFlags>
  projectId: number | undefined
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: item.id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  return (
    <Popover
      open={isEditing}
      onOpenChange={(open) => {
        if (!open && isEditing) onEdit()
      }}
    >
      <div ref={setNodeRef} style={style} className="relative group">
        <PopoverTrigger asChild>
          <button
            type="button"
            className="w-7 h-7 rounded border border-border cursor-grab active:cursor-grabbing relative overflow-hidden"
            style={{ backgroundColor: item.colour.hex }}
            title={item.colour.hex}
            onClick={onEdit}
            {...attributes}
            {...listeners}
          />
        </PopoverTrigger>
        {/* Remove button */}
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            onRemove()
          }}
          className="absolute -top-1.5 -right-1.5 w-4 h-4 rounded-full bg-destructive text-destructive-foreground text-[10px] leading-none flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity"
          title="Remove"
        >
          x
        </button>
      </div>
      <PopoverContent className="w-[352px] p-3" align="start" side="right">
        {/* No Palettes band: a list's colours are literals. */}
        <FxColourEditor
          colour={item.colour}
          extendedChannels={extendedChannels}
          onColourChange={(colour) => onColourChange(colour, false)}
          onRelease={onRelease}
          open={isEditing}
          projectId={projectId}
        />
      </PopoverContent>
    </Popover>
  )
}

/** The list's colours; a palette reference names none here, so it is dropped, as the desk drops it. */
function parseColourList(value: string): ColourItem[] {
  return value
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '' && !isPaletteRef(s))
    .map((raw) => ({ id: makeId(), colour: parseExtendedColour(raw) }))
}

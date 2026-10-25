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
import { ColourEditorBody } from './ColourEditorBody'

interface FxColourListPickerProps {
  value: string
  onChange: (value: string) => void
  label?: string
  description?: string
  extendedChannels?: Partial<ExtendedChannelFlags>
}

interface ColourItem {
  id: string
  raw: string
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
  const [items, setItems] = useState<ColourItem[]>(() => parseColourList(value))
  const [editingIndex, setEditingIndex] = useState<number | null>(null)

  // The last string this picker pushed up, used to tell "the parent echoed our
  // own edit back" from "the parent handed us a different list". It can't be
  // re-derived from `items`, whose serialisation need not match the text it was
  // parsed from (`red` comes back `#ff0000`). The colours would survive a
  // re-parse but the item ids would not, and SortableColourSwatch keys on them
  // while owning the hex field's state — so the swatch being typed into would
  // remount mid-keystroke.
  const lastEmitted = useRef(value)

  // `items` is otherwise seeded only at mount, but this instance can outlive the
  // value it was seeded from: a parameter input keys on `param.name`, so a
  // host that swaps the value under a mounted picker — `FxEditor` after a
  // Revert, a template form switching effects — reuses this picker, and the
  // previous swatches would stay on screen and get written back on the next edit.
  useEffect(() => {
    if (value === lastEmitted.current) return
    lastEmitted.current = value
    setItems(parseColourList(value))
    setEditingIndex(null)
  }, [value])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor)
  )

  const emitChange = useCallback(
    (newItems: ColourItem[]) => {
      setItems(newItems)
      const newValue = newItems.map((i) => serializeExtendedColour(i.colour)).join(',')
      lastEmitted.current = newValue
      onChange(newValue)
    },
    [onChange]
  )

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event
      if (!over || active.id === over.id) return

      const oldIndex = items.findIndex((i) => i.id === active.id)
      const newIndex = items.findIndex((i) => i.id === over.id)
      if (oldIndex === -1 || newIndex === -1) return

      emitChange(arrayMove(items, oldIndex, newIndex))
    },
    [items, emitChange]
  )

  const handleColourChange = useCallback(
    (index: number, colour: ExtendedColour) => {
      const newItems = [...items]
      newItems[index] = { ...newItems[index], colour, raw: serializeExtendedColour(colour) }
      emitChange(newItems)
    },
    [items, emitChange]
  )

  const handleAdd = useCallback(() => {
    const newItems = [...items, { id: makeId(), raw: '#ffffff', colour: { hex: '#ffffff', white: 0, amber: 0, uv: 0 } }]
    emitChange(newItems)
    setEditingIndex(newItems.length - 1)
  }, [items, emitChange])

  const handleRemove = useCallback(
    (index: number) => {
      const newItems = items.filter((_, i) => i !== index)
      emitChange(newItems)
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
          onDragEnd={handleDragEnd}
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
                onColourChange={(colour) => handleColourChange(index, colour)}
                extendedChannels={extendedChannels}
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
  extendedChannels,
}: {
  item: ColourItem
  isEditing: boolean
  onEdit: () => void
  onRemove: () => void
  onColourChange: (colour: ExtendedColour) => void
  extendedChannels?: Partial<ExtendedChannelFlags>
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: item.id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  const handleColourEdit = useCallback(
    (colour: ExtendedColour) => onColourChange(colour),
    [onColourChange],
  )

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
      <PopoverContent className="w-auto p-3" align="start" side="right">
        <ColourEditorBody
          colour={item.colour}
          onColourChange={handleColourEdit}
          rawValue={item.raw}
          extendedChannels={extendedChannels}
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
    .map((raw) => ({ id: makeId(), raw, colour: parseExtendedColour(raw) }))
}

import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
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
  isTemplateRef,
  type ExtendedChannelFlags,
  type ExtendedColour,
} from './colourUtils'
import { useColourTemplates, type ColourTemplates } from './FxColourTemplates'
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
  const colourTemplates = useColourTemplates()
  const [items, setItems] = useState<ColourItem[]>(() => parseColourList(value))
  const [editingIndex, setEditingIndex] = useState<number | null>(null)

  // The last string this picker pushed up, used to tell "the parent echoed our
  // own edit back" from "the parent handed us a different list". It can't be
  // re-derived from `items`: emitChange keeps a template reference as `tmpl:…`
  // while the parsed item carries a #000000 placeholder, so an items-vs-value
  // comparison never matches on a list holding one and re-parses on every edit.
  // The colours would survive that (parse/serialize round-trips) but the item
  // ids would not, and SortableColourSwatch keys on them while owning the hex
  // field's state — so the swatch being typed into would remount mid-keystroke.
  const lastEmitted = useRef(value)

  // `items` is otherwise seeded only at mount, but this instance can outlive the
  // value it was seeded from: ParameterInput keys on `param.name`, so editing
  // the same effect on a second target — busking's ActiveEffectSheet swaps its
  // context without closing — reuses this picker, and the previous target's
  // swatches would stay on screen and get written back on the next edit.
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
      const newValue = newItems
        .map((i) =>
          isTemplateRef(i.raw) ? i.raw : serializeExtendedColour(i.colour),
        )
        .join(',')
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
    (index: number, colour: ExtendedColour, raw?: string) => {
      const newItems = [...items]
      newItems[index] = {
        ...newItems[index],
        colour,
        raw: raw ?? serializeExtendedColour(colour),
      }
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
      {/* An explicit ordered list, every entry a literal or a `tmpl:` reference.
          There is deliberately no successor to the old "use entire palette" (`P*`) checkbox: a
          template holds one colour, so there is no set to expand, and naming the colours a cycle
          steps through is what makes it readable. */}
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
                onColourChange={(colour, raw) => handleColourChange(index, colour, raw)}
                extendedChannels={extendedChannels}
                colourTemplates={colourTemplates}
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
  colourTemplates,
}: {
  item: ColourItem
  isEditing: boolean
  onEdit: () => void
  onRemove: () => void
  onColourChange: (colour: ExtendedColour, raw?: string) => void
  extendedChannels?: Partial<ExtendedChannelFlags>
  /** Lifted to the parent so one query serves every swatch in the list. */
  colourTemplates: ColourTemplates
}) {
  const isRef = isTemplateRef(item.raw)
  const refSwatch = isRef ? colourTemplates.swatchFor(item.raw) : null

  // What the editor opens on, and what an edit starts from. A referencing item's own `colour` is the
  // `#000000` placeholder `parseColourList` gives it, so everything inside the popover — the wheel,
  // the hex field, the "save this as a template" offer — has to read the *resolved* colour instead,
  // or a reference opens at black and the first drag jumps the colour rather than nudging it. Same
  // rule `FxColourPicker` follows for the single-colour parameter.
  // Memoised: three callbacks and the open-editor effect depend on it, and a fresh object per render
  // would give them a new identity on every render (the `?? []` trap in CLAUDE.md, one type up).
  const effectiveColour = useMemo(
    () => (refSwatch ? { ...item.colour, hex: refSwatch } : item.colour),
    [refSwatch, item.colour],
  )

  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: item.id })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 10 : undefined,
    opacity: isDragging ? 0.5 : undefined,
  }

  // Touching the editor replaces a reference with the literal it resolved to — the same rule
  // `FxColourPicker` follows, and why the body is handed `effectiveColour` rather than `item.colour`.
  const handleColourEdit = useCallback(
    (colour: ExtendedColour) => onColourChange(colour),
    [onColourChange],
  )

  // Picking a template keeps the item's *stored* colour as the placeholder and swaps the raw value
  // for the reference, so the item goes back to following the template.
  const handlePickTemplate = useCallback(
    (ref: string) => onColourChange(item.colour, ref),
    [onColourChange, item.colour],
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
            style={{ backgroundColor: effectiveColour.hex }}
            title={isRef ? colourTemplates.labelFor(item.raw) : item.colour.hex}
            onClick={onEdit}
            {...attributes}
            {...listeners}
          >
            {/* A referencing swatch gets a corner dot rather than a code across its face: the name
                does not fit in 28px, and a two-character code is exactly what this change set out to
                stop showing. The name is on hover and in the popover. */}
            {isRef && (
              <span className="absolute bottom-0 right-0 size-1.5 rounded-full bg-white/90 ring-1 ring-black/40 pointer-events-none" />
            )}
          </button>
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
          colour={effectiveColour}
          onColourChange={handleColourEdit}
          rawValue={item.raw}
          onPickTemplate={handlePickTemplate}
          templates={colourTemplates.templates}
          extendedChannels={extendedChannels}
        />
      </PopoverContent>
    </Popover>
  )
}

/** Parse a comma-separated colour list into ColourItem array */
function parseColourList(value: string): ColourItem[] {
  if (!value.trim()) return []
  return value.split(',').map((s) => {
    const trimmed = s.trim()
    return {
      id: makeId(),
      raw: trimmed,
      // A reference carries a placeholder colour: the real one comes from the template at render
      // time, and baking it in here would make an edit elsewhere invisible until a remount.
      colour: isTemplateRef(trimmed)
        ? { hex: '#000000', white: 0, amber: 0, uv: 0 }
        : parseExtendedColour(trimmed),
    }
  })
}

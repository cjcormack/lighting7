import { Slider } from '@/components/ui/slider'
import { EditorField } from './EditorField'
import { EditorLabel } from './EditorLabel'

/**
 * One named value, said twice: a typed box and a slider that drive the same number, under an
 * `EditorLabel` (editor-kit plan D9).
 *
 * Shared because the two panels that draw it are the two the keyboard work gave text fields to —
 * `PositionCell`'s Pan and Tilt, and the old fan popover's From and To — and they had arrived at
 * byte-identical markup. A row is the unit an editor's keyboard rules land on (`useEditorKeyboard`
 * finds "the next field" by walking the inputs in DOM order), so a future change to the field's
 * height, its aria wiring or its select-on-focus behaviour has to reach both or neither.
 *
 * `SliderCell` is deliberately *not* a third caller: its row is the other way round — the slider
 * takes the width and the box sits beside it, because that editor is one value and has the whole
 * popover for it. Folding that in would mean a layout prop, which is where a shared component
 * stops sharing anything.
 *
 * **One unit per row.** [min], [max], [value] and [onChange] are all in the row's unit — bytes on
 * a spread, degrees on an annotated mover's position — and the caller converts on both sides, so the
 * box and the slider can never disagree about what a number means. The field owns the draft rule
 * (`EditorField`); the caller owns the clamp, because a position's bounds come from its resolution
 * while a raw spread's ends are flat bytes.
 */
export function ValueFieldRow({
  label,
  unit,
  min,
  max,
  value,
  onChange,
  seed,
}: {
  /** Names the row, and is the field's accessible name. */
  label: string
  /** The unit glyph inside the box — `°` on a degree row. */
  unit?: string
  min: number
  max: number
  /** What the box and the slider show — the desk's value, not a draft. */
  value: number
  onChange: (next: number) => void
  /** The character that opened the editor, for the first row only. See `EditorField`. */
  seed?: string | null
}) {
  return (
    <div className="space-y-1">
      <EditorLabel>{label}</EditorLabel>
      <EditorField label={label} unit={unit} min={min} max={max} value={value} onCommit={onChange} seed={seed} />
      <Slider min={min} max={max} step={1} value={[value]} onValueChange={([next]) => onChange(next)} />
    </div>
  )
}

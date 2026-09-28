/**
 * The look of a selected cell.
 *
 * A **fill and a foreground outline**, deliberately never another *colour-coded* ring.
 * `ownership.ts` owns a six-value ring vocabulary — parked, programmer touched/untouched, effect,
 * cue, baseline — that an operator has to be able to read at a glance in a blacked-out room, and a
 * seventh hue would compete with all six. Selection is a different kind of fact anyway: it says
 * "these are what your next edit hits", not "this is who owns the value", so it wants a different
 * *kind* of affordance rather than a different hue — and the text colour is the one line on the
 * grid that codes for nothing.
 *
 * Applied as an `::after` overlay so it layers over whatever `ownershipCellClass` produced without
 * either one having to know about the other.
 */
export function cellSelectionClass(selected: boolean, gutter = true): string {
  // A fill plus a 2px **foreground** outline. The outline is not a seventh colour in the ownership
  // vocabulary — it is the theme's text colour, white on the dark desk and near-black on light —
  // which is exactly why it can sit beside all six rings without being read as one of them. It
  // used to be the primary blue at 1px over a /20 fill, which was the "You — Record takes this"
  // ring (`ring-1 ring-primary bg-primary/10`) to within a shade: a selected empty cell and an
  // owned full cell were the same picture, and a heavier blue only made it a slightly bolder
  // version of the same picture. A spreadsheet's range border is a different *kind* of line from
  // its cell borders, and this is that.
  //
  // `gutter` is `SheetColumn.gutter`, and it decides where the overlay sits so that it and the
  // cell's *own* border are the same box on all four edges. A cell wrapper reserves an 18px marks
  // gutter on the right, so `inset-0` draws the selection around a box 18px wider than the
  // ownership ring inside it — fine where the gutter carries glyphs, wrong on the DMX sheet, which
  // has none and whose "has a value" ring is the most-read line on the grid. Without the gutter the
  // wrapper is padded 2px all round and this matches it. The two insets are spelled here rather
  // than passed in: a stringly-typed styling parameter is one nothing can check.
  const base = 'after:pointer-events-none after:absolute after:rounded-sm after:bg-primary/25 after:ring-2 after:ring-inset after:ring-foreground/90'
  if (!selected) return ''
  return gutter ? `after:inset-0 ${base}` : `after:inset-0.5 ${base}`
}

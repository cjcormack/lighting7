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
export function cellSelectionClass(selected: boolean, gutter = true, dimmed = false, join: CellSelectionJoin = NO_JOIN): string {
  if (selected && (join.above || join.below)) return joinedSelectionClass(gutter, dimmed, join)
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
  // Dimmed: a grid that is not the key region (show-cue-editing plan §4.5) keeps its selection on
  // screen, quieter, so the operator can see which of two grids the next key reaches.
  const look = dimmed
    ? 'after:pointer-events-none after:absolute after:rounded-sm after:bg-primary/10 after:ring-1 after:ring-inset after:ring-foreground/40'
    : base
  return gutter ? `after:inset-0 ${look}` : `after:inset-0.5 ${look}`
}

/** Whether the cell's neighbours in its column, directly above and below, are selected too. */
export interface CellSelectionJoin {
  above: boolean
  below: boolean
}

const NO_JOIN: CellSelectionJoin = { above: false, below: false }

/**
 * A selected cell whose neighbour above or below in the same column is selected **drops that edge**
 * of its outline, so a range down one column is one ring rather than a stack of boxes (stack-timeline
 * plan §4.2). The overlay reaches over the row's bottom line where it joins the cell below, and the
 * outline is drawn as the sides it keeps — a ring cannot leave an edge out. Whole class strings, one
 * per arm, for Tailwind's scanner.
 */
function joinedSelectionClass(gutter: boolean, dimmed: boolean, join: CellSelectionJoin): string {
  const { above, below } = join
  const position = gutter
    ? below
      ? 'after:inset-x-0 after:top-0 after:-bottom-px'
      : 'after:inset-x-0 after:top-0 after:bottom-0'
    : `after:inset-x-0.5 ${above ? 'after:top-0' : 'after:top-0.5'} ${below ? 'after:-bottom-px' : 'after:bottom-0.5'}`
  const radius = above && below ? 'after:rounded-none' : above ? 'after:rounded-b-sm' : 'after:rounded-t-sm'
  const edges = dimmed
    ? above && below
      ? 'after:bg-primary/10 after:shadow-[inset_1px_0_0_color-mix(in_oklab,var(--foreground)_40%,transparent),inset_-1px_0_0_color-mix(in_oklab,var(--foreground)_40%,transparent)]'
      : above
        ? 'after:bg-primary/10 after:shadow-[inset_1px_0_0_color-mix(in_oklab,var(--foreground)_40%,transparent),inset_-1px_0_0_color-mix(in_oklab,var(--foreground)_40%,transparent),inset_0_-1px_0_color-mix(in_oklab,var(--foreground)_40%,transparent)]'
        : 'after:bg-primary/10 after:shadow-[inset_1px_0_0_color-mix(in_oklab,var(--foreground)_40%,transparent),inset_-1px_0_0_color-mix(in_oklab,var(--foreground)_40%,transparent),inset_0_1px_0_color-mix(in_oklab,var(--foreground)_40%,transparent)]'
    : above && below
      ? 'after:bg-primary/25 after:shadow-[inset_2px_0_0_color-mix(in_oklab,var(--foreground)_90%,transparent),inset_-2px_0_0_color-mix(in_oklab,var(--foreground)_90%,transparent)]'
      : above
        ? 'after:bg-primary/25 after:shadow-[inset_2px_0_0_color-mix(in_oklab,var(--foreground)_90%,transparent),inset_-2px_0_0_color-mix(in_oklab,var(--foreground)_90%,transparent),inset_0_-2px_0_color-mix(in_oklab,var(--foreground)_90%,transparent)]'
        : 'after:bg-primary/25 after:shadow-[inset_2px_0_0_color-mix(in_oklab,var(--foreground)_90%,transparent),inset_-2px_0_0_color-mix(in_oklab,var(--foreground)_90%,transparent),inset_0_2px_0_color-mix(in_oklab,var(--foreground)_90%,transparent)]'
  return `after:pointer-events-none after:absolute ${position} ${radius} ${edges}`
}

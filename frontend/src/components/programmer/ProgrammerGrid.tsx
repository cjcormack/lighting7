import type { ReactNode } from 'react'
import { KeyRound, Layers, Search } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  LayerLegend,
  OwnershipKey,
  OwnershipLegend,
} from '@/components/fixtures-list/OwnershipLegend'
import { cn, labelUnlessCompact } from '@/lib/utils'
import { FIXTURE_FILTER_HINT, FIXTURE_FILTER_PLACEHOLDER_SHORT } from '@/lib/fixtureFilterCopy'
import type { ColumnVisibility } from '@/components/fixtures-list/ColumnsMenu'
import { FixturesListContainer } from '@/components/fixtures-list/FixturesListContainer'
import { SheetPage } from '@/components/sheet/SheetPage'
import { ScopedEditorContextProvider } from './ScopedEditorContext'
import { LayerRowNotices } from './LayerRowNotices'
import { ProgrammerScopeBand } from './ProgrammerScopeBand'
import { SelectionBar } from './SelectionBar'
import { useProgrammerScope } from './ProgrammerScope'

/**
 * The programmer's value grid: the fixtures-list spreadsheet with per-cell ownership colouring,
 * pointed at whatever the scope band above says.
 *
 * Writes route through an `EditorContext` this component supplies itself, derived from the scope:
 * `live` for Output and Local — which since the programmer redesign means "write the programmer",
 * not "write DMX" — and `lookLayer` when a Look layer is focused. The page keeps its own outer
 * `live` provider for the *rail*, whose FX controls write the programmer whatever the grid is
 * looking at.
 *
 * **Unconditionally mounted, and that is load-bearing.** `useListSelection` clears its Redux scope
 * on unmount, so anything that mounts this conditionally — a tab, a collapse, an `{open && …}` —
 * silently discards the fixture selection that Record and Record-look scope on. The pane this
 * replaced needed a `forceMount` escape hatch for exactly that; here there is nothing to force.
 *
 * **Row B is the toolbar's first line**, and it is here rather than above the workspace because
 * everything on it — the scope, the filter, Lit, Groups, Columns — is a fact about *this grid*,
 * and a band spanning the page reached across the rail to say it. The scope band was a
 * full-width sibling of the action bar until session 1 of the space plan; `sheetControls` on the
 * action bar (which hosted Groups and Columns) went at the same time and for the same reason.
 *
 * The *state* behind Groups and Columns still lives in `ProgrammerBody`, inside the memo barrier
 * — this component renders the controls, it does not own them.
 *
 * **Row B has a phone arm and a folded arm, and they are different questions.** Below `@[600px]`
 * of *this column* a key button appears, because the 22px ownership footer is not rendered at that
 * width (space plan D8) — outside layer scope only; see the note beside `ScopedKeyPopover` below.
 * The filter becomes a search icon over a popover too, but at `@[360px]` and not here — the two
 * used to be one threshold and are now two, because one is about a footer being absent and the
 * other about a field having room (see the note beside the field). The folded arm
 * is about *height*: under `@media (max-height: 500px)` `ProgrammerBody` stops drawing row A and
 * hands its two halves here as `leading`, so the page's two rows of chrome are one 40px line —
 * which on an 852×393 landscape phone is the difference between three fixture rows and eight.
 * Height is the one thing a container query cannot ask, so that arm is a `useMediaQuery` above
 * the barrier; the grid element itself never moves, so the grid never remounts.
 *
 * **Every row of chrome here is 40px on a 12px gutter and holds 32px controls** — the chrome
 * tidy-up (`lighting7/docs/plans/programmer-chrome-design/`). Row B was 36 with 28px and 32px
 * controls side by side; the 28s (Groups, the search icon, the key) are the `size="sm"` 32 now,
 * and the only 28s left on the page are controls *inside* a control — Update and Revert in the
 * source box, the template chips and `New` on the selection bar.
 */
export function ProgrammerGrid({
  projectId,
  grouped,
  onGroupedChange,
  columnVisibility,
  onColumnVisibilityChange,
  leading,
}: {
  projectId: number
  grouped: boolean
  onGroupedChange: (next: boolean) => void
  columnVisibility: ColumnVisibility
  onColumnVisibilityChange: (next: ColumnVisibility) => void
  /**
   * Row A's two halves, when the page is too short to give them a row of their own — the
   * short-height arm of space plan D8. `null` at every ordinary height, where `ProgrammerBody`
   * draws them above the workspace as it always has. They arrive as an *element* rather than as
   * a flag because they are the page's components, created above the memo barrier: this grid
   * mounts them, it does not know what they are.
   */
  leading?: ReactNode
}) {
  // Derived from the scope, and provided **unconditionally** — only the value varies, so the tree
  // shape never changes and the container below never unmounts. Rendering a different provider
  // (or a different grid) per scope is the exact hazard the doc comment above describes. The
  // derivation is `ScopedEditorContextProvider`'s, shared with the rail's Colour and Spread tabs.
  return (
    <ScopedEditorContextProvider>
      <ProgrammerGridBody
        projectId={projectId}
        grouped={grouped}
        onGroupedChange={onGroupedChange}
        columnVisibility={columnVisibility}
        onColumnVisibilityChange={onColumnVisibilityChange}
        leading={leading}
      />
    </ScopedEditorContextProvider>
  )
}

function ProgrammerGridBody({
  projectId,
  grouped,
  onGroupedChange,
  columnVisibility,
  onColumnVisibilityChange,
  leading,
}: {
  projectId: number
  grouped: boolean
  onGroupedChange: (next: boolean) => void
  columnVisibility: ColumnVisibility
  onColumnVisibilityChange: (next: ColumnVisibility) => void
  leading?: ReactNode
}) {
  // Whether the phone arm draws the key at all — see the note beside `ScopedKeyPopover` below.
  const layerScope = useLayerScopeKey()
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <FixturesListContainer
        grouped={grouped}
        selectionScope="programmer"
        showOwnership
        columnVisibility={columnVisibility}
        onColumnVisibilityChange={onColumnVisibilityChange}
        // The one word: this row keeps the field down to 360px of its own width, where the long
        // placeholder clipped mid-word (`lib/fixtureFilterCopy.ts`). The hint rides the field's
        // `title` and `aria-label` at every width.
        filterPlaceholder={FIXTURE_FILTER_PLACEHOLDER_SHORT}
        compactControls={!!leading}
        // Cmd+K's ?select= links target the fixtures/groups pair; consuming them here would bounce
        // a group select straight back out to /groups/list.
        enableDeepLinkSelect={false}
        // Include auto-selects the heads it pulled in — this is where you then edit them.
        respondToIncludeSelection
        renderToolbar={({
          filter,
          lit,
          columns,
          selection,
          cells,
          cellEntryKey,
          cellClearKey,
          templateTargets,
          targetFamilies,
          targetEmitters,
          marqueeDragging,
        }) => (
          <div className="flex flex-col">
            {/* Row B. Its own `@container`, with every query on the child — the wrapper can never
                be measured by the classes it hosts (`ProgrammerWorkspace`'s doc comment). */}
            <div className="@container">
              {/* The folded row **wraps rather than overlaps**, and only the folded row can:
                  unfolded, row B's tools have a hard minimum of ~290px and there is no width this
                  app is usable at where that does not fit on one line.

                  `h-10` becomes `min-h-10` in that arm (`SheetPage.Row`'s `minHeight`) because a
                  wrapped row is two lines tall. With one line it is the same 40px either way:
                  every chrome row on this page is 40 and holds 32px controls, so the 4px above
                  and below a control is the row's own inset, not padding, and the folded row
                  keeps it by having the same minimum.
                  `gap-y-1.5` is the gap *between* the lines, and is stated separately from `gap-2`
                  so the two can be read apart: at 6px a wrapped folded row is 70px against the
                  80px row A and row B cost unfolded, so the fold still pays for itself at the
                  widths where it has to take a second line. */}
              <SheetPage.Row minHeight={!!leading} className={cn(leading && 'flex-wrap gap-y-1.5')}>
                {/* The short-height arm: row A has no row of its own and its two halves lead this
                    one (space plan D8), and they get an `@container` of their own — because the
                    question their thresholds ask ("has this box room for the word `Editing`?") is
                    about the ~380px the flex gives them here, not about the 750px grid column. It
                    is measured, so it cannot be wrong about a rig whose verbs are wider than the
                    artboard's.

                    Everything after this separator goes to its icon arm while the row is folded —
                    the scope pills through `compact`, `Lit` and `Columns` through
                    `compactControls`, the filter as its search icon — which is what
                    `PhoneLandscape` draws. It is not decoration: the tools' three words are 130px,
                    and the leading block is what pays for them. Without it the source box, a
                    `flex-1` sharing its block with a 230px action bar, rendered four pixels wide
                    on an 852×393 phone. (The bar is 285px since Blind came back into it —
                    `PD-BLIND-ON-PROGRAMMER` — and the source box measures 117px beside it in the
                    419px this container gets there.)

                    **An explicit `min-width` floor is what stops the action bar painting over these tools**, and
                    it cannot be zero or be left to the intrinsic minimum. (Written as a
                    `min-width`, not as the utility itself: Tailwind scans *comments*, so a
                    complete utility spelled in prose is emitted as a real CSS rule — and when the
                    number is later retuned, as this one was from 360 to 410, the stale rule
                    outlives the class and ships as dead CSS. The repo's `CLAUDE.md` states the
                    rule for container queries; it is the same hazard here.) `container-type:
                    inline-size` brings size containment, so this block's min-content size is
                    **zero** whatever it holds — the trap that also cost the source box its rungs, and with
                    them its `flex-1`. Its contents are not zero: the action bar is `shrink-0` and 285px
                    wide once iconic, so the flex squeezed this block to 283px on a 612×457 window
                    while the bar kept its size and drew its Record button across the scope pills
                    and the search icon beside it. An explicit floor makes the block push back, and
                    the row's `flex-wrap` turns that push into a second line instead of an overlap.

                    **The number is the pair's *legible* minimum, not its absolute one**, and that
                    is a second decision on top of the first. 285 (the iconic bar) + 17 (the
                    divider and two gaps) + 36 (the source box as a bare glyph) is 338, and a floor
                    there does stop the overlap — but it also leaves a band, about 700 to 800px of
                    row, where the row still fits on one line and the box in it is too narrow to
                    say `No source`. The words then vanished between 612 and 945 and came back
                    either side, which reads as a bug however well it is explained.

                    So the floor is the box at its 102px full size instead: 285 + 17 + 102 = 404,
                    and **410** is that with a few pixels of slack — deliberately the same number as
                    the box's own rung in `ProgrammerSourceStrip`, because they are the same
                    question asked from the two ends. Wherever the row is on one line the box has
                    its words, and wherever it has not got the room the row wraps and the box gets
                    a whole line. It still clears the 419px the block gets on an 852×393 phone, so
                    that arm stays on one line — by nine pixels, which is why both numbers are
                    measured rather than rounded.

                    It is a floor for the *pair*. The box in the two states that fill (a cue, a
                    Look) still truncates and clips as it always did.

                    **And it is a floor rather than a share**, which is the second half of the same
                    fix. `flex-1` here meant this block took every spare pixel of the row while its
                    own contents stayed a fixed 404px — so the slack pooled *inside* it, behind the
                    verbs, and drew a 204px hole at 1024×457 wherever there was room to spare.
                    `flex-initial` under size containment resolves its base to zero, so the block
                    now sits at exactly its floor and the slack goes past it to the tools, where
                    the filter field spends it. `has-[[data-fills]]` puts the growth back for the
                    two states that can use it: a cue or a Look wants the width, and
                    `ProgrammerSourceStrip` marks those with `data-fills` precisely so this block
                    can ask — a parent cannot read a child's props, but it can read its DOM.

                    `min(410px, 100%)` rather than a bare 410, because a floor taller than the
                    room cannot be met and simply overflows again — a window both short enough to
                    fold and under ~393px wide is an ordinary desktop resize away. Clamped, the
                    block fills its line instead, which is the most it could have had.

                    **The 285 is the bar above `@[600px]` of the pair's own container.** Below it
                    the fade trigger drops its chevron (`ProgrammerActionBar`), and the bar is
                    247 — so on the 852×393 phone, where the pair's container is 419, this floor
                    and the two measured numbers derived from the same 285 (`@max-[739px]` and
                    `@[840px]` below) carry ~38px of slack they did not have when they were
                    measured. They still hold, and they were left where they are rather than
                    re-derived: move all three together, against 852×393 and 945×457, the way
                    their notes prescribe, or none of them. */}
                {leading && (
                  <>
                    <div className="@container flex min-w-[min(410px,100%)] flex-initial items-center gap-2 has-[[data-fills]]:flex-1">
                      {leading}
                    </div>
                    <span className="h-[22px] w-px shrink-0 self-center bg-border" />
                  </>
                )}
                {/* The tools travel together, so that what wraps is **the whole of row B's right
                    half rather than whichever buttons happened not to fit** — the folded row then
                    breaks back into the two rows it was folded from, which is the one two-line
                    shape an operator has already seen.

                    It is also what decides *when* to break, with no threshold to tune: this block
                    is `shrink-0`, so its flex base size is its own content width, and the line
                    breaks exactly when that plus the leading block's 410px floor no longer fit.
                    Move a control on or off this row and the break moves with it.

                    `contents` in the unfolded arm, not a second class of layout: the wrapper then
                    generates no box at all and every child lays out against row B exactly as it
                    did before — including the `flex-1` spacer below, which has to be a sibling of
                    `Groups` in the row itself to push it right.

                    **And when it has wrapped, these controls stop being icons.** `compact` is the
                    caller saying "the row is narrower than the viewport", which stops being true
                    the moment this block gets a line of its own — so the words come back, through
                    the `CONTROL_LABEL_CLASS` hook `labelUnlessCompact` puts on every one of them.
                    It has to be an ancestor that says so: a boolean prop cannot tell one line from
                    the other, and Tailwind scans source text, so the query cannot be composed
                    anywhere but in a literal like this one.

                    Both ends of that range are measured, and both are measured against **row B's
                    container**, which is this row plus its own `px-3` — not the workspace's, which
                    is 40px wider again wherever the rail's collapsed strip is docked. Reading the
                    wrong one is how the first cut of this put the upper bound 33px low and left
                    the words off at exactly the width the user reported.

                    `@max-[739px]` **is** "it wrapped": the break comes when the leading block's
                    410px floor and this block's 288px of icons exceed the row's
                    24px-inset content box — plus the divider and two gaps between them, which is
                    the same 17 counted inside that block above. 410 + 17 + 288 is 715, so 739 is
                    the first container width that fits on one line. **The literal is that number,
                    not the one below it**: Tailwind's `@max-*` compiles to a strict `width < N`,
                    so `@max-[739px]` reads as "narrower than the first width that fits", which is
                    exactly "it wrapped". Written as 738 — the last width that does *not* fit,
                    which looks like the right number to reach for — the two disagree at exactly
                    738, where the row wraps and the words stay hidden.
                    It moves with that floor: raise one without the other and a wrapped row keeps
                    its icons — which is what the first cut did, counting 706 here and 17 four
                    paragraphs up. Dropping the divider in one place and not the other left a
                    ten-pixel band that wrapped and stayed iconic. `@[560px]` is this block *with* its words —
                    522px measured, plus that same inset and a little slack — because below it the
                    words would not fit on the line they were brought back for.

                    The two failure modes either side are both benign, which is what makes a pair
                    of measured numbers safe here: too high, and a wrapped row keeps its icons for
                    a few pixels; too low, and the words make this block wider, which forces the
                    very wrap they are shown for. `flex-wrap` is the backstop under both — a line
                    this block genuinely cannot fit becomes a third line rather than something
                    clipped at the row's edge.

                    **`min-w-[384px]` is what stops that backstop firing instead of the outer
                    wrap**, and it is the half that was missing. Once this block grows at
                    `@[840px]` its flex-basis is 0, so for the *row's* line-breaking its
                    hypothetical size is zero and it can never be the thing that pushes the row
                    onto a second line. Squeezed below its own content it therefore stayed on line
                    one and shed its **last child** — the key button, alone at the far left of a
                    second line — which is exactly the "whichever buttons happened not to fit"
                    failure this block exists to prevent, arriving through the block itself. The
                    floor is this block's content with the field at the field's own 132px minimum:
                    244 of icons once the search icon has given way, a gap, and 132. With it the
                    row breaks as a unit when it must. Reproduced at a container of 830 and 834
                    before it was added — two lines, with one icon on the second. */}
                <div
                  className={cn(
                    leading
                      ? 'flex shrink-0 flex-wrap items-center gap-2 @[560px]:@max-[739px]:[&_.control-label]:inline @[840px]:min-w-[384px] @[840px]:flex-1'
                      : 'contents',
                  )}
                >
                  <ProgrammerScopeBand compact={!!leading} />
                  {/* The filter gives before anything else does: it is the one control here whose
                      width is a preference rather than a size — **and for the same reason it is
                      what takes the room back**. On the folded row it is the only elastic thing
                      left once the leading block sits at its floor, so every spare pixel becomes
                      field rather than a hole, at every width, with no threshold to tune.

                      That is why it has no `max-w` in the folded arm. The 340px ceiling is right
                      in the unfolded row, where a `flex-1` spacer follows the field and an
                      unbounded field would leave the row's right end unanchored; here the field IS
                      the spacer and the tools anchor the end, so a ceiling would only put the hole
                      back beyond it.

                      `@[840px]` is where a field is worth having instead of the icon, and it has
                      **two** needs to clear rather than one. The first is
                      `PD-FILTER-PLACEHOLDER-CLIP`'s: the placeholder wants 96px behind a 36px
                      search icon, so 132px of field. The second is that revealing the field puts
                      this block's floor at 384px, and the row must still hold the leading block's
                      410 and the 17 between them — 811 of content, so 835 of container, and 840
                      clears that with a few pixels rather than landing on it. The two move
                      together: reveal the field a moment before the row can hold it and the row
                      breaks, which is the 830–834 band this number replaced. What the field gets is the
                      row's 24px-inset content less the leading block's 410px floor, the tools'
                      288px and two gaps — **plus the 44px the icon gives back**, since the field
                      replaces it rather than joining it. That is 670 of furniture, so a container
                      of 830 leaves about 136px.

                      Both of the first two numbers were too high, and both for the same reason —
                      arithmetic that forgot the icon. 890 and then 860 left 945×457 (a container
                      of 841, and one of the two widths this whole fix started from) below the
                      threshold, still carrying its hole for want of a field it had the room for.

                      **The floor is what makes that threshold hold when a cue is included.** Then
                      the leading block is growing too (`has-[[data-fills]]`), so the two share the
                      slack rather than it all arriving here — at a container of 841 that is about
                      73px each, and a 73px field is the clipped placeholder the threshold exists
                      to avoid. The floor is the same 132 the threshold is derived from, so the
                      field stays readable and the block takes what is left. It cannot overflow: at
                      830 there are 136px to share, which is the floor plus four.

                      Below the threshold this reverts to exactly today's behaviour — the search
                      icon — which is what keeps the change additive: 860 is well clear of the 729
                      where the row wraps, so nothing about the wrap can be disturbed by it. There
                      is still a band just under it where the row ends short, but that slack now
                      sits **after** the tools rather than inside the leading block, which is a
                      toolbar ending rather than a hole between two controls. */}
                  {/* `max-w-[340px]` is the artboard's: past that the field is wider than any
                      fixture name and the row's right end starts to feel unanchored.

                      The field is allowed to give: the container's own node declares `min-w-0`
                      (it carried a `min-w-48` floor while the plain lists' toolbar *wrapped*, and
                      this row had to unpick it — below ~1300px of page width the 192px input
                      overran its flex track and painted its placeholder under the Lit button; the
                      floor went with the wrapping toolbar, since no list toolbar wraps now). The
                      *unfolded* row B has no second line to give it — the folded one wraps, but
                      this arm does not — so the field gives instead — and session 4 replaces it
                      with a search icon at the width where even that stops being enough. */}
                  {/* Two arms of one control. From `@[360px]` the field is on the row; below it
                      the field is a search icon that opens the same node in a popover — the icon
                      arm session 4 promised, and the real answer to the squeeze the old 192px floor
                      caused (the note above). Radix mounts popover content only while it is open, so there is
                      one filter input in the document except during the moment it is being used.

                      **The threshold is `@[360px]`, and two things make it that low.** It was
                      `@[600px]`, then `@[800px]`, and both were about a placeholder: the desk pass
                      found `Filter fixtures…` clipping mid-word at 600 (`PD-FILTER-PLACEHOLDER-CLIP`
                      — ~72px of room behind a 36px search icon for a placeholder needing 96), and
                      800 was the first width that cleared it. That constraint is retired: the
                      programmer's field says `Filter…` at every width
                      (`FIXTURE_FILTER_PLACEHOLDER_SHORT`, since a placeholder cannot switch by
                      container query), which fits behind the icon at the field's 132px floor, and
                      the whole hint rides the field's `title` and `aria-label`. The second thing
                      is the slack rule below. What 360 is: the row's furniture without the field
                      is ~250 (two pills, Lit, Groups, Columns, the gaps and the 24px inset), so 360
                      is the first width with a legible field beside it — and a row narrower than
                      that is the phone's, where the icon arm and its `w-72` popover are the right
                      answer anyway. Above 360 the middle of the row is never a hole.

                      **The field takes the slack before the spacer does.** It is `flex: 999 1 0%`
                      against the spacer's `flex: 1 1 0%` below, so it grows to its 340px cap first
                      and the spacer gets only what the cap leaves. It was `flex-1` like the spacer,
                      and two `flex: 1 1 0%` siblings *split* the free space — so the field was
                      half the width it could be at every width, which is the bug the note beside
                      `ml-auto` in `SelectionBar` records from the other end. That is what left the
                      field too narrow for its placeholder at 600 and 800 in the first place.
                      The folded arm keeps its plain `flex-1`: there the spacer is hidden and the
                      only other elastic thing is the leading block, which grows only when a cue or
                      a Look is included (`has-[[data-fills]]`) and shares the slack with the field
                      by design — see the note above on the 132px floor.

                      The folded row took the icon at **every** width until the hole above it was
                      closed, and the reason was never that a field is wrong there: "wide enough
                      AND short enough" cannot be written as one Tailwind class, because a
                      container query and a media query cannot be ANDed in one. That still holds,
                      and it is why the two arms are split in JS on `leading` — the height half —
                      and then given different container thresholds for the width half, rather than
                      by a third class. It also
                      fixes a real squeeze: the field is `flex-1` and so is the leading block, so on
                      an 852×393 phone the two split the row and the source box — `flex-1` inside a
                      block sharing it with a 230px action bar — collapsed to four pixels. */}
                  <div
                    className={cn(
                      'hidden min-w-0 items-center gap-2',
                      leading
                        ? 'flex-1 @[840px]:flex @[840px]:min-w-[132px]'
                        : 'max-w-[340px] flex-[999_1_0%] @[360px]:flex',
                    )}
                  >
                    {filter}
                  </div>
                  <FilterPopover className={leading ? '@[840px]:hidden' : '@[360px]:hidden'}>
                    {filter}
                  </FilterPopover>
                  {lit}
                  {/* Dropped in the folded arm: the leading block is already `flex-1` there, and two
                      competing `flex-1` siblings is the bug session 2 found on this very row. */}
                  <span className={cn('flex-1', leading && 'hidden')} />
                  <Button
                    variant={grouped ? 'default' : 'outline'}
                    size="sm"
                    className="shrink-0"
                    aria-pressed={grouped}
                    onClick={() => onGroupedChange(!grouped)}
                    title="Show group rows with their members"
                  >
                    <Layers className="size-3.5" />
                    {/* The one control after the divider that was *not* keeping the folded row's
                        icon rule the comment above states, and the fifth hand-rolled copy of an
                      idiom `labelUnlessCompact` already owned. Its `@[800px]` reads row B's width,
                      and a
                        folded row B is the whole grid column — 841px on a 945×457 window — so the
                        word appeared exactly where the row has least to spare. The 54px it cost was
                        the difference between the source box saying `Programmer is empty.` and
                        rendering a 156px bordered rectangle with nothing in it. */}
                    <span className={labelUnlessCompact(!!leading, '@[800px]:inline')}>Groups</span>
                  </Button>
                  {columns}
                  {/* The key, exactly where the 22px footer that normally carries it is not drawn
                      (see `renderFooter`) — narrow, or short. A grid whose tints are *navigational*
                      has to keep them learnable somewhere, so the two conditions are written as
                      two "show" rules over a hidden base rather than as a hide and an un-hide:
                      both set the same `display`, so neither can lose to the other's ordering.

                      **Not drawn in layer scope.** The key explains the ownership tints, and
                      ownership is switched off in layer scope — the engine has no opinion about a
                      Look's stored rows — so there is nothing for it to explain there. It is also
                      what the phone cannot afford: two scope pills, the layer pill, the save
                      state, the search icon, Lit, Groups and Columns are 336px of a 369px row
                      with the key gone, and clipped at the right end with it. */}
                  {!layerScope && (
                    <ScopedKeyPopover className="hidden @max-[600px]:inline-flex [@media(max-height:500px)]:inline-flex" />
                  )}
                </div>
                {/* `Make layer` was this row's right end until session 3 of the space plan moved
                    it onto the rail's Local values row — the row it promotes. See `LocalValuesRow`
                    in `ProgrammerRail` for the one rule that changed with the move. */}
              </SheetPage.Row>
            </div>
            {/* The layer notices keep their padded block. They are prose, not chrome — a sentence
                about what a focused layer will and will not take — and they wrap. `empty:hidden`
                because outside layer scope they render nothing, and an always-on wrapper would
                spend 16px of padding on a page whose budget is the reason this session exists. */}
            <div className="flex flex-col gap-2 px-3 py-2 empty:hidden">
              <LayerRowNotices projectId={projectId} />
            </div>
            {/* Row C: the selection bar, and the templates ride it. Its own `@container`, with
                the queries on the child — the wrapper trap again. See `SelectionBar` below. */}
            <div className="@container">
              <SelectionBar
                projectId={projectId}
                selection={selection}
                cells={cells}
                cellEntryKey={cellEntryKey}
                cellClearKey={cellClearKey}
                templateTargets={templateTargets}
                targetFamilies={targetFamilies}
                targetEmitters={targetEmitters}
                marqueeDragging={marqueeDragging}
              />
            </div>
          </div>
        )}
        renderFooter={({ fixtureCount, selectedCount }) => (
          /* The wrapper's `@container` exists only so the legend can be hidden *by its own
             column's* width: `LegendFooter` declares a container itself, and a container query
             never matches the element that declares one. Below 600 the key is on row B behind a
             button instead, and the 22px this footer costs is a row and a half of fixtures on a
             393px phone. The counts go with it — they are the least of what a phone needs.

             The height clause is the short-height arm, and it is the same 22px for the same
             reason: a landscape phone is 393px tall and this is the one band on it that is a
             *key* rather than the show. `ScopedKeyPopover` carries the same pair of conditions
             the other way round, so the button arrives wherever this footer goes — the key
             moves, it is never simply absent. */
          <div className="@container">
            <ScopedLegend
              className="@max-[600px]:hidden [@media(max-height:500px)]:hidden"
              fixtureCount={fixtureCount}
              selectedCount={selectedCount}
            />
          </div>
        )}
      />
    </div>
  )
}

/**
 * The key that matches what the grid is actually drawing.
 *
 * Layer scope switches the ownership rings off — the engine has no opinion about a Look's stored
 * rows — so leaving the six-colour key underneath would document colours that are not on screen.
 */
/**
 * Which of the two keys the grid's scope wants — the footer legend and the phone's key popover
 * are two renderings of one question, and this is where it is answered.
 *
 * They had the branch each. A third scope kind would have had to be added to both, and a version
 * that reached only one of them would put a footer and a popover on the same page disagreeing
 * about what the cells mean.
 */
function useLayerScopeKey(): boolean {
  return useProgrammerScope()?.kind === 'layer'
}

function ScopedLegend({
  className,
  fixtureCount,
  selectedCount,
}: {
  className?: string
  fixtureCount: number
  selectedCount: number
}) {
  const Legend = useLayerScopeKey() ? LayerLegend : OwnershipLegend
  return (
    <Legend className={className} fixtureCount={fixtureCount} selectedCount={selectedCount} />
  )
}

/**
 * The filter as a search icon, for the phone's arm of row B.
 *
 * It holds the container's *own* filter node rather than a second input, so there is one piece of
 * state and one placeholder however this is drawn — the field the popover opens is the field the
 * row shows a hundred pixels wider. `w-72` because the node carries no floor of its own (it is
 * `min-w-0`, so the row can squeeze it), and a popover is the one place a comfortable fixed width
 * is simply right.
 */
function FilterPopover({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className={cn('shrink-0', className)}
          aria-label="Filter fixtures"
          title={FIXTURE_FILTER_HINT}
        >
          <Search className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-72 p-2">
        {children}
      </PopoverContent>
    </Popover>
  )
}

/**
 * The ownership key behind a button — the phone's answer to the footer legend.
 *
 * Stacked rather than the footer's one line: a popover has room for the long glosses, which is
 * the form the footer only reaches at `@[1100px]`. The swatches are the footer's own components,
 * so it is still styled by the real `ownershipCellClass`.
 *
 * It used to make the same scope swap `ScopedLegend` makes and draw a `LayerKey` under a focused
 * layer. It is not rendered in layer scope at all now (see the note at its call site), so the
 * ownership key is the only one it holds and the layer key was deleted with its last caller; the
 * footer's `LayerLegend` still says what a focused layer's rings mean wherever the footer is
 * drawn.
 */
function ScopedKeyPopover({ className }: { className?: string }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className={cn('shrink-0', className)}
          aria-label="Key to the cell colours"
          title="Key to the cell colours"
        >
          <KeyRound className="size-3.5" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-56 p-3">
        <OwnershipKey />
      </PopoverContent>
    </Popover>
  )
}

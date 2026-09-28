import { useMemo } from 'react'
import { AlertTriangle, CirclePlus, Download, Layers, RefreshCw, Upload } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { formatFamilyList } from '@/lib/attributeFamily'
import { canClaimInSync, resolveProgrammerSource } from '@/lib/programmerSource'
import type { ProgrammerSource } from '@/lib/programmerSource'
import { includedCueId } from '@/lib/includedTarget'
import { useProgrammerSummaryQuery } from '@/store/programmer'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { useLookListQuery } from '@/store/looks'
import { useIncludeBaseline } from './useIncludeBaseline'

const ZONE_LABEL = 'text-[9px] font-bold uppercase tracking-[0.1em]'

/**
 * **The two sourceless states report; they do not instruct.**
 *
 * This box has two jobs — name what Record will write back to, and say how far the programmer has
 * drifted from it — and measured against those, the copy used to be exactly inverted: the two
 * states with *no* source were the wordiest on the row, and the two with a real one the tersest.
 * The empty arm was the only thing on this desk that taught rather than reported, and it did it
 * permanently, in chrome, beside the `Include` button it was describing.
 *
 * So the answer to "what is the source?" with no source is two words, and everything the sentence
 * used to say is on the box's `title`. What that deletes is the whole reason this file was hard:
 * `EMPTY_MID` / `EMPTY_TAIL` / `BUSK_DASH` / `BUSK_TAIL`, the eight nested `@[Npx]` rungs
 * (`PD-SOURCE-TRUNCATION`), the engineered floor under each ladder, `SentenceBox` and its
 * `@container`, and with them the reason the box had to be `flex-1` at all — a container under
 * `inline-size` containment cannot be sized by its contents, so the rungs needed a width handed
 * down from the row. Two words need no rungs, so the box is simply as wide as its words.
 *
 * One rung survives in each arm, and it is the same rung both arms always had at the bottom of
 * their ladder: below the width where the words fit, the box is its glyph or its label alone —
 * never a word sliced in half.
 *
 * **Both numbers are measured, and both have a ceiling as well as a floor** — which is the trap,
 * because a rung is naturally written with slack *above* what it needs and here that is the wrong
 * direction. The block these boxes sit in is **419px** on an 852×393 landscape phone (the figure
 * `ProgrammerActionBar`'s doc comment records, and the arm this fold exists for), so a threshold
 * chosen a comfortable 20px above the need lands *over* 419 and silently takes the words off the
 * one screen they were re-measured for. The first cut did exactly that, at 420 against a need of
 * 404, and lost `No source` on the phone by a single pixel.
 *
 * So: rendered at these styles, `No source` is 58px and the whole box 102px, and the box sits
 * beside the 285px iconic action bar with 17px of divider and gaps between — 404. `@[410px]`
 * clears that by six pixels **and stays under 419**, which is the half that matters: the empty box
 * is what is on screen when the desk is idle, and that is the arm the phone shows.
 *
 * The busking box with a three-digit count is 139px by the same measurement, so it needs 441 —
 * and `@[450px]` therefore serves the **unfolded** row only. Say that plainly, because the
 * arithmetic is not obvious and an earlier version of this note implied the opposite: in the
 * folded arm `ProgrammerGrid` pins the block this box shares at its 410px floor (`flex-initial`
 * under size containment, so it is that width and no other), which leaves the box 108px whatever
 * the window is doing. 139 does not fit in 108 at any width, so there the count never draws and
 * `Busking` carries the state alone.
 *
 * **And the floor cannot rise to admit it.** 410 + 17 + the tools' 288 is 715, against the 724 of
 * content an 852×393 landscape phone has — nine pixels of headroom. A floor of 441 needs 746 and
 * folds that arm onto two lines, which is the row the fold exists to prevent. So this is a real
 * constraint rather than a number to re-tune: at 108px the box chooses between naming the state
 * and giving a count that is *already* on the rail's Local values row and in the programmer tile,
 * and it names the state. The count is still on the `title` and in the `sr-only` sentence.
 */
const EMPTY_STATE = 'No source'
const EMPTY_SENTENCE = 'Programmer is empty. Include a cue or a Look, or start busking.'

const BUSK_STATE = 'Busking'

/**
 * What the programmer is holding, said out loud and permanently — **the left half of row A**.
 *
 * Brief item 4: the included cue used to be named only inside the Update button's tooltip, so an
 * operator four minutes into a busk had no on-screen answer to "will Record overwrite Q4?". It is
 * present in every state — *empty is a state, not an absence*, and "the programmer is empty,
 * Include something" answers a real question.
 *
 * `Update` and `Revert` live here rather than in the action bar for the same reason: they are the
 * two actions whose subject is the thing this names, so they belong *inside* the box rather than
 * among the verbs that act on the rig.
 *
 * **Record is not one of them, and that is `PD-TWO-RECORD-BUTTONS`.** The two states with nothing
 * to Update — busking, and a source that has been deleted — used to offer their own `Record…`
 * here, wired to the same `sheets.openRecord()` with the same no arguments as the action bar's,
 * so row A drew two enabled primary Record buttons about 120px apart and, below `@[800px]`, two
 * identical filled-circle icons. Two controls that are one act is the thing this page keeps
 * refusing (the chevrons, the doors, `useShowBarProps`); the action bar's is the one that stays,
 * because Record is a verb that acts on the rig and because only it carries the destination menu.
 * So these two states simply have no verb, which is correct: neither has anything to *update*,
 * and the box's job is to name the source, not to duplicate the bar.
 *
 * One consequence worth stating, since it is the only reach the deletion actually changed: the
 * bar's Record is `disabled` on an empty programmer, while the deleted-source Record here was
 * unconditionally enabled. So a source deleted with *nothing busked behind it* now offers no
 * Record anywhere — which is the rule the bar already keeps (Record reads the programmer, so it
 * is meaningless when the programmer is empty) finally applying to this state too, rather than a
 * gap. The busking arm never had the question: `resolveProgrammerSource` only answers `busking`
 * when there are entries or programmer FX, which is `hasContent` exactly.
 *
 * **It was a full-width band of its own until the space plan's session 1**, sitting above a
 * second full-width band of verbs. Two bands each spending a line on one sentence is 101px of an
 * 900px screen, and the grid below them is the page. So the strip is now a bordered 32px box at
 * the left of row A, which truncates its *name* before its badges — the badge is the part that
 * changes, the name is the part you can usually still guess. It fills the width it is given only
 * in the two states that have something to fill it with (a cue, a Look); the two sourceless ones
 * are as wide as their words. See `fill` on `Strip`. Nothing it said was
 * deleted: the location line and the long "3 changes not written back" wording appear at
 * `@[1100px]` and ride the *text's* `title` below that — never the box's, for the reason beside
 * `boxTitle` — `Update Q4` shortens to `Update` below
 * `@[800px]`, and Revert becomes its icon below `@[1100px]` with the word on its `aria-label`.
 *
 * **Below `@[600px]` it is the phone's arm** (space plan D8, session 4): the `Editing` label and
 * the cue/Look glyph go, and every verb here is its icon. What is left is **`Q4 · Update ·
 * Revert`** — the number and its two verbs, and nothing else — since the chrome tidy-up
 * (`lighting7/docs/plans/programmer-chrome-design/`). It was `Q4 · name · badge` there, and the
 * arithmetic never allowed it: with a cue included the verbs beside this box are 285px iconic, so
 * on a 393px portrait phone the box got 393 − 24 − 17 − 285 = 67px, and 117 in the folded arm on
 * a landscape one, for content that needs ~200 — Update was clipped to a sliver, on the one
 * screen where it is the only way to write the cue. So below 600 the name is hidden (it is on the
 * `title` and in the `sr-only` sentence) — **unless it is the only identity left**: a cue with no
 * number falls back to its name in `includedTargetParts`, and a Look's families badge is undefined
 * until the Look list arrives, and in either case hiding the name leaves two icon buttons and
 * nothing saying what Update would overwrite. The change count becomes an **amber dot on Update**
 * with the count on Update's tooltip, and the box is 24 + 28 + 28 + 16 + 20 = 116px: the number,
 * two 28px verbs, two gaps and the inset. With the fade trigger's chevron gone below 600 as well
 * (`ProgrammerActionBar`) the row is 116 + 17 + 228 = 361 of 369. A Look keeps its families
 * badge in that arm, since it has no number and the badge is the only thing left naming it. The
 * `Editing` label and the glyph went earlier and for a different reason — they said the *same*
 * thing twice: the label is a word for a state the blue rim already draws, and the glyph is a
 * picture of the `Q4` beside it.
 *
 * The container queried is **row A's**, declared by the wrapper in `ProgrammerPage` — this
 * component must not declare one of its own, or every query here would measure the box rather
 * than the row it has to share (`ProgrammerWorkspace`'s doc comment has the long version of that
 * bug). In the short-height arm row A does not exist and this box leads row B instead, where
 * `ProgrammerGrid` wraps the pair in an `@container` of **its own** — so these queries measure the
 * ~380px the folded row's flex gives the pair, not the ~750px grid column around them, and this
 * box is in its icon arm on an 852×393 landscape phone. That is one step narrower than
 * `PhoneLandscape` draws, and it is measured rather than assumed on purpose: the artboard's action
 * bar is ~155px against this one's ~230, so a container the width of the whole row would have
 * promised `Editing` and `Update` room that this rig's verbs have already taken.
 *
 * Two states the design drew are absent, and `lib/programmerSource.ts` says why: a real
 * "changed on another desk" conflict, and Detach. Neither is reachable without the backend, and
 * both would have had to be faked.
 */
export function ProgrammerSourceStrip({
  projectId,
  onUpdate,
  onRevert,
}: {
  projectId: number
  onUpdate: () => void
  onRevert: () => void
}) {
  const { data: summary } = useProgrammerSummaryQuery()
  const { data: activeEffects } = useActiveEffectsQuery()
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const dirty = useIncludeBaseline()

  const target = summary?.lastIncluded ?? null
  const lookId = target?.kind === 'LOOK' ? target.lookId : null
  // Only to label the Look's families; skipped entirely when a cue (or nothing) is included.
  const { data: looks } = useLookListQuery({ projectId }, { skip: lookId == null })

  const cueId = includedCueId(target)
  const cueLocation = useMemo(() => {
    // `undefined` while the stack list is still in flight, too: an absent list is not evidence the
    // cue was deleted, and `resolveProgrammerSource` reads a `null` here as exactly that claim.
    if (cueId == null || stacks == null) return undefined
    for (const stack of stacks) {
      const index = stack.cues.findIndex((c) => c.id === cueId)
      if (index >= 0) {
        return { stackName: stack.name, position: { index: index + 1, total: stack.cues.length } }
      }
    }
    // Not `undefined`: an explicit null is what tells the resolver the cue has been DELETED, as
    // opposed to not having been looked up.
    return null
  }, [cueId, stacks])

  const lookFamilies = useMemo(() => {
    if (lookId == null) return undefined
    const families = looks?.find((l) => l.id === lookId)?.families
    if (!families?.length) return undefined
    return formatFamilyList(families)
  }, [lookId, looks])

  const source = resolveProgrammerSource({
    target,
    entryCount: summary?.entryCount ?? 0,
    programmerFxCount: activeEffects?.filter((e) => e.programmerOwned).length ?? 0,
    dirty,
    cueLocation,
    lookFamilies,
  })

  if (source.kind === 'empty') {
    return (
      <Strip tone="neutral" fill={false} title={EMPTY_SENTENCE}>
        <CirclePlus className="size-3.5 shrink-0 text-muted-foreground" />
        {/* Shortening this to two words is a **visual** decision, so assistive tech still gets the
            whole sentence — `TruncateStart` makes the same split for the same reason, and this
            file's own rule is that what the box says must never need a hover. A `title` on a plain
            `div` is not that: it is mouse-only and inconsistently exposed, so it serves the
            pointer and this serves everyone else. The visible words are `aria-hidden` so the two
            are not announced as a stutter. */}
        <span className="sr-only">{EMPTY_SENTENCE}</span>
        <span
          aria-hidden="true"
          className="hidden min-w-0 truncate text-xs text-muted-foreground @[410px]:block"
        >
          {EMPTY_STATE}
        </span>
      </Strip>
    )
  }

  if (source.kind === 'busking') {
    const count = `${source.valueCount} value${source.valueCount === 1 ? '' : 's'}`
    // One string for the hover and for assistive tech, built from the same two pieces the visible
    // spans render — so the short form and the long one cannot drift into disagreeing.
    const busking = `${BUSK_STATE} — ${count}, with no source to update`
    return (
      <Strip tone="neutral" fill={false} title={busking}>
        <span className="sr-only">{busking}</span>
        {/* The label is the state's name and never drops; the count is the part said twice — it is
            on the rail's Local values row and in the programmer tile — so it is what goes when the
            box is narrow. That order is unchanged from the ladder this replaced. */}
        <span aria-hidden="true" className={cn(ZONE_LABEL, 'shrink-0 text-muted-foreground')}>
          {BUSK_STATE}
        </span>
        <span
          aria-hidden="true"
          className="hidden min-w-0 truncate text-xs text-muted-foreground @[450px]:block"
        >
          {count}
        </span>
      </Strip>
    )
  }

  if (source.missing) {
    const gone = `The ${source.kind === 'cue' ? 'cue' : 'Look'} you were editing has been deleted.`
    return (
      <Strip tone="warning">
        <AlertTriangle className="size-3.5 shrink-0 text-amber-400" />
        <span className="truncate text-xs text-amber-200" title={gone}>
          {gone}
        </span>
      </Strip>
    )
  }

  const inSync = canClaimInSync(source)
  const label = source.kind === 'cue' ? 'Update' : 'Update Look'
  // The whole state as one sentence, for the widths where the location line is hidden below
  // `@[1100px]`: hover is where it went, so it has to actually be there.
  //
  // It goes on the *text* — the `Editing` label and the name — and never on the box, and that is
  // the difference between it being available and it being a bug. A browser shows an ancestor's
  // native `title` for any descendant that has none, so a `title` on the box would have been
  // inherited by the Update button, which is already inside a Radix `Tooltip`: two tooltip surfaces
  // answering one hover.
  const boxTitle = [
    'Editing',
    source.kind === 'cue' ? source.number : null,
    source.name,
    source.kind === 'cue' ? cueLocationText(source) : null,
  ]
    .filter(Boolean)
    .join(' · ')

  // Whether anything *other than the name* still names the source below `@[600px]`: a cue's number,
  // or a Look's families badge. Neither is guaranteed — `includedTargetParts` falls back to the
  // **name** for a cue with no number (and to `Cue {id}` when it has neither), and `families` is
  // undefined until the Look list arrives — and with the name hidden unconditionally the box was
  // then an Upload glyph and a Revert glyph and nothing at all saying what Update would overwrite,
  // which is the one question this box exists to answer. So the name stays wherever it is the only
  // identity left.
  const namedWithoutName = source.kind === 'cue' ? !!source.number : !!source.families

  return (
    <Strip tone="editing">
      {/* The whole state, for assistive tech, at every width: below `@[600px]` the label and the
          name are both off the screen, and a `title` is mouse-only. The same split the two
          sourceless arms make — which is also why every visible part of it below carries
          `aria-hidden`: the number and the location are in this sentence too, and without it they
          are announced twice. */}
      <span className="sr-only">{boxTitle}</span>
      <span
        aria-hidden="true"
        className={cn(ZONE_LABEL, 'hidden shrink-0 text-blue-300 @[600px]:inline')}
        title={boxTitle}
      >
        Editing
      </span>
      {source.kind === 'look' ? (
        <Layers className="hidden size-3.5 shrink-0 text-blue-300 @[600px]:block" />
      ) : (
        <Download className="hidden size-3.5 shrink-0 text-blue-300 @[600px]:block" />
      )}
      {source.kind === 'cue' && source.number && (
        <span aria-hidden="true" className="shrink-0 font-mono text-sm font-bold">
          {source.number}
        </span>
      )}
      {source.name && (
        <span
          aria-hidden="true"
          className={cn(
            'min-w-0 truncate text-sm font-medium',
            // Hidden on the phone's arm only where `namedWithoutName` says something else is
            // carrying the identity.
            namedWithoutName && 'hidden @[600px]:inline',
          )}
          title={boxTitle}
        >
          {source.name}
        </span>
      )}
      {source.kind === 'look' && source.families && (
        <Badge variant="outline" className="shrink-0 text-[10px]">
          Look · {source.families}
        </Badge>
      )}
      {source.kind === 'cue' && <CueLocation source={source} />}

      <span className="flex-1" />

      <DirtyBadge dirty={source.dirty} inSync={inSync} />

      <Tooltip>
        <TooltipTrigger asChild>
          {/* Wrapped so a disabled button still shows its reason. `relative` for the dot below. */}
          <div className="relative shrink-0">
            <Button
              size="sm"
              className="h-7 @max-[600px]:w-7 @max-[600px]:px-0"
              disabled={inSync}
              onClick={onUpdate}
              // The full label whatever the width: below `@[800px]` the cue number is dropped from
              // the *visible* text and this is the only thing still carrying it.
              aria-label={
                source.kind === 'cue' && source.number ? `${label} ${source.number}` : label
              }
            >
              <Upload className="size-3" />
              <span className="hidden @[600px]:inline">{label}</span>
              {source.kind === 'cue' && source.number && (
                <span className="hidden @[800px]:inline">{source.number}</span>
              )}
            </Button>
            {/* The dirty state below `@[600px]`, where `DirtyBadge` is hidden: an amber dot on
                the verb that clears it. The count is on the tooltip text, so the number is still
                one press away; the badge and its `title` carry it above 600 as before. */}
            {source.dirty != null && source.dirty > 0 && (
              <span
                aria-hidden="true"
                className="absolute -top-0.5 -right-0.5 size-2 rounded-full bg-amber-400 ring-[1.5px] ring-background @[600px]:hidden"
              />
            )}
          </div>
        </TooltipTrigger>
        <TooltipContent>
          {inSync
            ? 'Nothing has changed since Include'
            : source.dirty == null
              ? "This tab didn't see the Include, so it can't count your changes — Update writes whatever changed on the server."
              : `${source.dirty} change${source.dirty === 1 ? '' : 's'} not written back — write them back`}
        </TooltipContent>
      </Tooltip>

      {!inSync && (
        <Button
          size="sm"
          variant="outline"
          // Icon-only below `@[1100px]`: the word rides the `aria-label`, which is also what a
          // screen reader gets at every width, so nothing is lost by the shrink.
          className="h-7 shrink-0 @max-[1100px]:w-7 @max-[1100px]:px-0"
          aria-label="Revert"
          title="Throw away the busk and Include this again"
          onClick={onRevert}
        >
          <RefreshCw className="size-3" />
          <span className="hidden @[1100px]:inline">Revert</span>
        </Button>
      )}
    </Strip>
  )
}

/** "Act 1 · cue 4 of 14" — the reassurance that you are editing the cue you think you are. */
function cueLocationText(source: Extract<ProgrammerSource, { kind: 'cue' }>): string | null {
  const parts = [
    source.stackName,
    source.position ? `cue ${source.position.index} of ${source.position.total}` : null,
  ].filter(Boolean)
  return parts.length === 0 ? null : parts.join(' · ')
}

/**
 * The location, visible only at `@[1100px]`.
 *
 * It was `@[700px]` against the strip's own container; row A shares its width with every verb on
 * the page now, so the threshold is the artboard's — and below it the same text is on the box's
 * `title`, which is where the plan says the sentences go rather than away.
 */
function CueLocation({ source }: { source: Extract<ProgrammerSource, { kind: 'cue' }> }) {
  const text = cueLocationText(source)
  if (text == null) return null
  return (
    // `aria-hidden` like every other visible part of the box: the same text is in the `sr-only`
    // sentence above, and without it the location is announced twice at `@[1100px]`.
    <span
      aria-hidden="true"
      className="hidden shrink-0 truncate text-xs text-blue-300/80 @[1100px]:inline"
    >
      {text}
    </span>
  )
}

/**
 * The change count — **or nothing at all**.
 *
 * `dirty == null` renders no badge rather than a reassuring one. See `canClaimInSync`: a tab that
 * did not watch the Include cannot tell, and "in sync" over unwritten work costs a cue.
 *
 * Hidden below `@[600px]` in both arms — the phone's box is `Q4 · Update · Revert` and nothing
 * else. The dirty state is an amber dot on Update there, with the count on its tooltip; "in sync"
 * is Update being disabled, which it already is.
 */
function DirtyBadge({ dirty, inSync }: { dirty: number | null; inSync: boolean }) {
  if (inSync) {
    return (
      <span className="hidden shrink-0 rounded-full border border-green-900 bg-green-950/40 px-2 py-px text-[10px] font-medium text-green-400 @[600px]:inline">
        in sync
      </span>
    )
  }
  if (dirty == null || dirty === 0) return null
  const plural = dirty === 1 ? '' : 's'
  return (
    <span
      // The long wording is the artboard's, and it only fits at `@[1100px]`; the short form keeps
      // the number, which is the part that changes, and the `title` keeps the sentence.
      title={`${dirty} change${plural} not written back`}
      className="hidden shrink-0 items-center gap-1.5 rounded-full border border-amber-900 bg-amber-950/40 px-2 py-px text-[10px] font-medium text-amber-300 @[600px]:flex"
    >
      <span className="size-1.5 rounded-full bg-amber-400" />
      {dirty} change{plural}
      <span className="hidden @[1100px]:inline">not written back</span>
    </span>
  )
}

/**
 * The box itself: a bordered 32px control that takes the left of row A.
 *
 * It takes a `title` only in the two arms that pass one — the sourceless pair, whose visible words
 * abbreviate it and which hold nothing interactive to inherit it. The three arms with a source put
 * their hover on the span it belongs to; the note beside `boxTitle` above says why that is a rule
 * rather than a preference.
 *
 * It was a full-width band with a bottom border. Session 1 makes it a peer of the verbs beside it,
 * so the tone that used to wash a whole row now rims a box — and `fill` with `overflow-hidden` and
 * `truncate` on the name is what lets the badges and the two buttons keep their width when the
 * name is long, which is the priority the plan sets ("truncates its name before its badges").
 *
 * No `@container` here: row A declares it, and this box is one of the things being measured.
 */
function Strip({
  tone,
  fill = true,
  title,
  children,
}: {
  tone: 'neutral' | 'editing' | 'warning'
  /**
   * Whether the box takes the row's spare width.
   *
   * True by default, and so for all three arms that have a source to talk about — a cue, a Look,
   * and the one whose source has been **deleted**, which needs the room most of all because its
   * sentence is the longest thing this box ever says. False only for the two sourceless arms:
   * they say two words, so `flex-initial` makes the box as wide as those words and no wider —
   * which is what the `ml-auto` on the divider in `ProgrammerPage`'s `rowA` then hands to the
   * verbs beside it. Count the *arms*, not the four `ProgrammerSource` kinds: `missing` splits
   * off cue and look, and it is the one an "exactly two and two" reading forgets.
   */
  fill?: boolean
  /**
   * The whole state as one sentence, for the arms whose visible words are an abbreviation of it.
   *
   * **Only safe on an arm with no interactive descendant**, which is why the two states with a
   * source pass their hover down to the text instead: a browser shows an ancestor's native
   * `title` for any descendant that has none, so a `title` here would be inherited by `Update`,
   * which already sits inside a Radix `Tooltip` — two tooltip surfaces answering one hover.
   */
  title?: string
  children: React.ReactNode
}) {
  return (
    <div
      title={title}
      // The one thing outside this component that has to know which way `fill` went. The block
      // this box sits in must grow when the box can use the room and stand still when it cannot —
      // and a parent cannot read a child's props, so it reads this instead, through `:has()`.
      // Without it the block grows in every state and parks the row's whole slack behind the
      // verbs, which is the hole this attribute exists to close.
      data-fills={fill ? '' : undefined}
      className={cn(
        'flex h-8 min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2.5',
        fill ? 'flex-1' : 'flex-initial',
        tone === 'editing' && 'border-blue-900/70 bg-blue-950/30',
        tone === 'warning' && 'border-amber-800 bg-amber-950/40',
        tone === 'neutral' && 'bg-card/50',
      )}
    >
      {children}
    </div>
  )
}

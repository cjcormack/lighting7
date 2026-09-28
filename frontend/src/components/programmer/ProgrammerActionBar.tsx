import { type ReactNode } from 'react'
import { ChevronDown, Circle, Download, Eraser, EyeOff, Layers, Plus, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { setProgrammerFade, useProgrammerFade } from '@/lib/programmerFade'
import {
  programmerClearAll,
  programmerSetBlind,
  useProgrammerSummaryQuery,
} from '@/store/programmer'
import { useIsDeskConnected } from '@/store/status'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { includedCueId, includedTargetParts } from '@/lib/includedTarget'
import { useProgrammerSheets } from './ProgrammerSheets'

/** Fade options for Clear and for entering/leaving Blind, in milliseconds. */
const FADE_OPTIONS = [
  { value: '0', label: 'Snap' },
  { value: '500', label: '0.5s' },
  { value: '1000', label: '1s' },
  { value: '2000', label: '2s' },
  { value: '3000', label: '3s' },
]


/**
 * The programmer's verbs — **the right half of row A**.
 *
 * Brief item 2: `Clear`, fade, `Blind`, `Record`, `Record look`, `Include` and `Update` used to sit
 * in one row as seven identical `variant="outline" size="sm"` peers, so nothing distinguished what
 * *stages* from what *writes*, and "Record" and "Record look" read as a pair when they are two
 * destinations for one act. Three zones under `STAGE · LOAD · SAVE` labels fixed that, and cost a
 * whole extra text line across the page to do it.
 *
 * **The zones are gone, and the labels with them** (`ActionZone` and `Divider` are deleted).
 * Session 1 of the space plan gives the grid the page: this bar shares one 40px row with the
 * source box rather than owning a 68px band of its own, so a 9px label above every control is
 * 20px of every screen spent on a word. Nothing they said was deleted — each label now rides the
 * control it introduced, as that control's hover text:
 *
 *  - **Stage** — leading Clear's *Radix* tooltip rather than a native `title`, because Clear is the
 *    one control here already wrapped in a `TooltipTrigger` and two tooltip mechanisms answering
 *    one hover is a bug, not two explanations. It changes what the rig is doing right now, and
 *    **Blind is its second control**, beside the fade they share. Blind has been in three places:
 *    here, then from session 2b in the `ShowBar` beside blackout (one control in one place on every
 *    view *with* a bar), and then — after the space plan's session 5 took the bar off this page —
 *    nowhere on the programmer at all, which a desk pass found unliveable (`PD-BLIND-ON-PROGRAMMER`).
 *    The answer taken was not to bring the bar back but to notice that Blind was never show
 *    chrome: `ProgrammerSummary.blind` is a programmer fact, so the one toggle is here and every
 *    other view *reports* it through `ProgrammerIndicator`. **This is the only Blind control in the
 *    app**, and `useShowBarProps` deliberately supplies none — it is not a second toggle, and
 *    adding one back to the bar for any host would be the split session 2b ended, from the other
 *    side.
 *  - **Load** — a native `title` on Include, which is not wrapped: the only way in, and the only
 *    control never disabled.
 *  - **Save** — the same, on Record: one primary button with a destination menu, unchanged.
 *
 * `Update` is not here at all — it lives inside the source box, beside the thing it writes to.
 *
 * `sheetControls` is gone too: Groups and Columns are the *grid's* tools, not the programmer's
 * verbs, and they moved to row B where the filter already was.
 *
 * Below `@[800px]` Clear keeps its fade segment and loses its word, and Blind, Include and Record
 * become their icons; every one of those carries an `aria-label` so the shrink costs a sighted
 * operator a word and a screen reader nothing. Nothing collapses into an overflow kebab — the old
 * bar hid its last four buttons behind a `MoreHorizontal` below `sm`, which put the entire point
 * of the programmer one tap further away on the surface most likely to be used standing up.
 *
 * **The phone's icon arm (space plan D8) needs one thing here, and it is not a word.** Session 4
 * puts rows A and B on icons below `@[600px]`, and 600 is inside the band this bar is already
 * iconic in — the verb that had to change was `Update`, in the source box next door. What the
 * chrome tidy-up added below 600 is the fade trigger dropping its **chevron**, and only that: the
 * value stays, because the fade has to be read before Clear is pressed. That takes the trigger
 * from 86 to 48px and the bar from 285 to 247, and those 38px are what pay for the source box's
 * `Q4 · Update · Revert` on a portrait phone (`ProgrammerSourceStrip` has the row's arithmetic).
 *
 * The container queried is **row A's**, declared by the wrapper in `ProgrammerPage`; this
 * component must not declare one of its own, for the reason `ProgrammerWorkspace` documents. In
 * the short-height arm there is no row A and this pair leads row B instead, inside an `@container`
 * `ProgrammerGrid` puts around the two of them — so the queries here measure the ~420px the folded
 * row gives the pair rather than the row's own ~750px, and these four stay icons on an 852×393
 * phone by measurement rather than by luck. Measured with Blind back in the bar
 * (`PD-BLIND-ON-PROGRAMMER`): the bar is 285px iconic (247 below `@[600px]`, once the fade's
 * chevron goes) and the leading container 419px; the first fixture row still lands at the same y
 * it did before. The source box took the remaining 117px
 * back when it filled in every state, and it no longer does: with nothing included it is as wide
 * as its words (102px for `No source`) and the rest goes to the row's filter field. So 117 is the
 * ceiling that arithmetic leaves it rather than the width it takes. The two figures that still
 * hold unconditionally are this bar's 285 and the container's 419 — `ProgrammerGrid`'s floor for
 * the pair is derived from them.
 */
export function ProgrammerActionBar({ projectId }: { projectId: number }) {
  const { data: summary } = useProgrammerSummaryQuery()
  const { data: activeEffects } = useActiveEffectsQuery()
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const fadeMs = useProgrammerFade()
  const sheets = useProgrammerSheets()
  // Blind is the one control here whose write is gated on the socket. `programmer.setBlind` is a
  // fire-and-forget WS op on a server-owned flag, so a press while the socket is down neither
  // reaches the rig nor moves the button — it just looks broken. The button stays visible (blind is
  // state the operator must keep reading) and only stops taking the press. Clear is a WS op too and
  // is not gated, which predates this; that is its call, not Blind's.
  const deskConnected = useIsDeskConnected()

  const entryCount = summary?.entryCount ?? 0
  const blind = summary?.blind ?? false
  const target = summary?.lastIncluded ?? null
  // The picker's value, subscribed: this component owns the picker, so it re-renders on every move
  // anyway, and Clear and Blind reading one variable is what keeps them fading by the same amount.
  const fade = Number(fadeMs) || 0

  // Clear releases programmer values *and* programmer-band FX, and the two are independent:
  // applying a busking effect creates a band FX with no value entry behind it. Gating the button on
  // the entry count alone would leave the documented escape hatch disabled in exactly the case an
  // operator most needs it.
  const programmerFxCount = activeEffects?.filter((e) => e.programmerOwned).length ?? 0
  const hasSomethingToClear = entryCount > 0 || programmerFxCount > 0

  // Record reads the programmer, so it is meaningless when the programmer is empty. Include is
  // not: it is how you *fill* the programmer.
  const hasContent = entryCount > 0 || programmerFxCount > 0

  const cueId = includedCueId(target)
  const includedCue = target?.kind === 'CUE' ? includedTargetParts(target) : null
  const includedStack =
    target?.kind === 'CUE' && target.cueStackId != null
      ? stacks?.find((s) => s.id === target.cueStackId)
      : undefined

  return (
    <div className="flex shrink-0 items-center gap-2">
      <Tooltip>
        <TooltipTrigger asChild>
          {/* Wrapped so the disabled state can still explain itself. */}
          <div className="inline-flex h-8 items-stretch overflow-hidden rounded-md border">
            <button
              type="button"
              disabled={!hasSomethingToClear}
              onClick={() => programmerClearAll(fade)}
              aria-label="Clear"
              className="inline-flex items-center gap-1.5 px-2.5 text-xs font-medium transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
            >
              <Eraser className="size-3.5" />
              <span className="hidden @[800px]:inline">Clear</span>
            </button>
            <Select value={fadeMs} onValueChange={setProgrammerFade}>
              <SelectTrigger
                size="sm"
                aria-label="Fade time"
                // Wide enough for "Snap" beside the chevron at the trigger's own padding; at 72px
                // the longest label clipped to "Sna". The fade is the one thing on this row that
                // has to be *read* before Clear is pressed, so the phone's arm keeps the value and
                // drops only the chevron: 48px centred with no padding holds the four mono
                // characters of "Snap" (~29px) with room either side, where 64px with `px-2` and
                // the chevron still in it clipped to "Sna" — which is why the first cut had no
                // narrow arm at all. 86 → 48 is what pays for the source box's phone arm.
                className="h-8 w-[86px] rounded-none border-0 border-l bg-muted/40 font-mono text-xs focus-visible:ring-0 @max-[600px]:w-12 @max-[600px]:justify-center @max-[600px]:px-0 @max-[600px]:[&_svg]:hidden"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {FADE_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </TooltipTrigger>
        {/* The retired **Stage** zone label leads this tooltip rather than sitting on the button
            as a native `title`: this button is the one control here already inside a
            `TooltipTrigger`, and a `title` beside it means the browser's own balloon and Radix's
            floating card both answer one hover. Include and Record carry theirs as `title`s
            because neither is wrapped. */}
        <TooltipContent>
          {'Stage — '}
          {!hasSomethingToClear
            ? 'the programmer is empty'
            : [
                'Release',
                entryCount > 0
                  ? `${entryCount} programmer value${entryCount === 1 ? '' : 's'}`
                  : null,
                entryCount > 0 && programmerFxCount > 0 ? 'and' : null,
                programmerFxCount > 0 ? `${programmerFxCount} programmer FX` : null,
                fade > 0 ? `over ${fade / 1000}s` : null,
              ]
                .filter(Boolean)
                .join(' ')}
        </TooltipContent>
      </Tooltip>

      {/* Blind: the programmer gated out of the stage output. A native `title` like Include and
          Record, because it is not wrapped in a `TooltipTrigger`; every arm of it leads with the
          Stage label, as Clear's tooltip does. Amber when on, in the SAME classes the
          `ProgrammerIndicator` in the app header uses for the same state — theme-paired, because
          light mode is a real arm here and `amber-300` on a light card is unreadable; that badge is
          the reporter, this is the control. The glyph is fixed: state is `aria-pressed` and the
          wash, as it was on this control before session 2b. It enters and leaves by the fade
          beside Clear. */}
      <Button
        variant="outline"
        size="sm"
        disabled={!deskConnected}
        aria-pressed={blind}
        aria-label="Blind"
        title={
          !deskConnected
            ? `Stage — Blind: ${DESK_OFFLINE_LABEL}, so it cannot be changed`
            : blind
              ? 'Stage — Blind is on: programmer values are gated out of the stage output'
              : 'Stage — Blind: edit without the rig showing it'
        }
        onClick={() => programmerSetBlind(!blind, fade)}
        className={cn(
          blind &&
            'border-amber-500/60 bg-amber-500/15 text-amber-700 hover:bg-amber-500/25 hover:text-amber-800 dark:text-amber-300 dark:hover:text-amber-200',
        )}
      >
        <EyeOff className="size-3.5" />
        <span className="hidden @[800px]:inline">Blind</span>
      </Button>

      <Button
        variant="outline"
        size="sm"
        onClick={sheets.openInclude}
        title="Load"
        aria-label="Include…"
      >
        <Download className="size-3.5" />
        <span className="hidden @[800px]:inline">Include…</span>
      </Button>

      <div className="inline-flex h-8 shrink-0 items-stretch overflow-hidden rounded-md">
        <Button
          size="sm"
          disabled={!hasContent}
          onClick={() => sheets.openRecord()}
          title="Save"
          aria-label="Record"
          className="rounded-none px-3 font-semibold"
        >
          <Circle className="size-3 fill-current" />
          <span className="hidden @[800px]:inline">Record</span>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              size="sm"
              disabled={!hasContent}
              aria-label="Record destination"
              className="rounded-none border-l border-primary-foreground/25 px-1.5"
            >
              <ChevronDown className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-[238px]">
            <DropdownMenuLabel className="text-[9px] font-bold uppercase tracking-[0.1em] text-muted-foreground">
              Write {entryCount} value{entryCount === 1 ? '' : 's'} into
            </DropdownMenuLabel>
            {cueId != null && (
              <MenuItem
                icon={<Upload className="size-3.5" />}
                title={[includedCue?.number, includedCue?.name].filter(Boolean).join(' ')}
                sub="Update the cue you are editing"
                onSelect={() =>
                  sheets.openRecord({ targetCueId: cueId, targetCueName: includedCue?.name })
                }
              />
            )}
            {includedStack && (
              <MenuItem
                icon={<Plus className="size-3.5" />}
                title={`A new cue after ${includedCue?.number ?? includedCue?.name ?? 'this one'}`}
                // NOT "becomes Q4.5": the server assigns the number, and `lib/cueNumber.ts` has
                // no between-two-numbers arithmetic. Predicting one the server then ignores is
                // worse than not predicting.
                sub={`${includedStack.name} · appended`}
                onSelect={() => sheets.openRecord({ defaultCueStackId: includedStack.id })}
              />
            )}
            <MenuItem
              icon={<Layers className="size-3.5" />}
              title="A new Look"
              sub="Names its own fixtures"
              onSelect={sheets.openRecordLook}
            />
            <DropdownMenuSeparator />
            <MenuItem
              icon={<Circle className="size-3.5" />}
              title="An existing cue…"
              sub="Pick from any stack"
              onSelect={() => sheets.openRecord()}
            />
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}

function MenuItem({
  icon,
  title,
  sub,
  onSelect,
}: {
  icon: ReactNode
  title: string
  sub: string
  onSelect: () => void
}) {
  return (
    <DropdownMenuItem onSelect={onSelect} className="flex items-start gap-2">
      <span className="mt-0.5 text-muted-foreground">{icon}</span>
      <span className="flex min-w-0 flex-col">
        <span className="truncate text-xs font-medium">{title}</span>
        <span className="truncate text-[10.5px] text-muted-foreground">{sub}</span>
      </span>
    </DropdownMenuItem>
  )
}

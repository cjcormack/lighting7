import { memo } from 'react'
import { ArrowRight, Play } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { UNLOCKED_WARNING_CLASS } from '@/lib/lockChrome'
import { useFadeRemainMs } from '@/hooks/useAnimatedProgress'
import type { RunnerAnimSpan } from '@/store/runnerSlice'
import { ProgrammerIndicator } from './ProgrammerIndicator'
import { SpeedMasters } from './SpeedMasters'

interface ShowBarProps {
  /** Leading "current stack" segment. Rendered only when non-null — Run passes it only when
   *  the stack tabs are hidden (single stack); Program + Prompt Book always pass it. */
  stackName?: string | null
  dbo: boolean
  onDbo: () => void
  /** The cue currently outputting on stage. */
  activeNumber: string | null
  activeName: string | null
  /** The cue queued to fire on the next GO. */
  standbyNumber: string | null
  standbyName: string | null
  /**
   * The active cue's fade *descriptor* (drives the amber FADING badge) — write-once per
   * transition, never a per-frame value. The bar runs its own ~10 Hz countdown from it, so a host
   * re-rendering at frame rate hands the memo below identical props and the chrome sits out the
   * fade. Do not swap this back to a `fadeRemainMs` number: that is what had `SpeedMasters` and
   * every tile here reconciling 60×/s during every fade.
   */
  fade: RunnerAnimSpan | null
  onGo: () => void
  onBack: () => void
  /** Disables + mutes BACK/GO (e.g. Prompt Book when the operator can't edit). Default false. */
  goDisabled?: boolean
  /**
   * The on-screen `space`/`⌫` keyboard hint. Default false.
   *
   * Only shown where those keys actually act: the merged Show view binds them while **locked** and
   * withholds them while unlocked, so the hint tracks the lock rather than the view.
   */
  showShortcuts?: boolean
  /**
   * A running show is unlocked. Washes the bar amber to match the header above it — the chrome has
   * to tint as one band, or it reads as stripes.
   */
  unlockedWarning?: boolean
}

/**
 * Universal "show bar" (Row 3) shared across the live-show views:
 * BLACKOUT · speed masters · programmer · active → Next · BACK · GO.
 * GO is the largest, most prominent affordance. It reflects show state, not mode state, so it
 * renders identically in Show and the Prompt Book — the two hosts since the busk-chrome plan's
 * session A took the bar off the busk view for its side sheet's Show tab (`useShowBarProps`).
 *
 * **There is no Blind tile, and there must not be one for any host.** From session 2b to
 * `PD-BLIND-ON-PROGRAMMER` a BLIND tile sat beside blackout, drawn under `{onBlind && …}` with
 * `useShowBarProps` supplying the press to every host — and the programmer, which draws no bar since
 * the space plan's session 5, had nowhere to press it. Blind is a programmer fact, so the toggle is
 * the programmer's action bar's now, and this bar *reports* it through the `ProgrammerIndicator`
 * below, which draws its amber badge whenever the programmer is blind. Do not reintroduce a tile
 * for one host: one control in one place on the programmer and another here is the drift
 * `useShowBarProps` exists to prevent, and it is exactly what that tile was the first time round.
 *
 * ## The rungs
 *
 * Collapse is driven by the bar's OWN width via container queries (`@container` + `@[NNpx]:` and
 * its exact complement `@max-[NNpx]:`), not the viewport — the app sidebar insets the content
 * region, so viewport width ≠ content width. Four rungs on three numbers:
 *
 * | Width      | Masters                        | Rows | GO                        |
 * |------------|--------------------------------|------|---------------------------|
 * | ≥1000      | a tile each, named*            | 1    | `min-w-[120px]`           |
 * | 700–1000   | one railed tile                | 1    | `min-w-[100px]`           |
 * | 440–700    | one railed tile, compact       | 2    | `flex-1`, h-10            |
 * | <440       | a chip, no `+n`                | 1    | `w-[84px]`, h-11          |
 *
 * \* The masters column is the one thing here that is not purely a width decision: a tile each is
 * only affordable if the bank is small, so `SpeedMasters` raises its own threshold with the count
 * and falls back to the railed tile. See `ARMS.shared` — the bar's ladder; the speed-master
 * overview panel has its own, because it owns its row and none of the widths above apply to it.
 *
 * **The bar wraps rather than deleting.** It used to hide the live-state block below 560px, and
 * four *width-adding* rules fired at that same boundary in the other direction — so in the 560–900
 * band roughly 470px of `shrink-0` tiles left the `flex-1` live block nothing, and because every
 * child still visible inside it was itself `shrink-0` the cue numbers spilled out of its border.
 * Now nothing is gated on the bar being wide: the transport takes `basis-full` below 700 and drops
 * onto its own line, which is deterministic rather than hoping items wrap nicely, and the live
 * block cannot wrap at all because its `flex-1 basis-0` hypothetical size is zero. `overflow-hidden`
 * makes it clip rather than spill in the tightest band. Cue *numbers* stay `shrink-0` — a truncated
 * Q number is worse than a truncated name, so names go first.
 *
 * GO gets **wider** as the bar narrows, which is the right way round for a control pressed in the
 * dark: in the 440–700 band it is a `flex-1` item sharing a line with nothing but BACK.
 *
 * **The bottom rung is one row again, and that is a rung added rather than a fallback removed**
 * (space plan D8). It used to be the 440–700 arm plus a bigger GO — two lines, 118px of an 852px
 * phone, before a single fixture. Now everything below 440 shrinks to its chip size instead: the
 * DBO tile centres its initials with no word above them, `SpeedMastersChip` drops its `+n`, the
 * live block says `Q4 → Q5` and nothing else (the pulse, both names and the stack are already
 * gated above it), BACK is its glyph, and GO is a fixed 84×44 — 44px of controls in a 56px band.
 * The `basis-full` transport line is untouched in the 440–700 band, which is where the ladder's
 * own history says it has to stay: the bar wraps rather than deleting, and a rung is added to the
 * bottom of the ladder rather than taken out of the middle.
 *
 * Two thresholds here are deliberately not rungs. `ProgrammerIndicator` queries `@[760px]`, which is
 * the *app header's* number (it is shared with `connection.tsx`); it lands mid-band here and that is
 * tolerated rather than re-owned for one caller's sake. And the kbd hint's `@[1100px]` asks a
 * different question in kind — "is there slack left over" rather than "which rung" — so folding it
 * into 1000 would put it in the band where the live block is tightest.
 */
export const ShowBar = memo(function ShowBar({
  stackName,
  dbo,
  onDbo,
  activeNumber,
  activeName,
  standbyNumber,
  standbyName,
  fade,
  onGo,
  onBack,
  goDisabled = false,
  showShortcuts = false,
  unlockedWarning = false,
}: ShowBarProps) {
  // The one frame-rate-adjacent thing in the bar, kept at the 10 Hz the 0.1 s readout can show.
  // Only this component re-renders on its tick; the memo above keeps the host's per-frame renders
  // out, and SpeedMasters' own memo keeps the masters out of this tick.
  const fadeRemainMs = useFadeRemainMs(fade)
  const isFading = fadeRemainMs != null && fadeRemainMs > 0

  return (
    // **The wrapper exists so the bar can query its own width, and it is not decorative.**
    // A container query resolves against the nearest *ancestor* query container and never against
    // the element that declares one — an element sizing itself from a query on its own size would
    // be circular, so the spec excludes it. This bar declared `@container` on the same element as
    // `@[440px]:gap-2 @[440px]:px-4 @[440px]:py-2` and has no container ancestor, so for as long as
    // those three classes have existed they have matched nothing: the bar drew at its `gap-1.5
    // px-2 py-1.5` base at every width, measured 8px/6px/6px at 1440 as well as at 393. It is the
    // trap `ProgrammerWorkspace`'s doc comment records — `@container` is a wrapper, and the queried
    // classes go on its child — and this was the only element in the repo still making it.
    //
    // `@container` stays on the bar as well, deliberately. The bar's own three classes now query
    // the wrapper; everything *inside* keeps querying the bar, exactly as before, so not one rung
    // moves for a reason of its own. What does move is that the bar now really does take `px-4`
    // above 440, which shrinks its content box — and a container query measures the content box —
    // so every descendant threshold sits 32px later in terms of the bar's outer width than it did
    // while the padding was silently absent. That is the arithmetic the ladder was written for; it
    // is only now being applied — the query should ask how much room the bar's *contents* have,
    // which is the content box, so `@container` belongs on both elements rather than the wrapper
    // alone.
    //
    // Two 32px bands therefore take the lower rung than their outer width suggests: 440–472 outer
    // draws the phone rung, and 700–732 outer puts the transport on its own line. Measured across
    // 393 / 470 / 600 / 730 / 788 / 966 / 1376 of bar width — one row at all but 600 and 730, the
    // live block never below 69px, and nothing spilling its border at any of them. That last check
    // is the one that matters: the 560px collision this ladder exists to prevent showed up as
    // `shrink-0` children escaping the live block's border, not as a wrapped line.
    <div className="@container">
      <div
        className={cn(
          '@container flex flex-wrap items-stretch gap-1.5 border-b px-2 py-1.5 transition-colors @[440px]:gap-2 @[440px]:px-4 @[440px]:py-2',
          unlockedWarning && UNLOCKED_WARNING_CLASS,
        )}
      >
        {/* DBO tile. Tiles use `justify-start` (not `justify-center`) so the labels share the same
            y-baseline regardless of value font size. It steps its own chrome down the rungs rather
            than swapping to a second element. */}
        <button
          type="button"
          onClick={onDbo}
          aria-pressed={dbo}
          title="Toggle blackout"
          className={cn(
            'flex shrink-0 flex-col items-start justify-center gap-px rounded-md border px-2 py-1 transition-colors @[440px]:justify-start @[440px]:px-2.5 @[700px]:px-3 @[700px]:py-1.5',
            'bg-card hover:bg-muted/40',
            dbo && 'border-red-700 bg-red-950/40 hover:bg-red-950/50 shadow-[0_0_12px_rgba(239,68,68,0.25)]',
          )}
        >
          <span
            className={cn(
              'hidden text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground @[440px]:block',
              dbo && 'text-red-300',
            )}
          >
            Blackout
          </span>
          <span
            className={cn(
              'font-mono text-[11px] font-bold leading-none tracking-wider @[440px]:text-base @[700px]:text-lg',
              dbo ? 'text-red-300' : 'text-foreground',
            )}
          >
            DBO
          </span>
        </button>

        {/* Note for whoever wires blackout up: DBO above is local state with no side effect, so it
            is a tile that does nothing (`FU-FE-DBO-INERT`). It used to have a working BLIND tile
            beside it, which made the two read as peers; that tile is the programmer's action bar's
            now, so DBO stands alone — still inert, just no longer beside something that works. */}

        {/* Every speed master, master 1 included. Self-contained; picks its own arm from the width
            above. The ShowBar used to own an M1 readout beside this, which is the split that made the
            560px band unwinnable. */}
        <SpeedMasters />

        {/* Programmer tile — renders itself only when the programmer holds something or blind is
            engaged, so it costs no width during a clean show. It reads its own state, which is why it
            takes no props from here. It is a direct child rather than living in a wrapper div: a
            wrapper always rendered, and so always ate a gap, even when the indicator drew nothing.

            This is the bar's blind signal: with no BLIND tile, the indicator's amber badge is how a
            view with a bar learns the programmer is blind, and the press is on the programmer. */}
        {/* Gone on the bottom rung, and it is the one thing D8's enumerated row leaves out. At 393px
            the tile, the tempo chip and an 84px GO leave the live block about 70px, and this tile
            is 50 of them — so keeping it is a two-line bar, which is the whole of what that rung
            exists to remove. It is also the item that costs least there: blind is still reported by
            the app header's own mount of this indicator one row up, the value count is on the
            Programmer page this links to, and the link itself is the view switcher beside it.
            Nothing else on the bar is allowed to go this way. */}
        <ProgrammerIndicator className="px-2.5 py-2 @max-[440px]:hidden" />

        {/* Live state — flexes to fill, and is never hidden. `overflow-hidden` is load-bearing: every
            child below is `shrink-0`, so without it they escape the border rather than clipping. */}
        <div className="flex min-w-0 flex-1 items-center justify-center gap-1.5 overflow-hidden rounded-md border bg-card px-2 @[440px]:justify-start @[440px]:gap-2 @[440px]:px-3 @[440px]:py-1.5 @[700px]:gap-3.5">
          {stackName && (
            <>
              <span className="hidden max-w-[160px] shrink-0 truncate text-sm font-medium @[700px]:block">
                {stackName}
              </span>
              <span className="hidden shrink-0 text-muted-foreground/40 @[700px]:block">·</span>
            </>
          )}
          {activeNumber || activeName ? (
            <>
              <div className="flex min-w-0 items-center gap-2">
                <span
                  className="hidden size-[22px] shrink-0 place-items-center rounded-full border border-green-900 bg-green-950 text-green-400 @[440px]:grid"
                  style={{ animation: 'r-live-pulse 1.6s ease-in-out infinite' }}
                >
                  <Play className="size-2.5 fill-current" strokeWidth={0} />
                </span>
                {activeNumber && (
                  <span className="shrink-0 font-mono text-sm font-bold text-green-400">
                    {activeNumber}
                  </span>
                )}
                <span
                  className={cn(
                    'hidden min-w-0 truncate text-sm @[700px]:block',
                    isFading ? 'font-medium text-amber-400' : 'font-medium text-foreground',
                  )}
                >
                  {activeName ?? 'No cue running'}
                </span>
                {isFading && (
                  <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-amber-900 bg-amber-950/40 px-2 py-px font-mono text-[10px] font-bold uppercase tracking-[0.08em] text-amber-400">
                    <span
                      className="size-1.5 rounded-full bg-amber-400"
                      style={{ animation: 'r-fade-pulse 0.9s ease-in-out infinite' }}
                    />
                    <span className="hidden @[700px]:inline">FADING · </span>
                    {(fadeRemainMs! / 1000).toFixed(1)}s
                  </span>
                )}
              </div>

              <ArrowRight className="size-3.5 shrink-0 text-muted-foreground" />

              <div className="flex min-w-0 items-center gap-2">
                <span className="hidden shrink-0 text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground @[700px]:inline">
                  Next
                </span>
                {standbyNumber && (
                  <span className="shrink-0 font-mono text-xs font-bold text-blue-400">
                    {standbyNumber}
                  </span>
                )}
                {/* The standby NAME is the last thing to arrive: at 700–1000 the active cue keeps its
                    name and this one does not, which is the mockup's rung 2. */}
                <span className="hidden truncate text-xs text-muted-foreground @[1000px]:inline">
                  {standbyName ?? 'end of stack'}
                </span>
              </div>
            </>
          ) : (
            <span className="truncate text-sm text-muted-foreground">No cue running</span>
          )}

          <span className="hidden flex-1 @[440px]:block" />

          {showShortcuts && (
            <span className="hidden shrink-0 items-center gap-1.5 font-mono text-[10px] text-muted-foreground @[1100px]:inline-flex">
              <kbd className="rounded border bg-muted/50 px-1.5 py-px text-[9.5px]">space</kbd>
              go
              <kbd className="ml-1 rounded border bg-muted/50 px-1.5 py-px text-[9.5px]">⌫</kbd>
              back
            </span>
          )}
        </div>

        {/* Transport. `basis-full` in the 440–700 band puts it on its own line deterministically, and
            GO takes the whole width that buys — the inversion the ladder exists for. Below 440 it
            comes back onto the one row at a fixed 84×44, which is the bottom rung (D8): a phone has
            no line to spare, and GO at 84px is still the widest thing on the bar. `h-auto` above 700
            lets it match the tile heights, overriding Button's default h-10. */}
        <div className="flex shrink-0 basis-auto items-stretch gap-2 @[440px]:basis-full @[700px]:ml-auto @[700px]:basis-auto">
          <Button
            variant="outline"
            onClick={onBack}
            disabled={goDisabled}
            aria-label="Back"
            className="h-11 px-2.5 text-sm font-semibold uppercase tracking-wider @[440px]:h-10 @[440px]:px-4 @[700px]:h-auto @[1000px]:px-5"
          >
            <span aria-hidden="true">◀</span>
            <span className="hidden @[440px]:inline">BACK</span>
          </Button>
          <Button
            onClick={onGo}
            disabled={goDisabled}
            className={cn(
              'h-11 w-[84px] flex-none text-base font-bold uppercase tracking-[0.16em]',
              '@[440px]:h-10 @[440px]:w-auto @[440px]:flex-1',
              '@[700px]:h-auto @[700px]:flex-none @[700px]:px-6 @[700px]:min-w-[100px]',
              '@[1000px]:px-8 @[1000px]:min-w-[120px]',
              !goDisabled && 'shadow-[0_6px_14px_rgba(59,130,246,0.35)]',
            )}
          >
            GO
          </Button>
        </div>
      </div>
    </div>
  )
})

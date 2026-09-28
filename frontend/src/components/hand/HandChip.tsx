import { useEffect } from 'react'
import { AudioWaveform, Hand, ListMusic, Palette, SwatchBook, X } from 'lucide-react'
import { EFFECT_GLYPH_CLASS, padFaceOf, type PadFace } from '@/components/busking/padFace'
import { useEscapeEditorSnapshot } from '@/components/sheet/useEscapeEditorSnapshot'
import { handDrop, useHand } from '@/store/hand'
import { cn } from '@/lib/utils'

/**
 * **What the desk is holding**, drawn as one chip fixed at the bottom of `<main>`.
 *
 * It is the whole of the hand's chrome (multi-screen plan §3.5, D14): *nothing new goes in the app
 * header row*, which `Layout.tsx`'s own comment already says cannot wrap and is at its width on a
 * phone. Fixed rather than in the flow, and below every bar, because the hand outlives the route —
 * you pick a record up on one view and put it down on another.
 *
 * ### The ghost is frozen and hookless
 *
 * The face comes from [padFaceOf] over the **summary DTOs the `hand.state` frame carries**, exactly
 * as a busk pad's does over the ones its row carries. This component subscribes to *nothing about
 * the held item* — no template list, no Look list, and above all no speed-master bank, which is
 * `dragOverlayRegistry`'s rule and has the same reason here: an effect template's detail line reads
 * a live master label through a hook, and a chip that can be on screen for five minutes across
 * every route must not mount one per hold.
 *
 * The cost lighting7 recorded as `FU-DTO-RECORD-SUMMARY` is accepted: the embedded summaries'
 * `usage` and `buskPageCount` were computed at pick-up, so a long hold can read "on 3 pages" after
 * a fourth was added. A pad's face is frozen between reads too. Nothing here presents either field.
 *
 * It subscribes to the **hand** itself, of course — that is the one thing it is about.
 */
export function HandChip() {
  const held = useHand()
  const claimedRef = useEscapeEditorSnapshot(anyOverlayOpen)

  useEffect(() => {
    if (held == null) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return
      // The last rung of the Escape ladder. See the snapshot hook for why the answer is taken in
      // the capture phase and read here rather than asked here.
      if (claimedRef.current) return
      // Bare, not guarded: "let go of whatever is there" is exactly what this gesture means, and
      // the record on screen may already be someone else's by the time the key lands.
      handDrop()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [held, claimedRef])

  if (held == null) return null

  const face = padFaceOf(held)

  return (
    <div
      // `key` carries the **hold**, not the record: a re-pick-up of the same record is a different
      // hold (`holdId`, and the reason it exists at all), and this chip should announce itself
      // again rather than sit unchanged through a gesture the operator just made.
      key={held.holdId}
      data-hand-chip
      // **Which** hold is on screen, not merely that one is. A DOM contract for the same reason the
      // grid's cell anchors carry `data-state`: `holdId` is the identity of this hold, two hands of
      // one record differ in nothing else, and without it neither a test nor a later session can
      // tell "still holding" from "picked the same thing up again".
      data-hold-id={held.holdId}
      role="status"
      aria-live="polite"
      className={cn(
        'fixed bottom-4 left-1/2 z-50 -translate-x-1/2',
        'flex max-w-[min(22rem,calc(100vw-2rem))] items-center gap-2 rounded-full border',
        'border-primary/50 bg-background/95 py-1.5 pl-3 pr-1.5 shadow-lg backdrop-blur',
      )}
    >
      <Hand className="size-4 shrink-0 text-primary" aria-hidden />
      <HeldGlyph face={face} />
      <div className="min-w-0 flex-1">
        <div className="truncate text-xs font-medium leading-tight">{face.name}</div>
        <div className="truncate text-[10px] leading-tight text-muted-foreground">
          {face.cueNumber != null ? `${face.cueNumber} · ${face.detail}` : face.detail}
        </div>
      </div>
      <span className="shrink-0 text-[10px] uppercase tracking-wide text-muted-foreground">
        Pick a place
      </span>
      <button
        type="button"
        onClick={() => handDrop()}
        aria-label={`Let go of ${face.name}`}
        title="Let go (Esc)"
        className="grid size-6 shrink-0 place-items-center rounded-full text-muted-foreground hover:bg-accent hover:text-foreground"
      >
        <X className="size-3.5" strokeWidth={2.5} />
      </button>
    </div>
  )
}

/** The swatch slot: a colour, the effect wave, or the kind's own glyph — the pad's vocabulary. */
function HeldGlyph({ face }: { face: PadFace }) {
  if (face.swatch != null) {
    return (
      <span
        aria-hidden
        className="size-3 shrink-0 rounded-full border border-border"
        style={{ background: face.swatch }}
      />
    )
  }
  if (face.isEffect) return <AudioWaveform className={EFFECT_GLYPH_CLASS} aria-hidden />
  if (face.kind === 'CUE') {
    return <ListMusic className="size-3 shrink-0 text-muted-foreground" aria-hidden />
  }
  if (face.kind === 'LOOK') {
    return <SwatchBook className="size-3 shrink-0 text-muted-foreground" aria-hidden />
  }
  return <Palette className="size-3 shrink-0 text-muted-foreground" aria-hidden />
}

/**
 * Is an overlay other than a cell editor claiming Escape?
 *
 * The **timing** — a window capture listener, because Radix listens on the document and closes
 * first, so a bubble-phase read always answers "nothing open" — belongs to
 * `useEscapeEditorSnapshot`, which this is passed to. What is here is only the extra rung: the
 * hand's ladder is longer than the grid's, because a cell editor is one of several things that can
 * be over the page while a record is held.
 *
 * It is a **document-wide** query, not an ancestor walk. "Is a dialog open anywhere" is a fact
 * about the page, which is what makes it the right shape where `closest('[role=\"dialog\"]')` — the
 * *where was the key pressed* question — is the wrong one.
 */
function anyOverlayOpen(): boolean {
  if (typeof document === 'undefined') return false
  return document.querySelector('[role="dialog"], [data-radix-popper-content-wrapper]') != null
}

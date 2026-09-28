// =============================================================================
// lighting7 — Show Mode Prompt-Book: data model
// =============================================================================
//
// Four masters this model must serve:
//   1. Runtime emphasis   — active cue's region is highlighted as the operator advances
//   2. Editing            — regions/annotations editable mid-run, behind a lock
//   3. Desync detection   — flag when cue-stack order ≠ script position order
//   4. Persistence        — viewport-independent, content-addressed, sync-backend friendly
//
// Assumption A (confirmed): the cue stack & activeCueId are OWNED UPSTREAM by the
//   existing Show Mode runner. This view subscribes; it does not own running state.
// Assumption B (confirmed): desync is ADVISORY ONLY. Nothing here reorders or
//   blocks. We compute warnings; the operator decides.
//
// =============================================================================


// ----------------------------------------------------------------------------
// Geometry — stored normalized (0..1) against page dimensions, never in pixels.
// This is what survives zoom, window resize, and re-display at a different size.
// react-pdf-highlighter-extended's highlight format is viewport-independent in
// the same spirit; `Rect` is our own canonical persisted form that we map to/from
// the library's scaled position at render time.
// ----------------------------------------------------------------------------

/** A rectangle on a single page, normalized to [0,1] in both axes. */
export interface Rect {
  /** 0-based page index within the ScriptDoc. */
  page: number;
  /** Left edge, fraction of page width. */
  x: number;
  /** Top edge, fraction of page height. y grows downward. */
  y: number;
  /** Width, fraction of page width. */
  w: number;
  /** Height, fraction of page height. */
  h: number;
}

/**
 * A region is one-or-more rects. Multiple rects let a single anchor or
 * annotation span a page break, or cover a multi-line text selection that
 * the text layer reports as several disjoint boxes.
 */
export type Region = Rect[];


// ----------------------------------------------------------------------------
// The script document the overlays are pinned to.
// ----------------------------------------------------------------------------

export interface ScriptDoc {
  id: string;
  /**
   * Content hash of the PDF bytes (e.g. SHA-256). This is the stable identity,
   * NOT the filename — users re-import, rename, and re-export scripts. Highlight
   * sets are keyed off this so a re-import of the *same* bytes re-attaches
   * cleanly, and a *changed* script is detectably different.
   */
  contentHash: string;
  /** Display name only. Never used for identity or matching. */
  title: string;
  pageCount: number;
}


// ----------------------------------------------------------------------------
// Cue anchors — the spine. Bind an upstream cue-stack entry to the script.
//
// Note the deliberate split: a CueAnchor does NOT contain the cue's lighting
// payload (levels, fade times, the Looks Library reference, etc). It holds only
// the *binding* — which cue, and where it lives on the page. The lighting data
// stays in the cue stack, owned upstream. This keeps a re-imported or lightly
// edited script from dragging the whole cue stack with it, and lets the same
// cue stack be re-anchored against a revised script.
// ----------------------------------------------------------------------------

export interface CueAnchor {
  id: string;
  scriptDocId: string;
  /**
   * FK into the upstream Show Mode cue stack. The authoritative ordering and all
   * lighting data live there; we only reference it.
   */
  cueId: string;
  /** One cue → many rects (spans page breaks / wrapped lines). */
  region: Region;
  /**
   * Optional cached label ("LX 12", "Q14") purely for rendering the badge
   * without a round-trip to the cue stack. Display convenience, not identity.
   */
  label?: string;
}


// ----------------------------------------------------------------------------
// Free annotations — commentary on the script, NOT cue-stack entries.
// Different lifecycle, independent lock, and the desync logic ignores them
// (except `strikethrough`, which feeds the "active cue overlaps a cut" warning).
// ----------------------------------------------------------------------------

export type AnnotationKind = "note" | "strikethrough" | "freetext";

export interface Annotation {
  id: string;
  scriptDocId: string;
  kind: AnnotationKind;
  region: Region;
  /**
   * For `note` / `freetext`: the text content.
   * For `strikethrough`: usually empty; the region itself is the meaning.
   */
  text?: string;
  /** Optional colour override; otherwise derives from kind. */
  color?: string;
}


// ----------------------------------------------------------------------------
// Persisted overlay set for one script. This is the unit that syncs to the
// GitHub-repo / SQLite backend. View state below is explicitly NOT in here.
// ----------------------------------------------------------------------------

export interface PromptBook {
  scriptDoc: ScriptDoc;
  anchors: CueAnchor[];
  annotations: Annotation[];
}


// ----------------------------------------------------------------------------
// Runtime view state — NEVER persisted. Owned partly upstream (activeCueId),
// partly local (lock, scroll). Kept separate so we never accidentally write
// "where the operator was scrolled" into the saved prompt-book.
// ----------------------------------------------------------------------------

export type LockState = "locked" | "unlocked";

export interface ViewState {
  /** Comes from upstream Show Mode runner. We subscribe; we don't set it. */
  activeCueId: string | null;
  /** Local. Defaults to "locked" when entered from Show Mode. */
  lock: LockState;
  /** Local. Anchor/annotation the operator last asked to scroll to. */
  scrollTargetId: string | null;
}


// =============================================================================
// Desync detection — advisory only (Assumption B).
// =============================================================================
//
// Two orderings that should agree in a clean prompt-book:
//   • cue-stack order  — authoritative sequence from upstream (an array of cueIds)
//   • script position  — each anchor's (page, y) reading position
//
// Agreement means: advancing the stack moves you monotonically DOWN the script.
// A desync is an anchor whose script position sits at-or-above the previous
// (in stack order) anchor's position — i.e. an inversion.
//
// We also surface a second, related class: an active/any anchor overlapping a
// `strikethrough` region (a cue pointing into cut text).
// =============================================================================

export type WarningKind = "out-of-order" | "anchor-in-cut" | "unanchored-cue";

export interface DesyncWarning {
  kind: WarningKind;
  cueId: string;
  anchorId?: string;
  message: string;
}

/** Reading-order key for a region: earliest page, then topmost y. */
function scriptPosition(region: Region): { page: number; y: number } {
  return region.reduce(
    (best, r) =>
      r.page < best.page || (r.page === best.page && r.y < best.y)
        ? { page: r.page, y: r.y }
        : best,
    { page: Infinity, y: Infinity }
  );
}

/** True if two rects overlap at all (same page, intersecting boxes). */
function rectsOverlap(a: Rect, b: Rect): boolean {
  if (a.page !== b.page) return false;
  return (
    a.x < b.x + b.w &&
    a.x + a.w > b.x &&
    a.y < b.y + b.h &&
    a.y + a.h > b.y
  );
}

function regionsOverlap(a: Region, b: Region): boolean {
  return a.some((ra) => b.some((rb) => rectsOverlap(ra, rb)));
}

/**
 * Compute advisory warnings for a prompt-book against the upstream cue order.
 *
 * @param book          the persisted overlays
 * @param cueStackOrder authoritative cueId sequence from the Show Mode runner
 */
export function computeWarnings(
  book: PromptBook,
  cueStackOrder: string[]
): DesyncWarning[] {
  const warnings: DesyncWarning[] = [];
  const anchorByCue = new Map(book.anchors.map((a) => [a.cueId, a]));
  const cuts = book.annotations.filter((n) => n.kind === "strikethrough");

  // 1. Out-of-order: walk cues in stack order, require monotonic script position.
  let prev: { page: number; y: number } | null = null;
  let prevCue: string | null = null;
  for (const cueId of cueStackOrder) {
    const anchor = anchorByCue.get(cueId);
    if (!anchor) {
      // 3. Unanchored cue — in the stack but never placed on the script.
      warnings.push({
        kind: "unanchored-cue",
        cueId,
        message: `Cue ${cueId} has no anchor on the script.`,
      });
      continue;
    }
    const pos = scriptPosition(anchor.region);
    if (prev && (pos.page < prev.page || (pos.page === prev.page && pos.y < prev.y))) {
      warnings.push({
        kind: "out-of-order",
        cueId,
        anchorId: anchor.id,
        message:
          `Cue ${anchor.label ?? cueId} appears earlier in the script than ` +
          `the cue before it (${prevCue ?? "previous"}). Check anchor placement or stack order.`,
      });
    }
    prev = pos;
    prevCue = anchor.label ?? cueId;

    // 2. Anchor-in-cut: this cue's anchor overlaps a struck-out section.
    if (cuts.some((cut) => regionsOverlap(anchor.region, cut.region))) {
      warnings.push({
        kind: "anchor-in-cut",
        cueId,
        anchorId: anchor.id,
        message: `Cue ${anchor.label ?? cueId} is anchored inside a cut section.`,
      });
    }
  }

  return warnings;
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useStableCallback } from '@/hooks/useStableCallback'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { Loader2, Trash2 } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Textarea } from '@/components/ui/textarea'
import { useProjectQuery } from '../store/projects'
import {
  useProjectProgramStateQuery,
  useActivateProgramMutation,
  useDeactivateProgramMutation,
  useProjectCueStackListQuery,
} from '../store/cueStacks'
import { usePatchProjectCueMutation } from '../store/cues'
import { useNarrowContainer } from '../hooks/useContainerBand'
import { useShowBarProps } from '../hooks/useShowBarProps'
import { useProjectPromptBookQuery } from '../store/promptBooks'
import { scriptDocUrl, type NoteTone } from '../api/promptBooksApi'
import { cn } from '@/lib/utils'
import { computeWarnings, type DesyncWarning, type FlatCue } from '../lib/promptBook/desync'
import { useCueIndex } from '../lib/promptBook/useCueIndex'
import { useCueRunStatus } from '../lib/promptBook/useCueRunStatus'
import { useRailExpansion } from '../lib/promptBook/useRailExpansion'
import { useCardViewMode } from '../lib/promptBook/useCardViewMode'
import { useBookAnchors } from '../lib/promptBook/useBookAnchors'
import { useAnnotationEditor } from '../lib/promptBook/useAnnotationEditor'
import { useScriptDocument } from '../lib/promptBook/useScriptDocument'
import { ScriptViewer, type ScriptViewerHandle } from '../components/promptbook/ScriptViewer'
import { CueAnchorPickerSheet } from '../components/promptbook/CueAnchorPickerSheet'
import { ShowHeader } from '../components/ShowHeader'
import { ImmersiveEscape } from '@/components/ImmersiveEscape'
import { ShowBar } from '../components/ShowBar'
import { PromptBookToolbar } from '../components/promptbook/PromptBookToolbar'
import { ShowLockControl } from '../components/runner/ShowLockControl'
import { ToolPalette, type PromptBookTool } from '../components/promptbook/ToolPalette'
import { CueStackPanel } from '../components/promptbook/CueStackPanel'
import { ScriptUploadCard } from '../components/promptbook/ScriptUploadCard'
import { useEditLock } from '../hooks/useEditLock'
import { useTransportKeys } from '../hooks/useTransportKeys'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

export function PromptBookRedirect() {
  return <CurrentProjectRedirect to="prompt-book" />
}

// ─── Viewer ──────────────────────────────────────────────────────────────

/**
 * The Prompt Book: the show's script PDF with cues anchored into it.
 *
 * The page composes six hooks under `lib/promptBook/` and owns only what genuinely spans them —
 * the lock, the transport, the desync advisories and the layout. The hooks are ordered by a real
 * dependency knot rather than by taste: anchors before the lock (its `onLock` stands the placing
 * cursor down), the lock before the transport (`onBeforeGo` re-locks), and the transport before
 * the playhead-derived state. `useBookAnchors` deliberately knows nothing about the lock so that
 * knot stays a line — see its docblock.
 */
export function PromptBookViewerPage() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)

  const navigate = useNavigate()
  const { data: book, isLoading: bookLoading, error: bookError, refetch: refetchBook } = useProjectPromptBookQuery(projectIdNum)
  const { data: project } = useProjectQuery(projectIdNum)
  const { data: programState } = useProjectProgramStateQuery(projectIdNum)
  const { data: stacks } = useProjectCueStackListQuery(projectIdNum)

  const [activateShow] = useActivateProgramMutation()
  const [deactivateShow] = useDeactivateProgramMutation()
  const [patchCue] = usePatchProjectCueMutation()

  const activeStackId = programState?.activeStackId ?? null
  const isShowActive = activeStackId != null

  // ── Runtime view state — NEVER persisted. ──
  const [tool, setTool] = useState<PromptBookTool>('move')
  const [showWarnings, setShowWarnings] = useState(true)
  // Local blackout toggle (parity with the Run view) + tablet/phone drawer.
  const [dbo, setDbo] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  // Below this container width the side rail becomes a drawer + bottom transport.
  const [bodyRef, isNarrow] = useNarrowContainer(1040)

  const viewerRef = useRef<ScriptViewerHandle>(null)

  // ── Upstream running state — subscribed, never owned. ──
  const {
    cueOrder,
    railRows,
    cueOrderIndex,
    cueLabelByCue,
    existingCueNames,
    cueEntryByCue,
    cueNotesByCue,
    defaultStackId,
  } = useCueIndex(stacks, activeStackId)

  const {
    anchorByCue,
    anchorHintByCue,
    scrollToCue,
    placingCueId,
    clearPlacing,
    togglePlacing,
    anchorPicker,
    overlapCueId,
    requestAnchor,
    closePicker,
    undoSnapshot,
    undo,
    moveAnchor,
    placeAnchor,
    anchorCue,
    removeAnchor,
    createCueFromSelection,
  } = useBookAnchors({ projectId: projectIdNum, book, stacks, cueOrder, cueLabelByCue, viewerRef })

  const doc = useScriptDocument(projectIdNum, book)
  const ann = useAnnotationEditor(projectIdNum)

  const canEdit = book?.canEdit ?? false

  /**
   * The shared show-editing lock. `onLock` stands the annotation tools down with it — a selected
   * tool that cannot be used reads as breakage rather than as protection.
   */
  const relock = useEditLock({
    canEdit,
    isShowActive,
    onLock: () => {
      setTool('move')
      clearPlacing()
    },
  })
  const { locked, toggleLock, noteEdit, noteGo } = relock
  /** See the note in `ShowPage`: the whole chrome band tints, or it reads as stripes. */
  const unlockedWarning = !locked && isShowActive

  /**
   * One transport and one show bar, from the same hook every other live view uses.
   *
   * This page used to call `useShowTransport` directly and hand-wire twelve `ShowBar` props, which
   * is how it ended up as the only view whose bar lacked a tile the others had (Blind, at the time)
   * and the only one deriving the stack name differently. `canOperate` and `onBeforeGo` are the
   * two things that are genuinely this page's — book-level permission, and re-locking on GO.
   */
  // `frameRateProgress: false` — the rail's live card reads its own fade via `useCueFade`
  // (see `railProps`/`CueStackPanel` below), so this page never needs `fadeProgress`/
  // `fadeRemainMs`; without the flag every fade frame re-rendered this page and, through
  // `railProps`, every card in the whole show.
  const { transport, showBarProps } = useShowBarProps(projectIdNum, {
    canOperate: canEdit,
    onBeforeGo: noteGo,
    frameRateProgress: false,
  })
  const {
    activeStack,
    activeCueId,
    standbyCueId,
    goDisabled,
    go: fireGo,
    back: fireBack,
  } = transport

  const { nextCueId, statusOf } = useCueRunStatus({
    isShowActive,
    activeCueId,
    standbyCueId,
    activeStackId,
    cueOrder,
    cueOrderIndex,
  })

  const { isExpanded, toggleExpanded } = useRailExpansion(activeCueId, nextCueId)
  const { modeOf, onCueModeChange } = useCardViewMode()

  // ── Desync — advisory only; recomputed on every edit and on load. ──
  const warnings: DesyncWarning[] = useMemo(
    () => (book ? computeWarnings(book.anchors, book.annotations, cueOrder) : []),
    [book, cueOrder],
  )
  const warningsByCue = useMemo(() => {
    const map = new Map<number, DesyncWarning[]>()
    for (const w of warnings) map.set(w.cueId, [...(map.get(w.cueId) ?? []), w])
    return map
  }, [warnings])
  const warningCueIds = useMemo(() => new Set(warnings.map((w) => w.cueId)), [warnings])

  const jumpToLive = useCallback(() => {
    if (activeCueId != null) scrollToCue(activeCueId)
  }, [activeCueId, scrollToCue])

  /**
   * **`?cue=` is the arrival contract, the mirror of the one this page mints for Show.** The cue
   * sheet's Book column asks "where is this cue in the book?", so it lands here at that cue's
   * anchor rather than on page one — or on the live cue, which is a different cue entirely and is
   * what `jumpToLive` below would otherwise do a beat later.
   *
   * Consumed **once per id**, by the same rule and for the same reason as `CueSheet`'s: the book
   * and its anchors are refetched on every edit and every WS echo, so a plain dependency on
   * `cueOrderIndex` would drag the viewport back here while the operator read ahead. The id is
   * left in the URL — it is a deep link, not a command — and the ref remembers it has been acted
   * on; a cue not in the book yet is consumed when its anchors arrive.
   */
  const [searchParams] = useSearchParams()
  // `cueParam ? Number(…) : null`, the idiom `ShowPage` parses this same contract with — not
  // `Number(…) || null`, which would read a cue id of 0 as "no link" and leave the two consumers of
  // one arrival contract disagreeing about it. An unparseable id is NaN, which `cueOrderIndex` has
  // no entry for, so the effect below simply never fires.
  const cueParam = searchParams.get('cue')
  const linkedCueId = cueParam ? Number(cueParam) : null
  const consumedLinkRef = useRef<number | null>(null)
  useEffect(() => {
    if (linkedCueId == null) {
      consumedLinkRef.current = null
      return
    }
    if (consumedLinkRef.current === linkedCueId) return
    if (!cueOrderIndex.has(linkedCueId)) return
    consumedLinkRef.current = linkedCueId
    scrollToCue(linkedCueId)
  }, [cueOrderIndex, linkedCueId, scrollToCue])

  // ── Runtime emphasis: scroll the live cue into view on advance. ──
  // The same operation the toolbar's "jump to live" performs, so the two cannot drift.
  // `scrollToCue` holds one identity for the session, so an unrelated book refetch (edit, WS echo)
  // can't re-run this and yank the viewport while the operator reads ahead.
  // Not while a `?cue=` link is being honoured — the two would fight over the viewport, and the
  // one the operator asked for is the link.
  const jumpToLiveUnlessLinked = useCallback(() => {
    if (linkedCueId != null) return
    jumpToLive()
  }, [jumpToLive, linkedCueId])
  useEffect(jumpToLiveUnlessLinked, [jumpToLiveUnlessLinked])

  /**
   * …and the one case that stability makes unreachable: **opening the book while the show runs**.
   * The playhead lands first — the program state is a far smaller query than the book — so the
   * effect above fires while `viewerRef` is still null (the guards below are still rendering a
   * spinner), and then never again, because neither `activeCueId` nor `scrollToCue` moves when the
   * book finally arrives. The book sat at page one until the next GO.
   *
   * So the viewer says when its sheets exist and we jump then. Through a ref because this is a
   * prop of the memoized viewer: it must keep one identity while still reading the playhead as it
   * is at that moment rather than as it was at mount.
   */
  const handlePagesReady = useStableCallback(() => {
    if (linkedCueId != null && cueOrderIndex.has(linkedCueId)) {
      consumedLinkRef.current = linkedCueId
      scrollToCue(linkedCueId)
      return
    }
    jumpToLive()
  })

  // Arm a cue as the next GO (mirrors the Run page's standby). Does NOT fire it. The
  // transport ignores the live cue; we just also close the narrow drawer here.
  const handleSetStandby = useCallback(
    (cueId: number) => {
      transport.setStandby(cueId)
      setDrawerOpen(false)
    },
    [transport],
  )

  // Jump to the cue's editor in Program, deep-linking to the exact cue (mirrors Run's
  // "Edit cue"). Show's ?stack=&cue= handler requires the stack, so resolve it first.
  const handleEditCue = useCallback(
    (cueId: number) => {
      const flat = cueOrder[cueOrderIndex.get(cueId) ?? -1]
      if (flat?.stackId != null) {
        navigate(`/projects/${projectIdNum}/show/stacks/${flat.stackId}?cue=${cueId}`)
      } else {
        navigate(`/projects/${projectIdNum}/show`)
      }
    },
    [cueOrder, cueOrderIndex, navigate, projectIdNum],
  )

  // Inline cue identity edits from the rail, unlocked only. PATCH so we touch one field and
  // leave the cue's children alone; the WS echo refreshes the rail and the script's cue pills.
  // Existing anchors keep their cached label, which the rail/viewer already override with the
  // live cue labels — so a renumber shows up immediately without re-saving anchors.
  const handleRenameCue = useCallback(
    (cueId: number, name: string) => patchCue({ projectId: projectIdNum, cueId, name }),
    [patchCue, projectIdNum],
  )
  const handleRenumberCue = useCallback(
    (cueId: number, cueNumber: string | null) => patchCue({ projectId: projectIdNum, cueId, cueNumber }),
    [patchCue, projectIdNum],
  )
  const handleRenoteCue = useCallback(
    (cueId: number, notes: string | null) => patchCue({ projectId: projectIdNum, cueId, notes }),
    [patchCue, projectIdNum],
  )

  // Primary rail click: always move the book as close to the cue as we can — its own
  // anchor, or a borrowed neighbour position. Unlocked, an unanchored cue ALSO arms
  // click-to-place, so you land near where the anchor belongs and can select the line.
  const handleCueClick = useCallback(
    (cue: FlatCue) => {
      scrollToCue(cue.cueId)
      if (!anchorByCue.has(cue.cueId) && !locked) togglePlacing(cue.cueId)
    },
    [scrollToCue, anchorByCue, locked, togglePlacing],
  )

  const handleWarningClick = useCallback(
    (warning: DesyncWarning) => scrollToCue(warning.cueId),
    [scrollToCue],
  )

  // Start/Stop the show in place from the header (parity with Program/Run). State is
  // derived from the program playhead, so no local entry tracking is needed here.
  const runnableStackCount = stacks?.filter((s) => s.type === 'STACK').length ?? 0
  const canStart = !isShowActive && runnableStackCount > 0
  const handleStartShow = useCallback(() => {
    activateShow({ projectId: projectIdNum })
      .unwrap()
      .catch(() => {
        // Reported by errorToastMiddleware; caught here only to stop the unhandled rejection.
      })
  }, [activateShow, projectIdNum])
  const handleStopShow = useCallback(async () => {
    await deactivateShow({ projectId: projectIdNum }).unwrap()
  }, [deactivateShow, projectIdNum])

  /**
   * Space=GO, Backspace=Back, L toggles the lock — see `useTransportKeys` for the guards, three of
   * which this page used to carry inline and one of which it was missing.
   *
   * Gated on `locked`, matching Show. Unlocked means the operator is editing — anchors, annotations,
   * inline cue identities — and in an editing surface Space is a space. `L` stays bound either way,
   * so there is always a keyboard route back to a safe desk. The two surfaces share one lock, so
   * they had better agree about what it does to the keyboard.
   */
  useTransportKeys({
    enabled: locked && !goDisabled,
    onGo: fireGo,
    onBack: fireBack,
    onToggleLock: toggleLock,
  })

  // ── Guards ──

  // Every guard arm draws the immersive glyph over its card: with no header there would otherwise
  // be no way back on a touch screen (`ImmersiveEscape`).
  if (bookLoading) {
    return (
      <>
        <ImmersiveEscape />
        <Card className="m-4 p-4 flex items-center justify-center">
          <Loader2 className="size-6 animate-spin" />
        </Card>
      </>
    )
  }

  if (!book) {
    // Only a genuine 404 means "no book yet". Any other failure (backend restarting,
    // 500, network blip) must NOT show the import card — otherwise a transient blip
    // during a show tempts the operator into re-importing, and setPromptBook (PUT
    // upsert) would replace the real book. Show a retry instead.
    const noBook = bookError != null && 'status' in bookError && bookError.status === 404
    if (!noBook && bookError != null) {
      return (
        <>
          <ImmersiveEscape />
          <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
          <p className="font-medium">Couldn&rsquo;t load the prompt book</p>
          <p className="max-w-md text-sm text-muted-foreground">
            The backend may be restarting or the connection blipped. Your prompt book is untouched.
          </p>
          <Button variant="outline" onClick={() => refetchBook()}>
            Retry
          </Button>
          </div>
        </>
      )
    }
    // No book yet → offer the import. Importing sets the show's one book and this
    // same route re-renders as the reader.
    return (
      <>
        <ImmersiveEscape />
        <div className="mx-auto mt-8 w-full max-w-md p-4">
        <ScriptUploadCard
          title="Import a script PDF"
          description="The PDF becomes the spatial backbone of the show's prompt book — cue anchors pin cues to it. Identity is the file's content, so re-importing the same PDF re-attaches cleanly."
          uploading={doc.uploading || doc.settingBook}
          error={doc.importError}
          onUpload={doc.importBook}
        />
        </div>
      </>
    )
  }

  // O(1) lookups via the existing cueOrderIndex map.
  const liveCue = activeCueId != null ? (cueOrder[cueOrderIndex.get(activeCueId) ?? -1] ?? null) : null
  const activeCueLabel = liveCue?.label ?? null
  const railStackName = activeStack?.name ?? cueOrder[0]?.stackName ?? null
  // Shared rail props — the same panel serves the desktop side rail and the drawer.
  const railProps = {
    rows: railRows,
    anchorByCue,
    anchorHintByCue,
    cueEntryByCue,
    statusOf,
    warningsByCue,
    warnings,
    showWarnings,
    locked,
    placingCueId,
    isExpanded,
    onToggleExpanded: toggleExpanded,
    modeOf,
    onCueModeChange,
    activeStackId,
    onCueClick: handleCueClick,
    onRemoveAnchor: removeAnchor,
    onWarningClick: handleWarningClick,
    onSetStandby: handleSetStandby,
    onEditCue: handleEditCue,
    onRenameCue: handleRenameCue,
    onRenumberCue: handleRenumberCue,
    onRenoteCue: handleRenoteCue,
    goDisabled,
    showActive: isShowActive,
    stackName: railStackName,
    dbo,
    onDbo: () => setDbo((d) => !d),
    projectId: projectIdNum,
    coverPages: book.coverPages,
  }

  const toneBtnActive: Record<NoteTone, string> = {
    NOTE: 'border-sky-600 bg-sky-500/15 text-sky-400',
    WARN: 'border-amber-600 bg-amber-500/15 text-amber-500',
    SAFETY: 'border-red-600 bg-red-500/15 text-red-400',
  }

  return (
    /* One boundary, not fifteen hand-placed calls: `noteEdit` on any pointer/key interaction
       anywhere in the page is what keeps the idle re-lock from firing at an operator who is
       mid-edit. The affordances that edit this page are spread across the toolbar, the tool
       palette, the script overlay, the rail's inline fields, the annotation sheet and the anchor
       picker — catching it once here means a new one cannot forget to feed the timer, which is
       exactly what the scattered call sites could not guarantee. The dialogs are Radix portals but
       still React children of this div, and React propagates through the React tree, so they are
       covered too. A no-op while locked (and while the show is stopped), so it costs nothing in the
       normal running state. Mirrors `ShowPage`, which does the same at its body boundary.

       `input` is the third handler because it is the only one that catches a value arriving without
       a keystroke — a dictated phrase, an IME commit, text dropped into a field. Those are exactly
       the slow, hands-off edits the idle timer would otherwise tear down mid-sentence. */
    <div
      className="flex h-full min-h-0 flex-col"
      onPointerDownCapture={noteEdit}
      onKeyDownCapture={noteEdit}
      onInputCapture={noteEdit}
    >
      <ShowHeader
        view="prompt-book"
        projectId={projectIdNum}
        projectName={project?.name ?? ''}
        isShowActive={isShowActive}
        canStart={canStart}
        onStart={handleStartShow}
        onStop={handleStopShow}
        unlockedWarning={unlockedWarning}
        // Same slot, same control, same position as Show — the lock is shared between the two
        // views, so having it in two different places was the inconsistency.
        actions={
          isShowActive || !canEdit ? (
            <ShowLockControl
              locked={locked}
              onToggle={toggleLock}
              countdownSecondsLeft={relock.countdownSecondsLeft}
              onStayUnlocked={relock.stayUnlocked}
              disabled={!canEdit}
            />
          ) : undefined
        }
      />
      {/* Not gated on the show running, and not hand-wired: the same bar the other two views get,
          from the same hook. It carries blackout, the speed masters and the programmer chip, all of
          which mean something with the show down. */}
      <ShowBar {...showBarProps} showShortcuts={locked} unlockedWarning={unlockedWarning} />
      <PromptBookToolbar
        scriptFileName={book.scriptFileName}
        locked={locked}
        unlockedWarning={unlockedWarning}
        canUndo={undoSnapshot != null}
        onUndo={undo}
        coverPages={book.coverPages}
        pageCount={book.pageCount}
        onCoverPagesChange={doc.setCoverPages}
        activeLabel={activeCueLabel}
        onJumpToLive={jumpToLive}
        warningCount={warnings.length}
        onToggleWarnings={() => setShowWarnings((s) => !s)}
        onOpenCues={isNarrow ? () => setDrawerOpen((o) => !o) : undefined}
      />

      {!locked && (
        <ToolPalette
          tool={tool}
          // Amber is the "you unlocked a running show" signal — with the show stopped the
          // bar is just the normal way to annotate, so it wears the normal chrome.
          warn={isShowActive}
          placingLabel={placingCueId != null ? (cueLabelByCue.get(placingCueId) ?? null) : null}
          onSelectTool={(t) => {
            setTool(t)
            clearPlacing()
          }}
        />
      )}

      <div
        ref={bodyRef}
        className={cn(
          // Unlocking a RUNNING show must be visually unmistakable: the whole script pane
          // gets an inset amber ring. Editing a stopped show is unremarkable — no ring.
          'relative flex min-h-0 flex-1 overflow-hidden',
          !locked && isShowActive && 'shadow-[inset_0_0_0_2px_rgba(245,158,11,0.55)]',
        )}
      >
        <div className="relative flex min-w-0 flex-1 flex-col">
          {doc.loadState === 'missing' ? (
            <div className="flex flex-1 items-center justify-center p-8">
              <div className="max-w-md">
                <ScriptUploadCard
                  title="Script PDF missing on this install"
                  description={`The prompt-book references ${book.scriptFileName ?? 'a PDF'} by content hash, but the file isn't in this backend's store. Re-import the same PDF to re-attach — anchors and annotations are untouched.`}
                  uploading={doc.uploading}
                  error={doc.reuploadError}
                  onUpload={doc.reupload}
                />
                {doc.hashMismatch && (
                  <p className="mt-3 text-sm text-red-500">
                    That PDF&rsquo;s content doesn&rsquo;t match this prompt-book&rsquo;s script (different hash). If the
                    script was revised, open the book settings to swap it in and re-anchor.
                  </p>
                )}
              </div>
            </div>
          ) : doc.loadState === 'error' ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
              <p className="font-medium">Couldn&rsquo;t load the script PDF</p>
              <p className="max-w-md text-sm text-muted-foreground">
                The backend may be restarting or the connection blipped. The script itself is untouched.
              </p>
              <Button variant="outline" onClick={doc.retry}>
                Retry
              </Button>
            </div>
          ) : (
            <ScriptViewer
              // Remount on a script change (different book/PDF) as well as on retry,
              // so per-page text-bounds/scanned classification never carry over stale.
              key={`${book.scriptHash}:${doc.retryNonce}`}
              ref={viewerRef}
              fileUrl={scriptDocUrl(projectIdNum, book.scriptHash)}
              anchors={book.anchors}
              annotations={book.annotations}
              statusOf={statusOf}
              cueLabels={cueLabelByCue}
              cueNotes={cueNotesByCue}
              onRenoteCue={handleRenoteCue}
              warningCueIds={warningCueIds}
              locked={locked}
              tool={tool}
              placingCueId={placingCueId}
              onMoveAnchor={moveAnchor}
              onPlaceAnchor={placeAnchor}
              onAnchorRequest={requestAnchor}
              onCreateAnnotation={ann.create}
              onAnnotationClick={ann.open}
              onDocumentError={doc.onDocumentError}
              onPagesReady={handlePagesReady}
            />
          )}
        </div>

        {!isNarrow && <CueStackPanel {...railProps} />}

        {/* Tablet / phone: rail slides in from the right over a scrim. */}
        {isNarrow && (
          <>
            <div
              className={cn(
                'absolute inset-0 z-30 bg-black/50 transition-opacity',
                drawerOpen ? 'opacity-100' : 'pointer-events-none opacity-0',
              )}
              onClick={() => setDrawerOpen(false)}
            />
            <div
              className={cn(
                'absolute inset-y-0 right-0 z-40 flex w-[min(380px,88%)] flex-col border-l bg-background shadow-2xl transition-transform',
                drawerOpen ? 'translate-x-0' : 'translate-x-full',
              )}
            >
              <CueStackPanel {...railProps} inDrawer onClose={() => setDrawerOpen(false)} />
            </div>
          </>
        )}
      </div>

      {/* Note / freetext text entry — a form, so a Sheet per the app's Sheet-vs-Dialog rule.
          A clicked strikethrough has nothing to edit; it gets a delete confirmation Dialog. */}
      <Sheet open={ann.sheetOpen} onOpenChange={(open) => !open && ann.close()}>
        <SheetContent className="flex flex-col sm:max-w-md">
          <SheetHeader>
            <SheetTitle>
              {ann.mode === 'create' ? `New ${ann.kindLabel}` : `Edit ${ann.kindLabel}`}
            </SheetTitle>
          </SheetHeader>
          <SheetBody>
            {ann.kind === 'NOTE' && (
              <div>
                <span className="mb-1.5 block text-[10.5px] font-medium tracking-wide text-muted-foreground uppercase">
                  Tone
                </span>
                <div className="flex gap-2">
                  {(['NOTE', 'WARN', 'SAFETY'] as NoteTone[]).map((t) => (
                    <button
                      key={t}
                      type="button"
                      onClick={() => ann.setTone(t)}
                      className={cn(
                        'flex-1 rounded-md border px-2 py-1.5 text-xs font-semibold capitalize',
                        ann.tone === t
                          ? toneBtnActive[t]
                          : 'text-muted-foreground hover:bg-muted/40',
                      )}
                    >
                      {t.toLowerCase()}
                    </button>
                  ))}
                </div>
              </div>
            )}
            <Textarea
              value={ann.text}
              onChange={(e) => ann.setText(e.target.value)}
              placeholder="e.g. slow build, 5s — watch conductor"
              autoFocus
            />
          </SheetBody>
          {ann.mode === 'edit' ? (
            <SheetFooter className="flex-row justify-between">
              <Button variant="destructive" onClick={ann.remove}>
                <Trash2 className="size-3.5" />
                Delete
              </Button>
              <div className="flex gap-2">
                <Button variant="outline" onClick={ann.close}>
                  Cancel
                </Button>
                <Button onClick={ann.commit}>Save</Button>
              </div>
            </SheetFooter>
          ) : (
            <SheetFooter className="flex-row justify-end gap-2">
              <Button variant="outline" onClick={ann.close}>
                Cancel
              </Button>
              <Button onClick={ann.commit}>Save</Button>
            </SheetFooter>
          )}
        </SheetContent>
      </Sheet>

      <Dialog open={ann.cutConfirmOpen} onOpenChange={(open) => !open && ann.close()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove this cut?</DialogTitle>
          </DialogHeader>
          <DialogDescription>
            The strikethrough will be removed from the script. Anchors and the cue stack are untouched.
          </DialogDescription>
          <DialogFooter>
            <Button variant="outline" onClick={ann.close}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={ann.remove}>
              <Trash2 className="size-3.5" />
              Remove cut
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <CueAnchorPickerSheet
        open={anchorPicker != null}
        cueOrder={cueOrder}
        anchorByCue={anchorByCue}
        stacks={stacks ?? []}
        defaultStackId={defaultStackId}
        existingCueNames={existingCueNames}
        preselectCueId={placingCueId ?? overlapCueId}
        onPick={(cueId) => {
          if (anchorPicker) anchorCue(cueId, anchorPicker.region)
        }}
        onCreateCue={createCueFromSelection}
        onEditCue={handleEditCue}
        onClose={closePicker}
      />
    </div>
  )
}

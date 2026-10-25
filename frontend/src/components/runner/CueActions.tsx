import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import {
  ArrowRightLeft,
  ChevronDown,
  Copy,
  Download,
  EyeOff,
  Link,
  ListPlus,
  ListX,
  Loader2,
  MoreHorizontal,
  RefreshCw,
  SquarePen,
  Trash2,
  Zap,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { useDuplicateCueMutation } from '@/store/cues'
import { useCueEdited } from '@/store/cueHolds'
import {
  useAddCueToStackMutation,
  useMoveCueStackEntryMutation,
  useProjectCueStackListQuery,
  useProjectProgramStateQuery,
  useRemoveCueStackEntryMutation,
} from '@/store/cueStacks'
import { programmerBlindOn } from '@/store/programmer'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { setShowInclude } from '@/lib/showInclude'
import { useInclude } from '@/components/programmer/useInclude'
import type { CueToDelete } from '@/components/cues/CueDeleteDialog'
import { useCueActionDialogs } from './cueActionDialogs'

/** One row the actions act on: a cue, and the entry it was opened from (null for a loose cue). */
export interface CueActionRow {
  cueId: number
  name: string
  cueNumber: string | null
  entryId: number | null
  stackId: number | null
  /**
   * How many places the cue has, where the host drew the cue rather than one of its places (the
   * Cues table): a cue in several reaches this set with no entry, which is not the same as loose.
   */
  placeCount?: number
}

/**
 * Where the set is drawn: the card's strip under its collapsed row, the cue sheet's selection bar,
 * the phone card's ⋯ menu, or the Cues table's selection bar.
 */
export type CueActionsHost = 'strip' | 'bar' | 'menu' | 'table'

/** A one-record action over several rows says why it is off. */
export const ONE_CUE_REASON = 'One cue at a time — select a single cue'

const DEFAULT_LOCKED_REASON = 'Locked — this project cannot be edited from here'

/**
 * The words each host folds to glyphs, widest first: the stack group, then Load and Record, then
 * the split button last. Rungs are the host's container, measured in the app — the card for the
 * strip, the cue sheet's bar for the bar, the Cues table's bar for the table. Literal classes, so
 * Tailwind emits them.
 */
const FOLDS: Record<Exclude<CueActionsHost, 'menu'>, { stack: string; programmer: string; split: string }> = {
  // The card's content box: the strip worded needs 835 + the strip's 24px gutter, 611 with the
  // stack group folded, 424 with Load and Record folded too (measured 2026-10-16).
  strip: {
    stack: 'hidden @[864px]:inline',
    programmer: 'hidden @[640px]:inline',
    split: 'hidden @[452px]:inline',
  },
  // The cue sheet's bar, measured with a cell selection — Set · Clear · Spread and the counts beside
  // the set, whose words the kit folds below 1100: worded the set fits from 1440, stack-folded from
  // 1230, and Load and Record folded until 1050 (2026-10-16).
  bar: {
    stack: 'hidden @[1456px]:inline',
    programmer: 'hidden @[1240px]:inline',
    split: 'hidden @[1056px]:inline',
  },
  // The Cues table's bar, measured with a Notes cell selected — the counts, Set · Clear and their
  // hints beside the set: worded the set fits from 1025, stack-folded from 805, and Load and Record
  // folded from 625, each rung ~45 over for longer counts (2026-10-17).
  table: {
    stack: 'hidden @[1072px]:inline',
    programmer: 'hidden @[848px]:inline',
    split: 'hidden @[672px]:inline',
  },
}

interface CueActionsProps {
  projectId: number
  /** The rows acted on, in row order — one from a card, several from the sheet's selection. */
  rows: readonly CueActionRow[]
  host: CueActionsHost
  /** The show-editing lock as this host draws it (CLAUDE.md §The show-editing lock). */
  locked: boolean
  /**
   * Given, a locked action asks to unlock and then lifts the lock through this; absent, every action
   * but *Blind edit* is disabled with [lockedReason] — the lock is not the operator's to lift here.
   */
  onRequestUnlock?: () => void
  lockedReason?: string
  /**
   * The host is the Show view (the card, the cue sheet, the phone runner there): its Includes are
   * recorded for the programmer's *← Show* (D16). The Prompt Book's and the busk view's are not.
   */
  fromShowView?: boolean
  className?: string
}

/**
 * **One cue action set, four hosts** (show-cue-editing plan §5, D6, D8, D9): the card's strip, the
 * cue sheet's selection bar, the phone card's ⋯ menu and the Cues table's bar all draw this, so the
 * four cannot drift.
 *
 * - **The split button follows the show** (D6). Stopped, its face is *Edit in programmer* — Include,
 *   then the programmer — with *Blind edit in programmer* in its menu; running, the two swap, and the
 *   face is amber while it is Blind edit. *Blind edit* switches Blind on at the programmer fade, waits
 *   for the desk to say it is on (Blind is a WS gesture and Include a REST call, which the desk does
 *   not order), then Includes and opens the programmer; nothing reaches the stage until Blind goes
 *   off, effects included (D7).
 * - **Load** Includes and stays; its title says whether that is live or blind. **Record…** opens the
 *   Record sheet on the cue. In the ⋯ menu, **Update stage…** follows it while the one cue is edited
 *   since GO (§4.4), opening its confirm. **Duplicate** is `POST /cues/{id}/duplicate`, the copy appended to the
 *   end of its stack (D12) and opened — loose without one, and never opened from the table, which
 *   lists it; over several rows, each in row order. **Remove from stack**
 *   deletes the entry and keeps the cue. **Delete…** is the D8 confirm, one for all the rows.
 *   **⋯** holds *Add to stack…*, *Move to stack…* — the entry whole, number, follow and anchor with
 *   it — and *Copy link*.
 * - **Locked, only Blind edit works** (D8, §4.6): every other action asks to unlock, or is disabled
 *   with the reason where the lock is not the operator's to lift. Over several rows the one-cue
 *   actions are disabled with [ONE_CUE_REASON].
 *
 * The run state comes off the playhead query and the stacks off the stack list, both cached, so a
 * host hands in only the rows and the lock.
 */
export function CueActions({
  projectId,
  rows,
  host,
  locked,
  onRequestUnlock,
  lockedReason = DEFAULT_LOCKED_REASON,
  fromShowView = false,
  className,
}: CueActionsProps) {
  const navigate = useNavigate()
  const { data: programState } = useProjectProgramStateQuery(projectId)
  const running = programState?.activeStackId != null
  const blind = useProgrammerBlind()
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const runnable = (stacks ?? []).filter((s) => s.type === 'STACK')
  const { includeCue, isLoading: including } = useInclude(projectId)
  const [duplicateCue] = useDuplicateCueMutation()
  const [removeEntry] = useRemoveCueStackEntryMutation()
  const [addToStack] = useAddCueToStackMutation()
  const [moveEntry] = useMoveCueStackEntryMutation()

  // The Record sheet, the delete confirm and the unlock question live above the views, so a host
  // that goes — a live card collapsing on a GO from elsewhere — cannot take one with it.
  const dialogs = useCueActionDialogs()
  const [blindPending, setBlindPending] = useState(false)
  const [duplicating, setDuplicating] = useState(false)

  const one = rows.length === 1 ? rows[0] : null
  const edited = useCueEdited(projectId, one?.cueId ?? -1)
  const placed = rows.filter((r) => r.entryId != null && r.stackId != null)
  /** Locked with no way to unlock here: everything but Blind edit is off. */
  const lockedOut = locked && onRequestUnlock == null

  /** Run [action] — or, locked, ask to unlock instead. */
  const guarded = (action: () => void) => () => {
    if (locked) {
      if (onRequestUnlock != null) dialogs.openUnlock(onRequestUnlock)
      return
    }
    action()
  }

  const include = async (row: CueActionRow, then: 'programmer' | 'stay') => {
    const response = await includeCue(row.cueId, row.entryId)
    if (response == null) return
    if (fromShowView && row.entryId != null) setShowInclude({ projectId, cueId: row.cueId, entryId: row.entryId })
    if (then === 'programmer') navigate(`/projects/${projectId}/programmer`)
  }

  const editInProgrammer = () => {
    if (one != null) void include(one, 'programmer')
  }

  // Not guarded: Blind edit is the one action that works locked.
  const blindEdit = async () => {
    if (one == null) return
    setBlindPending(true)
    try {
      const on = await programmerBlindOn(getProgrammerFadeMs())
      if (!on) {
        toast.error('Blind did not come on, so nothing was loaded into the programmer')
        return
      }
      await include(one, 'programmer')
    } finally {
      setBlindPending(false)
    }
  }

  const load = () => {
    if (one != null) void include(one, 'stay')
  }

  const duplicate = async () => {
    if (duplicating) return
    setDuplicating(true)
    const copies = []
    for (const row of rows) {
      try {
        copies.push(await duplicateCue({ projectId, cueId: row.cueId, stackId: row.stackId }).unwrap())
      } catch {
        // Reported by errorToastMiddleware; the rest of the batch stops here.
        break
      }
    }
    setDuplicating(false)
    const copy = copies[0]
    // The table lists the copy itself, so it stays where it is.
    if (host === 'table') {
      if (copies.length === 1) toast.success(`Duplicated as “${copy.name}”${copy.stackId == null ? ', in no stack' : ''}`)
      else if (copies.length > 1) toast.success(`Duplicated ${copies.length} cues`)
    } else if (rows.length === 1 && copy != null) {
      if (copy.entryId != null && copy.stackId != null) {
        navigate(`/projects/${projectId}/show/stacks/${copy.stackId}?cue=${copy.cueId}&entry=${copy.entryId}`)
      } else {
        toast.success(`Duplicated as “${copy.name}”, in no stack`)
      }
    } else if (copies.length > 0) {
      toast.success(`Duplicated ${copies.length} cue${copies.length === 1 ? '' : 's'} to the end of their stacks`)
    }
  }

  const removeFromStack = () => {
    for (const row of placed) {
      removeEntry({ projectId, stackId: row.stackId!, entryId: row.entryId!, cueId: row.cueId })
        .unwrap()
        .catch(ignoreReportedError)
    }
  }

  const askDelete = () => {
    const seen = new Set<number>()
    const cues: CueToDelete[] = []
    for (const row of rows) {
      if (seen.has(row.cueId)) continue
      seen.add(row.cueId)
      cues.push({ id: row.cueId, name: row.cueNumber ? `Q${row.cueNumber} ${row.name}` : row.name })
    }
    dialogs.openDelete(projectId, cues)
  }

  const addTo = (stackId: number) => {
    for (const row of rows) {
      addToStack({ projectId, stackId, cueId: row.cueId }).unwrap().catch(ignoreReportedError)
    }
  }

  // Sequential, so a number clash in the target stops the rest rather than leaving a scattered half.
  const moveTo = async (toStackId: number) => {
    for (const row of placed) {
      if (row.stackId === toStackId) continue
      try {
        await moveEntry({ projectId, stackId: row.stackId!, entryId: row.entryId!, cueId: row.cueId, toStackId }).unwrap()
      } catch {
        // Reported by errorToastMiddleware — a number the target already holds, say.
        return
      }
    }
  }

  const copyLink = () => {
    if (one?.entryId == null || one.stackId == null) return
    const url = `${window.location.origin}/projects/${projectId}/show/stacks/${one.stackId}?cue=${one.cueId}&entry=${one.entryId}`
    // `navigator.clipboard` is absent on a plain-http LAN origin, where the phone runner usually is.
    void (async () => {
      try {
        await navigator.clipboard.writeText(url)
        toast.success('Link copied')
      } catch {
        toast.error('Could not copy the link')
      }
    })()
  }

  const moveChoices = one?.stackId != null ? runnable.filter((s) => s.id !== one.stackId) : runnable

  // ── Why each action is off, or null when it is on ──
  const lockReason = lockedOut ? lockedReason : null
  const oneReason = rows.length > 1 ? ONE_CUE_REASON : null
  // A cue drawn whole (the Cues table) in several places names no entry to remove or move.
  const severalPlaces = rows.some((r) => r.entryId == null && (r.placeCount ?? 0) > 1)
  const severalReason = 'In several places — open it in the stack to change one'
  const unplacedReason = severalPlaces ? severalReason : 'Not in a stack'
  const reasons = {
    edit: lockReason ?? oneReason,
    blind: oneReason,
    load: lockReason ?? oneReason,
    record: lockReason ?? oneReason,
    duplicate: lockReason ?? (duplicating ? 'Duplicating…' : null),
    remove: lockReason ?? (severalPlaces ? severalReason : placed.length === 0 ? unplacedReason : null),
    delete: lockReason,
    add: lockReason ?? (runnable.length === 0 ? 'No stack to add it to' : null),
    move:
      lockReason ??
      (severalPlaces ? severalReason : placed.length === 0 ? unplacedReason : moveChoices.length === 0 ? 'No other stack' : null),
    link: lockReason ?? oneReason ?? (one?.entryId == null ? unplacedReason : null),
  }

  const blindFace = running
  const editAction: Action = {
    key: 'edit',
    label: 'Edit in programmer',
    icon: <SquarePen className="size-3.5" />,
    title: 'Load this cue into the programmer and open it — it goes live on stage',
    onSelect: guarded(editInProgrammer),
    reason: reasons.edit,
  }
  const blindAction: Action = {
    key: 'blind',
    label: 'Blind edit in programmer',
    icon: <EyeOff className="size-3.5" />,
    title: 'Blind on, then load this cue into the programmer and open it — nothing reaches the stage',
    onSelect: () => void blindEdit(),
    reason: reasons.blind,
  }
  const [face, other] = blindFace ? [blindAction, editAction] : [editAction, blindAction]
  const pending = including || blindPending

  const programmerActions: Action[] = [
    {
      key: 'load',
      label: 'Load into programmer',
      icon: <Download className="size-3.5" />,
      title: blind
        ? 'Load this cue into the programmer and stay — Blind is on, so nothing reaches the stage'
        : 'Load this cue into the programmer and stay — it goes live on stage',
      onSelect: guarded(load),
      reason: reasons.load,
    },
    {
      key: 'record',
      label: 'Record…',
      icon: <Zap className="size-3.5" />,
      title: 'Record the programmer into this cue',
      // Named when it opens — a card's row can move on meanwhile.
      onSelect: guarded(() => one && dialogs.openRecord(projectId, one.cueId, one.name)),
      reason: reasons.record,
    },
    ...(host === 'menu' && one != null && edited
      ? [
          {
            key: 'update-stage',
            label: 'Update stage…',
            icon: <RefreshCw className="size-3.5" />,
            title: 'Send this cue’s stored values and FX to the stage, over its own fade',
            onSelect: guarded(() =>
              dialogs.openUpdateStage({ projectId, cueId: one.cueId, name: one.cueNumber ? `Q${one.cueNumber} ${one.name}` : one.name }),
            ),
            reason: lockReason,
          },
        ]
      : []),
  ]
  const stackActions: Action[] = [
    {
      key: 'duplicate',
      label: 'Duplicate',
      icon: <Copy className="size-3.5" />,
      title:
        host === 'table'
          ? 'Copy each cue to the end of its stack, or in no stack where it has none or several'
          : rows.length > 1
            ? 'Copy each cue to the end of its stack'
            : 'Copy this cue to the end of its stack and open it',
      onSelect: guarded(() => void duplicate()),
      reason: reasons.duplicate,
    },
    {
      key: 'remove',
      label: 'Remove from stack',
      icon: <ListX className="size-3.5" />,
      title: 'Take it out of this stack — the cue itself is kept',
      onSelect: guarded(removeFromStack),
      reason: reasons.remove,
    },
    {
      key: 'delete',
      label: 'Delete…',
      icon: <Trash2 className="size-3.5" />,
      title: 'Delete the cue, after listing everything that uses it',
      onSelect: guarded(askDelete),
      reason: reasons.delete,
      destructive: true,
    },
  ]

  const stackMenus = (
    <>
      <DropdownMenuSub>
        <DropdownMenuSubTrigger disabled={reasons.add != null} title={reasons.add ?? undefined}>
          <ListPlus className="size-3.5" />
          Add to stack…
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent>
          <DropdownMenuLabel className="text-xs">Share {rows.length > 1 ? 'these cues' : 'this cue'} with</DropdownMenuLabel>
          {runnable.map((s) => (
            <DropdownMenuItem key={s.id} onSelect={guarded(() => addTo(s.id))}>
              {s.name}
            </DropdownMenuItem>
          ))}
        </DropdownMenuSubContent>
      </DropdownMenuSub>
      <DropdownMenuSub>
        <DropdownMenuSubTrigger disabled={reasons.move != null} title={reasons.move ?? undefined}>
          <ArrowRightLeft className="size-3.5" />
          Move to stack…
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent>
          <DropdownMenuLabel className="text-xs">Move {rows.length > 1 ? 'these entries' : 'this entry'} to the end of</DropdownMenuLabel>
          {moveChoices.map((s) => (
            <DropdownMenuItem key={s.id} onSelect={guarded(() => void moveTo(s.id))}>
              {s.name}
            </DropdownMenuItem>
          ))}
        </DropdownMenuSubContent>
      </DropdownMenuSub>
      <DropdownMenuItem
        disabled={reasons.link != null}
        title={reasons.link ?? undefined}
        onSelect={guarded(copyLink)}
      >
        <Link className="size-3.5" />
        Copy link
      </DropdownMenuItem>
    </>
  )

  if (host === 'menu') {
    return (
      <>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label="Cue actions"
              className={cn(
                'inline-flex size-6 items-center justify-center rounded text-current hover:bg-foreground/10',
                className,
              )}
              onClick={(e) => e.stopPropagation()}
            >
              <MoreHorizontal className="size-4" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
            {[face, other].map((a) => (
              <MenuAction
                key={a.key}
                action={pending ? { ...a, reason: 'Loading the cue…' } : a}
                amber={a.key === 'blind' && blindFace}
              />
            ))}
            {programmerActions.map((a) => (
              <MenuAction key={a.key} action={a} />
            ))}
            <DropdownMenuSeparator />
            {stackActions.map((a) => (
              <MenuAction key={a.key} action={a} />
            ))}
            <DropdownMenuSeparator />
            {stackMenus}
          </DropdownMenuContent>
        </DropdownMenu>
        {/* Drawn here only where no provider is mounted. The dialogs are portaled, but React events
            still bubble through the tree, and here it runs through the phone card's header — whose
            click scrolls the Prompt Book and toggles anchor placing. */}
        <span className="contents" onClick={(e) => e.stopPropagation()}>
          {dialogs.local}
        </span>
      </>
    )
  }

  const folds = FOLDS[host]
  return (
    // 4px between controls on the bar, which carries the cell verbs too and is otherwise 12px short
    // at 600 of sheet, just above where the Show view becomes the phone runner.
    <div className={cn('flex shrink-0 items-center', host === 'strip' ? 'gap-1.5' : 'gap-1', className)} data-cue-actions={host}>
      {/* The split button: the face, then its menu holding the other edit. */}
      <div className="inline-flex shrink-0" data-split-face={face.key}>
        <Button
          size="sm"
          className={cn(
            'h-7 gap-1 rounded-r-none text-xs',
            blindFace && 'bg-amber-500 text-amber-950 hover:bg-amber-400',
          )}
          disabled={face.reason != null || pending}
          title={face.reason ?? face.title}
          aria-label={face.label}
          onClick={face.onSelect}
        >
          {pending ? <Loader2 className="size-3.5 animate-spin" /> : face.icon}
          <span className={folds.split}>{face.label}</span>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              size="sm"
              className={cn(
                'h-7 w-6 rounded-l-none border-l border-background/30 px-0',
                blindFace && 'bg-amber-500 text-amber-950 hover:bg-amber-400',
              )}
              aria-label="More ways to edit"
              disabled={other.reason != null || pending}
              title={other.reason ?? undefined}
            >
              <ChevronDown className="size-3.5" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <MenuAction action={pending ? { ...other, reason: 'Loading the cue…' } : other} />
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {programmerActions.map((a) => (
        <StripButton key={a.key} action={a} word={folds.programmer} />
      ))}
      <span aria-hidden className={cn('h-5 w-px shrink-0 bg-border', host === 'strip' && 'mx-0.5')} />
      {stackActions.map((a) => (
        <StripButton key={a.key} action={a} word={folds.stack} />
      ))}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm" className="h-7 w-7 shrink-0 px-0" aria-label="More cue actions">
            <MoreHorizontal className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">{stackMenus}</DropdownMenuContent>
      </DropdownMenu>
      {dialogs.local}
    </div>
  )
}

interface Action {
  key: string
  label: string
  icon: ReactNode
  title: string
  onSelect: () => void
  /** Why it is off; null when it is on. */
  reason: string | null
  destructive?: boolean
}

function StripButton({ action, word }: { action: Action; word: string }) {
  return (
    <Button
      variant={action.destructive ? 'ghost' : 'outline'}
      size="sm"
      className={cn(
        'h-7 shrink-0 gap-1 text-xs',
        action.destructive && 'text-destructive hover:text-destructive',
      )}
      disabled={action.reason != null}
      title={action.reason ?? action.title}
      aria-label={action.label}
      onClick={action.onSelect}
    >
      {action.icon}
      <span className={word}>{action.label}</span>
    </Button>
  )
}

function MenuAction({ action, amber = false }: { action: Action; amber?: boolean }) {
  return (
    <DropdownMenuItem
      disabled={action.reason != null}
      title={action.reason ?? action.title}
      onSelect={action.onSelect}
      className={cn(
        action.destructive && 'text-destructive focus:text-destructive',
        amber && 'text-amber-500 focus:text-amber-500',
      )}
    >
      {action.icon}
      {action.label}
    </DropdownMenuItem>
  )
}

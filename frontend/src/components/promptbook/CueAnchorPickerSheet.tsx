import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Anchor, Pencil, Plus } from 'lucide-react'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { TruncateStart } from '@/components/TruncateStart'
import { nextAvailableName } from '@/lib/cueUtils'
import type { CueAnchorDto } from '../../api/promptBooksApi'
import type { CueStack } from '../../api/cueStacksApi'
import type { FlatCue } from '../../lib/promptBook/desync'
import { groupCuesByStack } from '../../lib/promptBook/geometry'

/** Where a newly-created cue should live: an existing stack, or a new one to create first. */
export type NewCueStackChoice = { kind: 'existing'; id: number } | { kind: 'new'; name: string }

/** Select value for the "create a stack inline" option (distinct from any numeric stack id). */
const NEW_STACK = 'new'

/**
 * The cue surface for the current text selection. Opened from the floating selection
 * toolbar's "Anchor cue" action, it does this without leaving the book:
 *
 *  • Create a brand-new cue and anchor it to the selection — into an existing stack
 *    (slotted in reading order) or a new stack named inline (covering an empty show).
 *  • Anchor (or re-anchor) an existing cue to the selection — cues are grouped by stack
 *    since a prompt-book spans the whole show; already-anchored cues are marked.
 *  • Jump to an anchored cue's editor in Program (deep authoring stays there).
 */
export function CueAnchorPickerSheet({
  open,
  cueOrder,
  anchorByCue,
  stacks,
  defaultStackId,
  existingCueNames,
  preselectCueId,
  onPick,
  onCreateCue,
  onEditCue,
  onClose,
}: {
  open: boolean
  cueOrder: FlatCue[]
  anchorByCue: Map<number, CueAnchorDto>
  /** All project stacks; the create form's stack picker lists the runnable (STACK) ones. */
  stacks: CueStack[]
  /** Stack pre-selected in the create form (the live stack, the sole stack, or the first). */
  defaultStackId: number | null
  /** Existing cue names, so the create form can suggest a non-colliding "New Cue N". */
  existingCueNames: Set<string>
  /** Cue to highlight as the likely pick (armed from the rail, or overlapping the selection). */
  preselectCueId: number | null
  onPick: (cueId: number) => void
  onCreateCue: (input: {
    name: string
    cueNumber: string | null
    notes: string | null
    stack: NewCueStackChoice
  }) => void | Promise<void>
  onEditCue: (cueId: number) => void
  onClose: () => void
}) {
  const rows = useMemo(() => groupCuesByStack(cueOrder), [cueOrder])
  const runnableStacks = useMemo(() => stacks.filter((s) => s.type === 'STACK'), [stacks])
  const existingStackNames = useMemo(() => new Set(runnableStacks.map((s) => s.name)), [runnableStacks])

  const [mode, setMode] = useState<'pick' | 'create'>('pick')
  const [name, setName] = useState('')
  const [cueNumber, setCueNumber] = useState('')
  const [notes, setNotes] = useState('')
  // Chosen stack: an existing id, or NEW_STACK to create one named `newStackName`.
  const [stackChoice, setStackChoice] = useState<number | typeof NEW_STACK>(NEW_STACK)
  const [newStackName, setNewStackName] = useState('')
  // Guards the multi-step create (stack → cue → anchor → reorder) against a double-submit
  // while those requests are in flight — otherwise a second click spawns duplicates.
  const [submitting, setSubmitting] = useState(false)

  const initCreate = () => {
    setName(nextAvailableName('New Cue', existingCueNames))
    setCueNumber('')
    setNotes('')
    // Prefer an existing stack when there is one; otherwise the only path is a new stack.
    const preset = defaultStackId ?? runnableStacks[0]?.id
    setStackChoice(preset ?? NEW_STACK)
    setNewStackName(nextAvailableName('New Stack', existingStackNames))
    setMode('create')
  }

  // On each open: with no cues yet, the create form is the point — jump straight to it
  // (naming a stack inline when there are none); otherwise show the list.
  useEffect(() => {
    if (!open) return
    if (cueOrder.length === 0) initCreate()
    else setMode('pick')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // Scroll the preselected cue (armed / overlapping the selection) into view when the list opens.
  const preselectRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (open && mode === 'pick' && preselectCueId != null) {
      preselectRef.current?.scrollIntoView({ block: 'nearest' })
    }
  }, [open, mode, preselectCueId])

  const creatingStack = stackChoice === NEW_STACK
  const canCreate = name.trim() !== '' && (creatingStack ? newStackName.trim() !== '' : true)
  const handleCreate = async () => {
    if (!canCreate || submitting) return
    const stack: NewCueStackChoice = creatingStack
      ? { kind: 'new', name: newStackName.trim() }
      : { kind: 'existing', id: stackChoice as number }
    setSubmitting(true)
    try {
      await onCreateCue({
        name: name.trim(),
        cueNumber: cueNumber.trim() || null,
        notes: notes.trim() || null,
        stack,
      })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{mode === 'create' ? 'New cue for this selection' : 'Cue for this selection'}</SheetTitle>
        </SheetHeader>

        {mode === 'create' ? (
          <>
            <SheetBody>
              <div className="space-y-1.5">
                <Label htmlFor="new-cue-name">Name</Label>
                <Input
                  id="new-cue-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="h-9"
                  autoFocus
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="new-cue-num">Cue # (optional)</Label>
                <Input
                  id="new-cue-num"
                  value={cueNumber}
                  onChange={(e) => setCueNumber(e.target.value)}
                  placeholder="14A"
                  className="h-9"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="new-cue-notes">Notes (optional)</Label>
                <Textarea
                  id="new-cue-notes"
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Performance note…"
                  className="min-h-[60px] resize-y"
                />
              </div>
              <div className="space-y-1.5">
                <Label>Stack</Label>
                {runnableStacks.length > 0 && (
                  <Select
                    value={creatingStack ? NEW_STACK : String(stackChoice)}
                    onValueChange={(v) => setStackChoice(v === NEW_STACK ? NEW_STACK : Number(v))}
                  >
                    <SelectTrigger className="h-9">
                      <SelectValue placeholder="Choose a stack" />
                    </SelectTrigger>
                    <SelectContent>
                      {runnableStacks.map((s) => (
                        <SelectItem key={s.id} value={String(s.id)}>
                          {s.name}
                        </SelectItem>
                      ))}
                      <SelectItem value={NEW_STACK}>+ New stack…</SelectItem>
                    </SelectContent>
                  </Select>
                )}
                {creatingStack && (
                  <Input
                    aria-label="New stack name"
                    value={newStackName}
                    onChange={(e) => setNewStackName(e.target.value)}
                    placeholder="Stack name"
                    className="h-9"
                  />
                )}
                <p className="text-xs text-muted-foreground">
                  {creatingStack
                    ? 'A new stack is created and the cue added to it, anchored to the selection.'
                    : 'The cue is added here in reading order and anchored to the selection.'}
                </p>
              </div>
            </SheetBody>
            <SheetFooter className="flex-row justify-end gap-2">
              <Button variant="outline" onClick={() => setMode('pick')} disabled={submitting}>
                Back
              </Button>
              <Button onClick={handleCreate} disabled={!canCreate || submitting}>
                Create &amp; anchor
              </Button>
            </SheetFooter>
          </>
        ) : (
          <SheetBody className="space-y-0.5 px-2">
            <button
              type="button"
              onClick={initCreate}
              className="mb-1 flex w-full items-center gap-2 rounded-md border border-dashed border-amber-500/60 px-3 py-2 text-sm font-semibold text-amber-600 hover:bg-amber-400/10"
            >
              <Plus className="size-4" />
              New cue
            </button>

            {cueOrder.length === 0 ? (
              <p className="px-2 py-2 text-sm text-muted-foreground">No cues in the show yet.</p>
            ) : (
              rows.map((row) =>
                row.type === 'header' ? (
                  <div
                    key={`h-${row.stackId}`}
                    className="px-2 pt-3 pb-1 text-[11px] font-semibold tracking-wide text-muted-foreground/80 uppercase"
                  >
                    {row.stackName}
                  </div>
                ) : (
                  <Fragment key={row.cue.cueId}>
                    <div
                      ref={row.cue.cueId === preselectCueId ? preselectRef : undefined}
                      className={cn(
                        'flex items-center gap-1 rounded-md border',
                        row.cue.cueId === preselectCueId
                          ? 'border-amber-500 bg-amber-400/10'
                          : 'border-transparent hover:bg-muted/40',
                      )}
                    >
                      <button
                        type="button"
                        onClick={() => onPick(row.cue.cueId)}
                        className="flex min-w-0 flex-1 items-center gap-3 px-3 py-2 text-left"
                      >
                        <TruncateStart
                          text={row.cue.label}
                          title={row.cue.label}
                          className="w-16 shrink-0 font-mono text-sm font-bold"
                        />
                        <span className="min-w-0 flex-1 truncate text-sm">{row.cue.name}</span>
                        {anchorByCue.has(row.cue.cueId) ? (
                          <span className="inline-flex shrink-0 items-center gap-1 text-[11px] text-amber-600">
                            <Anchor className="size-3" />
                            re-anchor
                          </span>
                        ) : (
                          <span className="shrink-0 text-[11px] text-muted-foreground/40">not anchored</span>
                        )}
                      </button>
                      {anchorByCue.has(row.cue.cueId) && (
                        <button
                          type="button"
                          onClick={() => onEditCue(row.cue.cueId)}
                          aria-label={`Edit ${row.cue.label} in Program`}
                          className="mr-1.5 shrink-0 rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                        >
                          <Pencil className="size-3.5" />
                        </button>
                      )}
                    </div>
                  </Fragment>
                ),
              )
            )}
          </SheetBody>
        )}
      </SheetContent>
    </Sheet>
  )
}

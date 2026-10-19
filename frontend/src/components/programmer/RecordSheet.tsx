import { useEffect, useMemo, useRef, useState } from 'react'
import { useSelector } from 'react-redux'
import { Loader2, XCircle } from 'lucide-react'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { formatError } from '@/lib/formatError'
import { useCreateProjectCueStackMutation, useProjectCueStackListQuery } from '@/store/cueStacks'
import { useRecordProgrammerMutation } from '@/store/programmerOps'
import { selectTargetKeys } from '@/store/selectionSlice'
import type {
  RecordMode,
  RecordSource,
} from '@/store/programmerOps'
import { MaskPicker, maskIsEmpty, maskToWire, type MaskValue } from './maskPicker'
import { useWindowMaskReader } from '@/hooks/useWindowMaskReader'
import { RecordSceneryRow } from './RecordSceneryRow'
import { announceWrite, recordNote } from './writeToasts'
import { RecordCuePicker, usePickedCueName } from './RecordCuePicker'

export interface RecordSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** Preselect a cue to record into — set when opened from a cue row. */
  targetCueId?: number
  targetCueName?: string
  /** Preselect a stack to create in. */
  defaultCueStackId?: number
  /**
   * Open on Merge with the cue picker, nothing picked — the Record menu's "An existing cue…".
   * Ignored when [targetCueId] names the cue already.
   */
  existingCue?: boolean
}

/**
 * The stack select's *New stack…* item (S10). Not a stack id: ids are positive integers, so this
 * can never name one, and it never reaches the wire — submit creates the stack first.
 */
const NEW_STACK = 'new'

/**
 * *No stack* (cue model plan §10, 19): the new cue is loose — in no stack, found in the Cues table.
 * Never on the wire either; submit sends no `cueStackId`.
 */
const NO_STACK = 'none'

const SOURCES: { value: RecordSource; label: string; hint: string }[] = [
  {
    value: 'TOUCHED',
    label: 'Programmer',
    hint: 'What you busked — the programmer values you edited.',
  },
  {
    value: 'ALL',
    label: 'Programmer + hand-downs',
    hint: 'Also captures unpark hand-downs the programmer is holding.',
  },
  {
    value: 'STAGE_SNAPSHOT',
    label: 'Whole stage',
    hint: 'Everything on stage, including values from other cues and running effects.',
  },
]

const MODES: { value: RecordMode; label: string; hint: string }[] = [
  { value: 'CREATE', label: 'New cue', hint: 'Add a new cue to a stack, or to none.' },
  { value: 'MERGE', label: 'Merge', hint: 'Add to the cue, keeping what it already has.' },
  {
    value: 'UPDATE_EXISTING',
    label: 'Replace',
    hint: 'Replace the cue’s values. Triggers and timed effects are kept.',
  },
  { value: 'REMOVE', label: 'Remove', hint: 'Delete the cue rows these fixtures name.' },
]

/**
 * Record the programmer into a cue.
 *
 * A Sheet rather than a Dialog per this repo's convention: mask + mode + target is a form,
 * not a confirmation. What a Record wrote is said as a toast once the sheet closes, and amber
 * where it skipped anything — a Record that quietly dropped an element-keyed entry or masked half
 * the rig out should say so, because the operator's next move is to fire the cue and trust it.
 */
export function RecordSheet({
  open,
  onOpenChange,
  projectId,
  targetCueId,
  targetCueName,
  defaultCueStackId,
  existingCue = false,
}: RecordSheetProps) {
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const [record, { isLoading: recording, error: recordError, reset }] = useRecordProgrammerMutation()
  const [createStack, { isLoading: creatingStack, error: createStackError, reset: resetCreateStack }] =
    useCreateProjectCueStackMutation()
  const isLoading = recording || creatingStack

  // The programmer sheet's selection, published into the store by its list container. Read
  // from there rather than passed in: this sheet opens from the programmer toolbar and from a
  // cue card, and neither has a prop path to the list that holds the selection.
  const selectedKeys = useSelector((s: Parameters<typeof selectTargetKeys>[0]) =>
    selectTargetKeys(s, 'programmer'),
  )

  const openingMode: RecordMode = targetCueId != null || existingCue ? 'MERGE' : 'CREATE'
  const [mode, setMode] = useState<RecordMode>(openingMode)
  const [source, setSource] = useState<RecordSource>('TOUCHED')
  const [mask, setMask] = useState<MaskValue>(null)
  // The selection's mask, read once on open (apply-model plan D3): the sheet opens on it, and from
  // then on the mask is this Record's own — a change here never moves the selection.
  const readSelectionMask = useWindowMaskReader()
  const [includeFx, setIncludeFx] = useState(true)
  const [includeScenery, setIncludeScenery] = useState(true)
  const [selectedOnly, setSelectedOnly] = useState(false)
  const [stackId, setStackId] = useState<string>('')
  const [newStackName, setNewStackName] = useState('')
  const [cueId, setCueId] = useState<number | null>(targetCueId ?? null)
  const [name, setName] = useState('')
  const [cueNumber, setCueNumber] = useState('')
  // Set by a Record that landed, until the next open: the sheet animates out still mounted, and its
  // Record must not take a second press meanwhile.
  const [done, setDone] = useState(false)

  const recordableStacks = useMemo(
    () => (stacks ?? []).filter((stack) => stack.type !== 'SEPARATOR'),
    [stacks],
  )

  /** The stack a Record opens on: the preset's, else the first, else *New stack…* for a show with none. */
  const defaultStackChoice = () => String(defaultCueStackId ?? recordableStacks[0]?.id ?? NEW_STACK)

  // Once per open. RTK Query's `reset` changes identity whenever the mutation's state does, so
  // an effect that depends on it *and* calls it re-fires — here that would wipe the operator's
  // form the instant a Record failed.
  const openedRef = useRef(false)
  useEffect(() => {
    if (!open) {
      openedRef.current = false
      return
    }
    if (openedRef.current) return
    openedRef.current = true
    setMode(openingMode)
    setCueId(targetCueId ?? null)
    setSource('TOUCHED')
    setMask(readSelectionMask())
    setIncludeFx(true)
    // On whenever anything is held (the row is absent otherwise): held scenery is part of what the
    // operator busked, and the cue writes only the pieces it would not already show (D7).
    setIncludeScenery(true)
    // Off on every open, even with heads selected. Narrowing is the surprising outcome, so it
    // should be something the operator asked for on this Record, not a setting that persisted.
    setSelectedOnly(false)
    setName('')
    setCueNumber('')
    // A show with no stack yet opens on *New stack…*, so the first Record of a show needs no
    // detour through the Show view to make somewhere to put it — but only once the list says so:
    // an unloaded list is not an empty one, and the effect below fills it in when it arrives.
    setStackId(defaultCueStackId != null || stacks != null ? defaultStackChoice() : '')
    setNewStackName('')
    setDone(false)
    reset()
    resetCreateStack()
    // recordableStacks is only read for its first id on open; re-running when the list
    // reloads would stomp a stack the operator had already picked.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, targetCueId, defaultCueStackId, reset])

  // The list arriving after the sheet opened: fill the stack the open could not, and only then —
  // never over one the operator has picked.
  useEffect(() => {
    if (open && stackId === '' && stacks != null) setStackId(defaultStackChoice())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, stackId, stacks])

  const creating = mode === 'CREATE'
  const newStack = stackId === NEW_STACK
  // The stack's error only while *New stack…* is still the choice: picking an existing stack after a
  // failed create must not leave it standing over the Record that follows (submit resets it too).
  const error = (creating && newStack ? createStackError : undefined) ?? recordError
  const canSubmit =
    (creating ? (newStack ? newStackName.trim() !== '' : stackId !== '') : cueId != null) &&
    !maskIsEmpty(mask)
  const pickedCueName = usePickedCueName(projectId, creating ? null : cueId)
  // The list's name once it has loaded, the preset's until then — and the preset's only for its
  // own cue, never for one picked since.
  const intoCueName = pickedCueName ?? (cueId != null && cueId === targetCueId ? targetCueName : null)
  // Guarded on the selection as well as the checkbox: the list can unmount (or be deselected)
  // while this sheet is open, and sending an empty `targets` is a 400, not a whole-rig record.
  const scoped = selectedOnly && selectedKeys.length > 0

  // No `unwrap()`: a failure is reported by the hook's own `error`, rendered below. There is
  // nothing left to catch here — Record's one recoverable refusal was `409 CUE_EDIT_SESSION_OPEN`,
  // and backend sweep item D1 retired the sessions that produced it.
  const submit = async () => {
    if (done) return
    resetCreateStack()
    let cueStackId = creating && !newStack && stackId !== NO_STACK ? Number(stackId) : undefined
    if (creating && newStack) {
      // The stack first, and only now: a cancelled sheet leaves nothing behind (S10). It joins the
      // show order at the end, like the Show view's own *New stack*. Once made it is the selection,
      // so a Record that then fails is retried into it rather than making a second one.
      const created = await createStack({ projectId, name: newStackName.trim(), loop: false })
      if (!('data' in created) || created.data == null) return
      cueStackId = created.data.id
      setStackId(String(created.data.id))
      setNewStackName('')
    }
    const response = await record({
      projectId,
      mode,
      source,
      mask: maskToWire(mask),
      includeFx,
      scenery: includeScenery,
      targets: scoped
        ? selectedKeys.map((key) => ({ type: 'fixture' as const, key }))
        : undefined,
      cueStackId,
      cueId: creating ? undefined : (cueId ?? undefined),
      name: creating && name.trim() !== '' ? name.trim() : undefined,
      // A number is an entry's, so a cue in no stack has none to take.
      cueNumber: creating && cueStackId != null && cueNumber.trim() !== '' ? cueNumber.trim() : undefined,
    })
    if ('data' in response && response.data) {
      setDone(true)
      announceWrite(recordNote(response.data))
      onOpenChange(false)
    }
  }

  return (
    <Sheet open={open} onOpenChange={(next) => !isLoading && onOpenChange(next)}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Record</SheetTitle>
          <SheetDescription>
            {!creating && intoCueName
              ? `Write the programmer into “${intoCueName}”.`
              : 'Write the programmer into a cue.'}
          </SheetDescription>
        </SheetHeader>

        <SheetBody>
          <div className="space-y-2">
            <Label>Mode</Label>
            <ToggleGroup
              type="single"
              value={mode}
              onValueChange={(next) => next && setMode(next as RecordMode)}
              className="flex-wrap justify-start"
            >
              {MODES.map((m) => (
                <ToggleGroupItem key={m.value} value={m.value} className="text-xs">
                  {m.label}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
            <p className="text-xs text-muted-foreground">
              {MODES.find((m) => m.value === mode)?.hint}
            </p>
          </div>

          {!creating && (
            <RecordCuePicker projectId={projectId} value={cueId} onChange={setCueId} disabled={isLoading} />
          )}

          {creating && (
            <>
              <div className="space-y-2">
                <Label htmlFor="record-stack">Stack</Label>
                <Select value={stackId} onValueChange={setStackId}>
                  <SelectTrigger id="record-stack">
                    <SelectValue placeholder="Choose a stack" />
                  </SelectTrigger>
                  <SelectContent>
                    {recordableStacks.map((stack) => (
                      <SelectItem key={stack.id} value={String(stack.id)}>
                        {stack.name}
                      </SelectItem>
                    ))}
                    {recordableStacks.length > 0 && <SelectSeparator />}
                    <SelectItem value={NEW_STACK}>New stack…</SelectItem>
                    <SelectItem value={NO_STACK}>No stack</SelectItem>
                  </SelectContent>
                </Select>
                {stackId === NO_STACK && (
                  <p className="text-xs text-muted-foreground">In no stack — find it in the Cues table.</p>
                )}
              </div>
              {newStack && (
                <div className="space-y-2">
                  <Label htmlFor="record-new-stack">New stack name</Label>
                  <Input
                    id="record-new-stack"
                    value={newStackName}
                    onChange={(e) => setNewStackName(e.target.value)}
                    placeholder="Required"
                    autoFocus
                  />
                  <p className="text-xs text-muted-foreground">Created when you record.</p>
                </div>
              )}
              <div className="space-y-2">
                <Label htmlFor="record-name">Name</Label>
                <Input
                  id="record-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Auto"
                />
              </div>
              {stackId !== NO_STACK && (
                <div className="space-y-2">
                  <Label htmlFor="record-number">Cue #</Label>
                  <Input
                    id="record-number"
                    value={cueNumber}
                    onChange={(e) => setCueNumber(e.target.value)}
                    placeholder="Auto"
                  />
                </div>
              )}
            </>
          )}

          <div className="space-y-2">
            <Label>Source</Label>
            <Select value={source} onValueChange={(next) => setSource(next as RecordSource)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {SOURCES.map((s) => (
                  <SelectItem key={s.value} value={s.value}>
                    {s.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              {SOURCES.find((s) => s.value === source)?.hint}
            </p>
          </div>

          <div className="space-y-1">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={scoped}
                disabled={selectedKeys.length === 0}
                onChange={(e) => setSelectedOnly(e.target.checked)}
                className="size-4"
              />
              Selected fixtures only
              {selectedKeys.length > 0 && (
                <span className="text-muted-foreground tabular-nums">
                  ({selectedKeys.length})
                </span>
              )}
            </label>
            <p className="text-xs text-muted-foreground">
              {selectedKeys.length === 0
                ? 'Select fixtures on the programmer sheet to record just those heads.'
                : 'Record only these heads — the rest of the programmer is left out, and rows ' +
                  'the cue already holds for other fixtures are kept.'}
            </p>
          </div>

          <MaskPicker value={mask} onChange={setMask} />

          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={includeFx}
              onChange={(e) => setIncludeFx(e.target.checked)}
              className="size-4"
            />
            Record effects too
          </label>

          <RecordSceneryRow
            projectId={projectId}
            checked={includeScenery}
            onCheckedChange={setIncludeScenery}
            destination="cue"
          />

          {error != null && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}
        </SheetBody>

        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={() => void submit()} disabled={!canSubmit || isLoading || done}>
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            {isLoading ? 'Recording…' : 'Record'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

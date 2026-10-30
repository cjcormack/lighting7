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
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import type { CueTarget } from '@/api/cuesApi'
import { formatError } from '@/lib/formatError'
import { useLookListQuery } from '@/store/looks'
import { useRecordLookMutation } from '@/store/programmerOps'
import { selectTargetKeys } from '@/store/selectionSlice'
import type {
  RecordMode,
  RecordSource,
} from '@/store/programmerOps'
import { MaskPicker, maskIsEmpty, maskToWire, type MaskValue } from './maskPicker'
import { RecordSceneryRow } from './RecordSceneryRow'
import { announceWrite, recordLookNote } from './writeToasts'
import { useLocalFamilyCounts } from './useLocalFamilyCounts'
import { Badge } from '@/components/ui/badge'
import { useActiveFxQuery } from '@/store/fixtureFx'
import type { AttributeFamily } from '@/lib/attributeFamily'

export interface RecordLookSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** Preselect a Look to write into — set when opened from a library row. */
  targetLookId?: number
  /**
   * The selection to record over, when the opener is not the programmer's list — the busk view's
   * Spread tab, whose selection is the desk's (groups as groups; the route expands them). Absent,
   * the sheet reads the programmer scope's row selection as it always has.
   */
  targets?: CueTarget[]
  /** The mask to open on, in place of every attribute — the FX editor's lanes' families. */
  initialMask?: AttributeFamily[] | null
  /** FX to open ticked — the FX editor's list. Only the programmer's own are offered. */
  initialFxIds?: readonly number[]
}

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
    hint: 'Everything on stage, including values from other cues and running FX.',
  },
]

const MODES: { value: RecordMode; label: string; hint: string }[] = [
  { value: 'CREATE', label: 'New look', hint: 'Create a look from what the programmer holds.' },
  { value: 'MERGE', label: 'Merge', hint: 'Add to the look, keeping the rows it already has.' },
  {
    value: 'UPDATE_EXISTING',
    label: 'Replace',
    hint: 'Replace the look’s rows for these fixtures and attributes.',
  },
  { value: 'REMOVE', label: 'Remove', hint: 'Delete the look’s rows these fixtures name.' },
]

/**
 * Record the programmer into a Look — **the gesture that creates a bound Look**, which nothing in
 * this client could do while the only record destination was the retired palette tables. The
 * library's Recorded section said so in place of a button.
 *
 * Modelled on `RecordSheet` deliberately, down to the mode and source vocabulary: recording into a
 * cue and recording into a look are the same operation with a different destination, and an operator
 * who has learned one should not have to learn the other. Two things genuinely differ.
 *
 * The **mask is prominent rather than incidental**. A palette bank implied its own attribute — you
 * recorded a colour palette into the colour bank — and a Look has no type to imply it, so an
 * unmasked record of a busked state captures position and beam alongside the colour you meant. The
 * per-family counts are there to make that visible before it happens.
 *
 * And the **selection is the recommended path, not the exception**. A Look recorded from the whole
 * programmer names every head the programmer happens to hold, which is almost never what "Warm
 * Amber" means; a cue, by contrast, usually does want everything you busked.
 */
export function RecordLookSheet({
  open,
  onOpenChange,
  projectId,
  targetLookId,
  targets,
  initialMask,
  initialFxIds,
}: RecordLookSheetProps) {
  const { data: looks } = useLookListQuery({ projectId }, { skip: !projectId })
  const [record, { isLoading, error, reset }] = useRecordLookMutation()

  // The programmer sheet's selection, published into the store by its list container — the same
  // read `RecordSheet` makes, and for the same reason: this sheet opens from the toolbar and from
  // the library, and neither has a prop path to the list holding the selection.
  const programmerKeys = useSelector((s: Parameters<typeof selectTargetKeys>[0]) =>
    selectTargetKeys(s, 'programmer'),
  )
  // The opener's own selection wins where it has one; the programmer scope is the fallback and
  // not a union, since a busk window's Spread tab does not share the programmer list's scope.
  const selection = useMemo<CueTarget[]>(
    () => targets ?? programmerKeys.map((key) => ({ type: 'fixture' as const, key })),
    [targets, programmerKeys],
  )

  const [mode, setMode] = useState<RecordMode>(targetLookId ? 'MERGE' : 'CREATE')
  const [source, setSource] = useState<RecordSource>('TOUCHED')
  const [mask, setMask] = useState<MaskValue>(null)
  const [selectedOnly, setSelectedOnly] = useState(true)
  const [fxIds, setFxIds] = useState<number[]>([])
  const [includeScenery, setIncludeScenery] = useState(true)
  const [lookId, setLookId] = useState<string>('')
  const [name, setName] = useState('')
  const [notes, setNotes] = useState('')
  // Set by a Record that landed, until the next open: the sheet animates out still mounted, and its
  // Record must not take a second press meanwhile.
  const [done, setDone] = useState(false)

  // Once per open. RTK Query's `reset` changes identity whenever the mutation's state does, so an
  // effect depending on it *and* calling it re-fires — here that would wipe the operator's form
  // the instant a Record failed.
  const openedRef = useRef(false)
  // Read once per open: a host re-rendering meanwhile changes nothing.
  const initialRef = useRef({ initialMask, initialFxIds })
  initialRef.current = { initialMask, initialFxIds }
  useEffect(() => {
    if (!open) {
      openedRef.current = false
      return
    }
    if (openedRef.current) return
    openedRef.current = true
    setMode(targetLookId ? 'MERGE' : 'CREATE')
    setSource('TOUCHED')
    const initial = initialRef.current
    setMask(initial.initialMask ?? null)
    // On by default here, unlike `RecordSheet`: see the class doc. Still guarded by `scoped`, so
    // an empty selection falls back to the whole programmer rather than sending a 400.
    setSelectedOnly(true)
    // Effects default **off**, unlike the fixture selection. A value the operator busked is almost
    // certainly part of the look; a chase they started to see what it looked like very often is not,
    // and folding one in moves a running effect out of their hands — unless the opener named them.
    setFxIds(initial.initialFxIds != null ? [...initial.initialFxIds] : [])
    // On whenever anything is held (the row is absent otherwise): a look recorded with only the moon
    // held is a scenery look, the busk pad that flies it (D9, D10).
    setIncludeScenery(true)
    setLookId(targetLookId != null ? String(targetLookId) : '')
    setName('')
    setNotes('')
    setDone(false)
    reset()
  }, [open, targetLookId, reset])

  const creating = mode === 'CREATE'
  const canSubmit = (creating ? name.trim() !== '' : lookId !== '') && !maskIsEmpty(mask)
  // Guarded on the selection as well as the checkbox: the list can unmount (or be deselected) while
  // this sheet is open, and sending an empty `targets` is a 400, not a whole-rig record.
  const scoped = selectedOnly && selection.length > 0

  const submit = async () => {
    if (done) return
    try {
      const response = await record({
        projectId,
        mode,
        source,
        mask: maskToWire(mask),
        lookId: creating ? undefined : Number(lookId),
        name: creating ? name.trim() : undefined,
        notes: creating && notes.trim() !== '' ? notes.trim() : undefined,
        targets: scoped ? selection : undefined,
        fxIds: fxIds.length > 0 ? fxIds : undefined,
        scenery: includeScenery,
      }).unwrap()
      setDone(true)
      announceWrite(recordLookNote(response))
      onOpenChange(false)
    } catch {
      // Rendered inline from the mutation's own `error` — `recordLook` is in SILENT_ENDPOINTS.
    }
  }

  return (
    <Sheet open={open} onOpenChange={(next) => !isLoading && onOpenChange(next)}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Record look</SheetTitle>
          <SheetDescription>
            Write the programmer into a look that names its own fixtures.
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

          {creating ? (
            <>
              <div className="space-y-2">
                <Label htmlFor="record-look-name">Name</Label>
                <Input
                  id="record-look-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Warm Amber"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="record-look-notes">Notes</Label>
                <Textarea
                  id="record-look-notes"
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  rows={2}
                />
              </div>
            </>
          ) : (
            <div className="space-y-2">
              <Label htmlFor="record-look-target">Look</Label>
              <Select value={lookId} onValueChange={setLookId}>
                <SelectTrigger id="record-look-target">
                  <SelectValue placeholder="Choose a look" />
                </SelectTrigger>
                <SelectContent>
                  {(looks ?? []).map((look) => (
                    <SelectItem key={look.id} value={String(look.id)}>
                      {look.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
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
                disabled={selection.length === 0}
                onChange={(e) => setSelectedOnly(e.target.checked)}
                className="size-4"
              />
              {targets != null ? 'Selected targets only' : 'Selected fixtures only'}
              {selection.length > 0 && (
                // From the busk view the selection can hold a group, which the route expands to
                // every member — so the count is of targets there, never of heads.
                <span className="text-muted-foreground tabular-nums">
                  ({selection.length}{targets != null && selection.some((t) => t.type === 'group') ? ', groups expanded' : ''})
                </span>
              )}
            </label>
            <p className="text-xs text-muted-foreground">
              {selection.length === 0
                ? targets != null
                  ? 'Select targets on the rig band to record just those heads — recommended, ' +
                    'or the look names every head the programmer is holding.'
                  : 'Select fixtures on the values sheet to record just those heads — recommended, ' +
                    'or the look names every head the programmer is holding.'
                : 'Record only these heads. A look names its own fixtures, so this is what decides ' +
                  'where recalling it lands.'}
            </p>
          </div>

          <MaskedAttributes value={mask} onChange={setMask} />

          <RecordableFx value={fxIds} onChange={setFxIds} />

          <RecordSceneryRow
            projectId={projectId}
            checked={includeScenery}
            onCheckedChange={setIncludeScenery}
            destination="look"
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
          <Button onClick={submit} disabled={!canSubmit || isLoading || done}>
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            {isLoading ? 'Recording…' : 'Record'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/**
 * The mask picker plus the per-family counts of what the programmer is holding.
 *
 * A child component rather than a hook in the sheet, because the counts subscribe to
 * `useProgrammerRevision` — which wakes on *any* programmer change, a slider drag included. Radix
 * mounts a sheet's body only while it is open, so putting the subscription down here is what keeps
 * an always-mounted-but-closed sheet from re-rendering on every programmer event. The sheet's own
 * toolbar sits on every page.
 */
function MaskedAttributes({
  value,
  onChange,
}: {
  value: MaskValue
  onChange: (next: MaskValue) => void
}) {
  return <MaskPicker value={value} onChange={onChange} counts={useLocalFamilyCounts()} />
}

/**
 * The running programmer-band effects, tickable.
 *
 * Beside the per-family value counts because they are the *other* half of what a busked state is —
 * `RecordLookSheet` had no notion of effects at all, so a look whose whole character was a chase
 * recorded as a set of static values and quietly lost it.
 *
 * Three things here surprise people, and all three are stated on screen rather than left to be
 * discovered:
 *
 * - an **unticked effect keeps running**. Leaving one out of a look is not the same as stopping it,
 *   which is exactly why these are checkboxes and not a "take everything" toggle;
 * - **the tempo travels, the timing does not**. `LookFx` has speed-master fields and no
 *   delay/interval, so a busked "fire after 3s" becomes the *layer's* delay — per-use rather than
 *   baked in;
 * - a ticked effect is **moved**, not copied: the server takes it out of the band, because the
 *   layer applying this look starts running it and two copies would beat against each other.
 *
 * Only loose band effects are offered. One already owned by a layer belongs to that Look already,
 * and recording it into a second would duplicate a running instance rather than move it.
 */
function RecordableFx({
  value,
  onChange,
}: {
  value: number[]
  onChange: (next: number[]) => void
}) {
  const { data: effects } = useActiveFxQuery()
  const candidates = (effects ?? []).filter(
    (e) => e.programmerOwned && e.programmerLayerId == null && !e.leaving,
  )
  if (candidates.length === 0) return null

  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium">FX</span>
        <Badge variant="secondary" className="px-1.5 text-[10px] tabular-nums">
          {candidates.length}
        </Badge>
        <span className="text-xs text-muted-foreground">tempo travels, timing does not</span>
      </div>
      {candidates.map((effect) => (
        <label key={effect.id} className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            className="size-4"
            checked={value.includes(effect.id)}
            onChange={(e) =>
              onChange(
                e.target.checked
                  ? [...value, effect.id]
                  : value.filter((id) => id !== effect.id),
              )
            }
          />
          <span className="truncate">{effect.fxType}</span>
          <span className="shrink-0 font-mono text-xs text-muted-foreground">
            → {effect.attribute}
          </span>
        </label>
      ))}
      <p className="text-xs text-muted-foreground">
        An FX you leave out keeps running in the programmer — leaving it out is not stopping it.
      </p>
    </div>
  )
}

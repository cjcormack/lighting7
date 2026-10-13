import { useEffect, useMemo, useState } from 'react'
import { XCircle } from 'lucide-react'
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { formatError } from '@/lib/formatError'
import { useExtractVenueMutation } from '@/store/venues'
import type { ProjectSummary } from '@/api/projectApi'
import type { ExtractKind, ExtractVenueResult } from '@/api/venueApi'

/** The parts an extract can move, in the board's words; all of them by default (venue plan §3.5). */
const PARTS: { kind: ExtractKind; label: string }[] = [
  { kind: 'UNIVERSES', label: 'Universes' },
  { kind: 'PATCHES', label: 'Patch' },
  { kind: 'RIGGINGS', label: 'Bars' },
  { kind: 'REGIONS', label: 'Regions' },
  { kind: 'ELEMENTS', label: 'Room' },
  { kind: 'SEATING', label: 'Seating' },
  { kind: 'GROUPS', label: 'Groups' },
  { kind: 'VIEWPOINTS', label: 'Views' },
  { kind: 'PARKS', label: 'Parks' },
]
const ALL_PARTS = PARTS.map((p) => p.kind)

/** One extract's inputs as a comparable key, so a preview is only ever read against the inputs it was for. */
function argsKey(args: { projectId: number | null; name: string; include?: ExtractKind[] }): string {
  return JSON.stringify([args.projectId, args.name, args.include ?? null])
}

/** How long the inputs rest before the preview is read again. */
const PREVIEW_DEBOUNCE_MS = 350

/**
 * *Extract a venue from a project…* (venue plan §3.5; `Projects.dc.html`'s Venues row): an admin's.
 * Moves a standalone show's room, house patch, universes and bars into a new venue at v1 and puts the
 * show on it, so it loads the same fixtures at the same addresses. The preview is the extract **rolled
 * back** on the desk (`dryRun`), read again as the name, the show or the parts change, so the sheet
 * can never say something the press would not do — including a refusal (a part that would point out
 * of the venue). A sheet, not a dialog: it is a form (CLAUDE.md §Sheets vs Dialogs).
 */
export function ExtractVenueSheet({
  open,
  onOpenChange,
  shows,
  initialProjectId,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The standalone shows an extract can start from. */
  shows: ProjectSummary[]
  initialProjectId?: number
}) {
  const [projectId, setProjectId] = useState<number | null>(initialProjectId ?? null)
  const [name, setName] = useState('')
  const [parts, setParts] = useState<ExtractKind[]>(ALL_PARTS)
  const [preview, previewState] = useExtractVenueMutation()
  const [extract, { isLoading, error, reset, originalArgs: extractArgs }] = useExtractVenueMutation()
  /** The result, and the show's name as it was pressed: the list refetches it out of `shows` at once. */
  const [done, setDone] = useState<{ result: ExtractVenueResult; showName: string } | null>(null)

  // Seed the show on each open; the first standalone show when nothing named one.
  useEffect(() => {
    if (!open) return
    setProjectId(initialProjectId ?? shows[0]?.id ?? null)
    // Only on opening: a list refetch must not move the operator's choice.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initialProjectId])

  const trimmed = name.trim()
  const ready = open && projectId != null && trimmed.length > 0 && parts.length > 0
  const include = useMemo(() => (parts.length === ALL_PARTS.length ? undefined : parts), [parts])
  // What the sheet says is only ever about the inputs on screen: a preview (or a refused extract) read
  // for other inputs — the debounce still pending, a newer read in flight — is not shown, and Extract
  // waits for the preview of exactly what it would send.
  const inputs = argsKey({ projectId, name: trimmed, include })
  const previewCurrent = previewState.originalArgs != null && argsKey(previewState.originalArgs) === inputs
  const dry = previewCurrent ? previewState.data : undefined
  const previewError = previewCurrent ? previewState.error : undefined
  const reading = !previewCurrent || previewState.isLoading

  useEffect(() => {
    if (!ready || projectId == null) return
    const timer = window.setTimeout(() => {
      void preview({ projectId, name: trimmed, include, dryRun: true })
    }, PREVIEW_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [ready, projectId, trimmed, include, preview])

  const close = () => {
    setName('')
    setParts(ALL_PARTS)
    setDone(null)
    reset()
    previewState.reset()
    onOpenChange(false)
  }

  const run = async () => {
    if (projectId == null) return
    const showName = shows.find((s) => s.id === projectId)?.name ?? 'the show'
    try {
      setDone({ result: await extract({ projectId, name: trimmed, include }).unwrap(), showName })
    } catch {
      // Drawn below from the mutation's error.
    }
  }

  const extractFailure = extractArgs != null && argsKey(extractArgs) === inputs ? error : undefined
  const failure = extractFailure ?? (ready ? previewError : undefined)
  return (
    <Sheet open={open} onOpenChange={(next) => !next && close()}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>Extract a venue</SheetTitle>
          <SheetDescription>
            Moves a project&apos;s room, house patch, universes and bars into a new venue at v1, and points the project
            at it. The project loads the same fixtures at the same addresses afterwards.
          </SheetDescription>
        </SheetHeader>
        <SheetBody>
          {done ? (
            <Alert>
              <AlertDescription>
                {done.result.name} v1 is published, and {done.showName} sits on it.
              </AlertDescription>
            </Alert>
          ) : (
            <>
              <div className="space-y-2">
                <Label htmlFor="extract-from">From project</Label>
                <Select value={projectId != null ? String(projectId) : ''} onValueChange={(v) => setProjectId(Number(v))}>
                  <SelectTrigger id="extract-from">
                    <SelectValue placeholder="Choose a show" />
                  </SelectTrigger>
                  <SelectContent>
                    {shows.map((show) => (
                      <SelectItem key={show.id} value={String(show.id)}>
                        {show.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="extract-name">Venue name *</Label>
                <Input id="extract-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Commemoration Hall" autoFocus />
              </div>
              <div>
                <EditorLabel>Moves</EditorLabel>
                <ToggleGroup
                  type="multiple"
                  size="sm"
                  aria-label="Parts to move"
                  value={parts}
                  onValueChange={(next) => setParts(next as ExtractKind[])}
                  className="mt-1 h-auto w-full flex-wrap justify-start"
                >
                  {PARTS.map((part) => (
                    <ToggleGroupItem key={part.kind} value={part.kind}>
                      {part.label}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              </div>
              {!(ready && previewError) && <ExtractPreview result={ready ? dry : undefined} reading={ready && reading} />}
            </>
          )}
          {failure && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{formatError(failure)}</AlertDescription>
            </Alert>
          )}
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          {done ? (
            <Button onClick={close}>Done</Button>
          ) : (
            <>
              <Button variant="outline" onClick={close} disabled={isLoading}>
                Cancel
              </Button>
              <Button onClick={run} disabled={!ready || isLoading || reading || dry == null || previewError != null}>
                {isLoading ? 'Extracting…' : 'Extract'}
              </Button>
            </>
          )}
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

/** The dry run's answer, in counts. */
function ExtractPreview({ result, reading }: { result: ExtractVenueResult | undefined; reading: boolean }) {
  if (!result) {
    return <p className="text-sm text-muted-foreground">{reading ? 'Reading what moves…' : 'Name the venue to see what moves.'}</p>
  }
  const m = result.moved
  const counts = [
    [m.patches.length, 'fixture'],
    [m.universes.length, 'universe'],
    [m.riggings.length, 'bar'],
    [m.elements.length, 'room element'],
    [m.regions.length, 'region'],
    [m.groups.length, 'group'],
    [m.viewpoints.length, 'view'],
    [m.parks?.length ?? 0, 'parked channel'],
  ] as const
  const listed = counts.filter(([n]) => n > 0).map(([n, noun]) => `${n} ${noun}${n === 1 ? '' : 's'}`)
  return (
    <div className="space-y-1.5 rounded-md border bg-card px-3 py-2 text-sm" aria-label="What the extract moves">
      <p>
        <span className="font-medium">Into {result.name} v1:</span> {listed.length > 0 ? listed.join(' · ') : 'nothing'}.
      </p>
      {result.copiedPresetSeating.length > 0 && (
        <p className="text-muted-foreground">
          Preset seating copied into the venue, kept by the show: {result.copiedPresetSeating.join(', ')}.
        </p>
      )}
    </div>
  )
}

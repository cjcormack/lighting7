import { useEffect, useMemo, useRef, useState } from 'react'
import { CopyPlus, Download, Loader2, XCircle } from 'lucide-react'
import { AddToBuskPageMenu } from '@/components/busking/AddToBuskPageMenu'
import {
  Sheet,
  SheetBody,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { formatError } from '@/lib/formatError'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import { useLookQuery, useSaveLookMutation } from '@/store/looks'
import { useInclude } from '@/components/programmer/useInclude'
import { LookValueChip } from './lookValueChips'
import type { LookSummary } from '@/api/looksApi'
import { useLookDelete } from './useLookDelete'

export interface LookDetailSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** The summary the library already has, so the sheet has a name to show before the detail lands. */
  look: LookSummary | null
  /** Make a copy of this Look in the same project. */
  onDuplicate: (look: LookSummary) => void
}

/**
 * One **bound** Look's contents and metadata.
 *
 * Read-only about *values*, on purpose. Editing a bound Look is the record loop — Include it into
 * the programmer, change it on the rig you can see, Update it back — because a row that names a
 * fixture only means anything against that fixture, and a grid of hex codes divorced from the heads
 * they light is exactly the abstraction the programmer redesign exists to remove. Name and notes are
 * metadata and are editable here.
 *
 * The deferred half of the library gets the opposite treatment (`LookEditor`, a full value grid
 * against a synthetic fixture), and the reason is the targeting mode rather than taste: a deferred
 * row has no head to edit it on.
 *
 * **Update-back is not available yet.** Include stages the Look's literals so they can be seen and
 * busked from, but the write-back path still targets the retired palette tables, so the programmer's
 * Update is disabled for a Look target rather than allowed to write rows nothing reads. That lands
 * with the record rewrite.
 */
export function LookDetailSheet({
  open,
  onOpenChange,
  projectId,
  look,
  onDuplicate,
}: LookDetailSheetProps) {
  const lookId = look?.id ?? 0
  // `currentData`, **not** `data`. RTK Query's `data` falls back to the previous argument's result
  // while a new one is in flight, so opening one Look after another would render the first one's
  // rows under the second one's name — and the `isFetching && !detail` branch below, which was
  // written on the assumption that `detail` is *this* Look's, would never fire. Nothing destructive
  // (`save` sends only metadata, and reads the name from the summary), but this sheet exists to say
  // what a Look contains. `currentData` is this argument's own data or nothing, which is what makes
  // both the loading branch and the `?? look.…` summary fallbacks below do their job.
  const { currentData: detail, isFetching } = useLookQuery(
    { projectId, lookId },
    { skip: !open || lookId === 0 },
  )
  const [saveLook, { isLoading: isSaving, error: saveError }] = useSaveLookMutation()
  // The sheet's batch delete over one Look (library-sheets plan D13): the same question, in the same
  // dialog, as a Delete over a selection — and it reports its own refusals.
  const { run: runDelete, busy: isDeleting, dialog: deleteDialog } = useLookDelete({
    projectId,
    onDeleted: () => onOpenChange(false),
  })
  const { include, isLoading: isIncluding } = useInclude(projectId)

  const [name, setName] = useState('')
  const [notes, setNotes] = useState('')

  // Seed the form once per Look, tracked by id in a ref rather than by dependencies.
  //
  // Depending on `name`/`notes` was wrong: the summary refetches on every WS look notification,
  // so an edit in another tab re-ran this and silently replaced whatever the operator was halfway
  // through typing — and `dirty` then read false, so nothing showed that an edit had been lost.
  // The operator's in-progress text wins; closing and reopening re-seeds from the server.
  const seededLookIdRef = useRef<number | null>(null)
  useEffect(() => {
    if (look == null) {
      seededLookIdRef.current = null
      return
    }
    if (seededLookIdRef.current === look.id) return
    seededLookIdRef.current = look.id
    setName(look.name)
    setNotes(look.notes ?? '')
  }, [look])

  const rowsByTarget = useMemo(() => {
    const byTarget = new Map<
      string,
      { targetKey: string; values: { property: string; value: string }[] }
    >()
    for (const row of detail?.rows ?? []) {
      const bucket = byTarget.get(row.targetKey) ?? { targetKey: row.targetKey, values: [] }
      bucket.values.push({ property: row.propertyName, value: row.value })
      byTarget.set(row.targetKey, bucket)
    }
    return [...byTarget.values()]
  }, [detail])

  if (!look) return null

  const dirty = name.trim() !== look.name || (notes.trim() || null) !== (look.notes ?? null)

  // From `detail` where it has loaded, not from the `look` summary: the summary is a frozen
  // snapshot taken when the library row was clicked.
  const layerCount = detail?.layerCount ?? look.layerCount
  const families = detail?.families ?? look.families

  const save = async () => {
    if (name.trim() === '') return
    // Only the metadata keys. Omitting `rows` and `effects` is what tells the backend to leave the
    // contents alone — sending `[]` would clear them, and clear them out from under every cue
    // resolving through this Look.
    await saveLook({
      projectId,
      lookId: look.id,
      name: name.trim(),
      notes: notes.trim() === '' ? null : notes.trim(),
    })
      .unwrap()
      .catch(() => {
        // Rendered inline below.
      })
  }

  return (
    /* `unsavedChanges` rather than `useUnsavedChanges`: that hook reports through the Sheet's own
       context, so calling it from the component that renders the Sheet registers with nothing. Only
       the name and notes are editable here, but losing a retyped name to a stray click outside is
       still a loss. */
    <Sheet open={open} onOpenChange={onOpenChange} unsavedChanges={dirty}>
      <SheetContent className="flex flex-col sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>{look.name}</SheetTitle>
          <SheetDescription>
            {look.targetCount} fixture{look.targetCount === 1 ? '' : 's'} · used by {layerCount} cue
            layer{layerCount === 1 ? '' : 's'}
          </SheetDescription>
        </SheetHeader>

        <SheetBody>
          {families.length > 0 && (
            <div className="flex flex-wrap items-center gap-1">
              {families.map((family) => (
                <Badge key={family} variant="outline" className="text-[10px] px-1.5 py-0">
                  {FAMILY_LABELS[family].singular}
                </Badge>
              ))}
              <span className="text-[11px] text-muted-foreground">
                — derived from the rows below, not declared
              </span>
            </div>
          )}

          <div className="space-y-2">
            <Label htmlFor="look-detail-name">Name</Label>
            <Input
              id="look-detail-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="look-detail-notes">Notes</Label>
            <Textarea
              id="look-detail-notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={2}
            />
          </div>

          {saveError != null && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{formatError(saveError)}</AlertDescription>
            </Alert>
          )}

          <div className="rounded-md border bg-muted/30 p-3 text-xs text-muted-foreground">
            To see what this look holds on the rig, <strong>Include</strong> it into the programmer
            and edit the fixtures on stage. Writing those edits back into the look is not available
            yet — that arrives with the record rewrite. Every cue layering it will move with it.
          </div>

          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={isIncluding}
              onClick={() => include({ kind: 'LOOK', lookId: look.id })}
            >
              {isIncluding ? (
                <Loader2 className="size-3.5 animate-spin" />
              ) : (
                <Download className="size-3.5" />
              )}
              Include
            </Button>
            <Button variant="outline" size="sm" onClick={() => onDuplicate(look)}>
              <CopyPlus className="size-3.5" />
              Duplicate
            </Button>
            {/* Beside Include and Duplicate rather than in the footer: all three are things to *do*
                with this Look, where the footer is about this sheet. */}
            <AddToBuskPageMenu
              projectId={projectId}
              record={{ kind: 'LOOK', id: look.id, name: look.name }}
            />
          </div>

          <div className="space-y-2">
            <Label>Contents</Label>
            {isFetching && !detail ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : rowsByTarget.length === 0 ? (
              <p className="text-sm text-muted-foreground">No rows.</p>
            ) : (
              <div className="divide-y rounded-md border">
                {rowsByTarget.map((bucket) => (
                  <div key={bucket.targetKey} className="flex items-center gap-2 px-2 py-1.5">
                    <span className="min-w-0 flex-1 truncate text-sm">{bucket.targetKey}</span>
                    <div className="flex flex-wrap items-center justify-end gap-1">
                      {/* Keyed by index as well as property: a multi-element fixture holds one row
                          per element for the same property, so the property alone is not unique. */}
                      {bucket.values.map((v, index) => (
                        <LookValueChip
                          key={`${bucket.targetKey}-${v.property}-${index}`}
                          value={v.value}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

        </SheetBody>

        <SheetFooter className="flex-row justify-between">
          <Button variant="destructive" onClick={() => void runDelete([look])} disabled={isDeleting}>
            {isDeleting && <Loader2 className="size-4 animate-spin" />}
            Delete
          </Button>
          <div className="flex gap-2">
            {/* Through SheetClose, and with no onClick of its own, so it passes the discard
                question the same way Escape does. */}
            <SheetClose asChild>
              <Button variant="outline">Close</Button>
            </SheetClose>
            <Button onClick={save} disabled={!dirty || isSaving || name.trim() === ''}>
              {isSaving && <Loader2 className="size-4 animate-spin" />}
              Save
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
      {deleteDialog}
    </Sheet>
  )
}

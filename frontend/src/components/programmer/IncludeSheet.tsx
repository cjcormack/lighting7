import { useEffect, useMemo, useRef, useState } from 'react'
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { cn } from '@/lib/utils'
import { formatError } from '@/lib/formatError'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { isCueRow } from '@/api/cueStacksApi'
import { useLookListQuery } from '@/store/looks'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS } from '@/lib/attributeFamily'
import { LookPreviewSwatches } from '@/components/looks/lookValueChips'
import { useInclude } from './useInclude'
import { announceWrite, includeNote } from './writeToasts'
import { MaskPicker, maskIsEmpty, maskToWire, type MaskValue } from './maskPicker'
import { useWindowMaskReader } from '@/hooks/useWindowMaskReader'

export interface IncludeSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
}

/**
 * Pick a cue *or a look* to Include. Reached from the programmer action bar; the Show view
 * includes straight from the cue row it is already showing, so it doesn't need this picker.
 *
 * One sheet with two tabs rather than two sheets, because the programmer has exactly one include
 * target: including a look replaces an included cue, and a single picker is the honest shape
 * for a single-valued destination.
 */
export function IncludeSheet({ open, onOpenChange, projectId }: IncludeSheetProps) {
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const { data: looks } = useLookListQuery({ projectId })
  const { include, isLoading, error, resetInclude } = useInclude(projectId, {
    toastErrors: false,
  })

  // Set by an Include that landed, until the next open: the sheet animates out still mounted, and its
  // rows must not take a second press meanwhile.
  const [done, setDone] = useState(false)
  const includeAndClose = async (...args: Parameters<typeof include>) => {
    if (done) return
    const response = await include(...args)
    if (response == null) return
    setDone(true)
    announceWrite(includeNote(response))
    onOpenChange(false)
  }

  const [filter, setFilter] = useState('')
  const [mask, setMask] = useState<MaskValue>(null)
  // Include starts from the selection's mask (apply-model plan P6, D3), read once on open; a change
  // here is this Include's own and never moves the selection.
  const readSelectionMask = useWindowMaskReader()

  // Once per open — `resetInclude` closes over RTK Query's `reset`, whose identity changes
  // with the mutation's state, so re-running on its identity would clear a failed Include's
  // error the moment it appeared.
  const openedRef = useRef(false)
  useEffect(() => {
    if (!open) {
      openedRef.current = false
      return
    }
    if (openedRef.current) return
    openedRef.current = true
    setFilter('')
    setMask(readSelectionMask())
    setDone(false)
    resetInclude()
  }, [open, resetInclude, readSelectionMask])

  const needle = filter.trim().toLowerCase()

  const matches = useMemo(() => {
    return (stacks ?? [])
      .filter((stack) => stack.type !== 'SEPARATOR')
      .map((stack) => ({
        stack,
        // A marker is a row of the stack's list, not a cue, so there is nothing to include.
        cues: (stack.cues ?? []).filter(isCueRow).filter(
          (cue) =>
            needle === '' ||
            cue.name.toLowerCase().includes(needle) ||
            (cue.cueNumber ?? '').toLowerCase().includes(needle) ||
            stack.name.toLowerCase().includes(needle),
        ),
      }))
      .filter((entry) => entry.cues.length > 0)
  }, [stacks, needle])

  /**
   * Only **bound** Looks are offered. A fully-deferred one names no fixture, so there is nothing to
   * stage — the programmer has no layer to take targets from until it becomes a layer stack.
   *
   * Grouped by family, and a Look spanning two appears under both: its families are derived, so
   * filing it under a single primary one would be a guess.
   */
  const lookGroups = useMemo(
    () =>
      ATTRIBUTE_FAMILIES.map((family) => ({
        family,
        // No bound/deferred filter any more: every Look names its own fixtures, which is exactly
        // what Include needs — it loads a Look's rows onto the heads they name. The deferred half of
        // the old library is a template, and a template is applied rather than included.
        looks: (looks ?? []).filter(
          (look) =>
            look.families.includes(family) &&
            (needle === '' || look.name.toLowerCase().includes(needle)),
        ),
      })).filter((group) => group.looks.length > 0),
    [looks, needle],
  )

  return (
    <Sheet open={open} onOpenChange={(next) => !isLoading && onOpenChange(next)}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Include</SheetTitle>
          <SheetDescription>
            Load a cue’s values and effects, or a look’s contents, into the programmer — then
            edit them on stage and press Update.
          </SheetDescription>
        </SheetHeader>

        <SheetBody>
          <Input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Filter"
            aria-label="Filter cues and looks"
          />

          <MaskPicker value={mask} onChange={setMask} />

          {error != null && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}

          <Tabs defaultValue="cues">
            <TabsList className="w-full">
              <TabsTrigger value="cues">Cues</TabsTrigger>
              <TabsTrigger value="looks">Looks</TabsTrigger>
            </TabsList>

            <TabsContent value="cues" className="space-y-3">
              {matches.map(({ stack, cues }) => (
                <div key={stack.id} className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground">{stack.name}</p>
                  {cues.map((cue) => (
                    <button
                      key={cue.id}
                      type="button"
                      disabled={isLoading || done || maskIsEmpty(mask)}
                      onClick={() => void includeAndClose({ kind: 'CUE', cueId: cue.id }, maskToWire(mask))}
                      className="flex w-full items-baseline gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-accent disabled:opacity-50"
                    >
                      {cue.cueNumber && (
                        <span
                          className={cn(
                            'w-14 shrink-0 tabular-nums text-xs',
                            cue.cueNumberAuto && AUTO_CUE_NUMBER_CLASS,
                          )}
                        >
                          {cue.cueNumber}
                        </span>
                      )}
                      <span className="truncate">{cue.name}</span>
                    </button>
                  ))}
                </div>
              ))}
              {matches.length === 0 && <p className="text-sm text-muted-foreground">No cues match.</p>}
            </TabsContent>

            <TabsContent value="looks" className="space-y-3">
              {/* Including a look stages plain literals, not references — you are looking at that
                  look's own contents, and a slot referencing the thing it describes would mean
                  nothing. The mask still applies on top. */}
              {lookGroups.map(({ family, looks: group }) => (
                <div key={family} className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground">
                    {FAMILY_LABELS[family].plural}
                  </p>
                  {group.map((look) => (
                    <button
                      key={look.id}
                      type="button"
                      disabled={isLoading || done || maskIsEmpty(mask)}
                      onClick={() => void includeAndClose({ kind: 'LOOK', lookId: look.id }, maskToWire(mask))}
                      className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-accent disabled:opacity-50"
                    >
                      <span className="min-w-0 flex-1 truncate">{look.name}</span>
                      <LookPreviewSwatches preview={look.preview.slice(0, 4)} className="shrink-0" />
                      <span className="w-20 shrink-0 text-right text-xs text-muted-foreground">
                        {look.targetCount} fixture{look.targetCount === 1 ? '' : 's'}
                      </span>
                    </button>
                  ))}
                </div>
              ))}
              {lookGroups.length === 0 && (
                <p className="text-sm text-muted-foreground">
                  No looks match. Only looks that name their own fixtures can be included.
                </p>
              )}
            </TabsContent>
          </Tabs>
        </SheetBody>

        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
            Cancel
          </Button>
          {isLoading && (
            <Button disabled>
              <Loader2 className="size-4 animate-spin" />
              Including…
            </Button>
          )}
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

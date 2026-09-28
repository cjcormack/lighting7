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
import { useLookListQuery } from '@/store/looks'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS } from '@/lib/attributeFamily'
import { LookPreviewSwatches } from '@/components/looks/lookValueChips'
import { useInclude } from './useInclude'
import { MaskPicker } from './maskPicker'
import type { PropertyMaskGroup } from '@/store/programmerOps'

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
  const { include, isLoading, error, result, resetInclude } = useInclude(projectId, {
    toastErrors: false,
  })

  const [filter, setFilter] = useState('')
  const [mask, setMask] = useState<PropertyMaskGroup[]>([])

  // Once per open — `resetInclude` closes over RTK Query's `reset`, whose identity changes
  // with the mutation's state, so re-running on its identity would clear the "Included …"
  // confirmation the moment it appeared.
  const openedRef = useRef(false)
  useEffect(() => {
    if (!open) {
      openedRef.current = false
      return
    }
    if (openedRef.current) return
    openedRef.current = true
    setFilter('')
    setMask([])
    resetInclude()
  }, [open, resetInclude])

  const needle = filter.trim().toLowerCase()

  const matches = useMemo(() => {
    return (stacks ?? [])
      .filter((stack) => stack.type !== 'SEPARATOR')
      .map((stack) => ({
        stack,
        cues: (stack.cues ?? []).filter(
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
            aria-label="Filter cues and palettes"
          />

          <MaskPicker value={mask} onChange={setMask} />

          {error != null && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}

          {result && (
            <Alert>
              <AlertDescription>
                Included “{result.name}” — {result.entriesWritten} value
                {result.entriesWritten === 1 ? '' : 's'}
                {result.fxSpawned > 0 ? `, ${result.fxSpawned} effect(s)` : ''}
                {result.fxAlreadyRunning > 0
                  ? `. ${result.fxAlreadyRunning} effect(s) were already running on stage.`
                  : '.'}
              </AlertDescription>
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
                      disabled={isLoading}
                      onClick={() => include({ kind: 'CUE', cueId: cue.id }, mask)}
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
                  nothing. The mask still applies on top.

                  One-way for now: Update writes back through the retired palette tables, so it is
                  disabled while a look is the include target. Staging it to busk from still works,
                  which is what this tab is for. */}
              {lookGroups.map(({ family, looks: group }) => (
                <div key={family} className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground">
                    {FAMILY_LABELS[family].plural}
                  </p>
                  {group.map((look) => (
                    <button
                      key={look.id}
                      type="button"
                      disabled={isLoading}
                      onClick={() => include({ kind: 'LOOK', lookId: look.id }, mask)}
                      className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-accent disabled:opacity-50"
                    >
                      <span className="min-w-0 flex-1 truncate">{look.name}</span>
                      <LookPreviewSwatches preview={look.preview.slice(0, 4)} className="shrink-0" />
                      <span className="w-16 shrink-0 text-right text-xs text-muted-foreground">
                        {look.targetCount} fx
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
            {result ? 'Close' : 'Cancel'}
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

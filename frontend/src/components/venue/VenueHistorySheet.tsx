import { useState } from 'react'
import { History } from 'lucide-react'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { useVenueDiffQuery } from '@/store/venues'
import type { VenueHistory } from '@/api/venueApi'
import { changesPhrase, publishedDate } from '@/lib/venue'
import { cn } from '@/lib/utils'
import { VenueChangeList } from './VenueChangeList'
import { DiscardDraftDialog } from './DiscardDraftDialog'
import { VenueShowsList } from './VenueShowsList'

/** A side of a comparison: a version number, 0 the empty venue before v1, or the draft. */
type Side = number | 'draft'

const sideValue = (side: Side) => String(side)
const parseSide = (value: string): Side => (value === 'draft' ? 'draft' : Number(value))

/**
 * History (venue plan D3; `Venue.dc.html`'s table): the draft, then every version newest first with
 * its date, author and note. Versions are immutable; any two compare, classified as Publish
 * classifies them. *Compare* on a row shows what that version changed from the one before.
 */
export function VenueHistorySheet({
  open,
  onOpenChange,
  history,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  history: VenueHistory
}) {
  const [compare, setCompare] = useState<{ from: Side; to: Side } | null>(null)
  const [discardOpen, setDiscardOpen] = useState(false)
  const installed = history.installed ?? null

  const sides: { value: Side; label: string }[] = [
    { value: 'draft', label: 'Draft' },
    ...history.versions.map((v) => ({ value: v.number as Side, label: `v${v.number}` })),
    { value: 0, label: 'Empty (before v1)' },
  ]

  return (
    <>
      <Sheet open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) setCompare(null) }}>
        <SheetContent className="flex flex-col sm:max-w-lg">
          <SheetHeader>
            <SheetTitle className="flex items-center gap-2">
              <History className="size-4" />
              History · {history.name}
            </SheetTitle>
            <SheetDescription>Versions are immutable. Compare any two.</SheetDescription>
          </SheetHeader>
          <SheetBody>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-[10px] uppercase tracking-wider text-muted-foreground">
                  <th className="py-1 pr-2 font-medium">Version</th>
                  <th className="py-1 pr-2 font-medium">Published</th>
                  <th className="py-1 pr-2 font-medium">Note</th>
                  <th className="py-1" />
                </tr>
              </thead>
              <tbody>
                <tr className="border-t align-top">
                  <td className="py-2 pr-2 font-semibold">Draft</td>
                  <td className="py-2 pr-2 text-muted-foreground">editing</td>
                  <td className="py-2 pr-2">{changesPhrase(history.draft.changes)}</td>
                  <td className="py-1.5 text-right">
                    {installed != null && history.draft.changes > 0 ? (
                      <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => setDiscardOpen(true)}>
                        Discard
                      </Button>
                    ) : null}
                  </td>
                </tr>
                {history.versions.map((v) => (
                  <tr key={v.number} className="border-t align-top">
                    <td className="py-2 pr-2 font-semibold tabular-nums">v{v.number}</td>
                    <td className="py-2 pr-2 whitespace-nowrap">
                      {publishedDate(v.publishedAt)}
                      {v.publishedBy ? <span className="text-muted-foreground"> · {v.publishedBy}</span> : null}
                    </td>
                    <td className="py-2 pr-2 break-words">{v.note}</td>
                    <td className="py-1.5 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        className={cn(compare?.to === v.number && 'bg-accent')}
                        onClick={() => setCompare({ from: v.number - 1, to: v.number })}
                      >
                        Compare
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {history.versions.length === 0 && (
              <p className="text-sm text-muted-foreground">Nothing published yet. Publish the draft to make v1.</p>
            )}

            <VenueShowsList shows={history.shows ?? []} />

            {compare && (
              <section className="space-y-3 border-t pt-3" aria-label="Compare">
                <div className="flex items-center gap-2">
                  <EditorLabel>Compare</EditorLabel>
                  <SideSelect label="From" value={compare.from} sides={sides} onChange={(from) => setCompare({ ...compare, from })} />
                  <span className="text-muted-foreground">→</span>
                  <SideSelect label="To" value={compare.to} sides={sides} onChange={(to) => setCompare({ ...compare, to })} />
                </div>
                <CompareResult venueId={history.venueId} from={compare.from} to={compare.to} />
              </section>
            )}
          </SheetBody>
        </SheetContent>
      </Sheet>
      {installed != null && (
        <DiscardDraftDialog
          open={discardOpen}
          onOpenChange={setDiscardOpen}
          venueId={history.venueId}
          installed={installed}
          changes={history.draft.changes}
        />
      )}
    </>
  )
}

function SideSelect({
  label,
  value,
  sides,
  onChange,
}: {
  label: string
  value: Side
  sides: { value: Side; label: string }[]
  onChange: (side: Side) => void
}) {
  return (
    <Select value={sideValue(value)} onValueChange={(v) => onChange(parseSide(v))}>
      <SelectTrigger size="sm" className="h-8 w-[170px]" aria-label={label}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {sides.map((s) => (
          <SelectItem key={sideValue(s.value)} value={sideValue(s.value)}>
            {s.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/**
 * One comparison. The route reads `from → to` with the draft only on the right, so a pair given
 * the other way round is asked for in order and said in order.
 */
function CompareResult({ venueId, from, to }: { venueId: number; from: Side; to: Side }) {
  const swapped = from === 'draft' && to !== 'draft'
  const a = swapped ? (to as number) : from
  const b = swapped ? from : to
  const same = a === b
  const { data, isFetching, isError } = useVenueDiffQuery(
    { venueId, from: a === 'draft' ? 0 : a, to: b },
    { skip: same || a === 'draft' },
  )
  if (same) return <p className="text-sm text-muted-foreground">Pick two different versions.</p>
  if (isError) return <p className="text-sm text-destructive">Could not compare these versions.</p>
  if (!data) return <p className="text-sm text-muted-foreground">{isFetching ? 'Comparing…' : ''}</p>
  return (
    <div className="space-y-2">
      {swapped && <p className="text-xs text-muted-foreground">Shown from v{a} to the draft.</p>}
      <VenueChangeList diff={data} empty="These two are the same." />
    </div>
  )
}

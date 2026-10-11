import { useState } from 'react'
import { Upload } from 'lucide-react'
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
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { usePublishVenueMutation, useVenueDiffQuery } from '@/store/venues'
import type { VenueHistory } from '@/api/venueApi'
import { VenueChangeList } from './VenueChangeList'
import { DiscardDraftDialog } from './DiscardDraftDialog'
import { VenueShowsList } from './VenueShowsList'

/** The most a note may say; the desk's `VENUE_VERSION_NOTE_MAX`. */
const NOTE_MAX = 500

/**
 * *Publish vN* (venue plan D3, P2; `Venue.dc.html`'s sheet): the note, the draft's changes split
 * into **changes output** and **drawing only**, and the shows on the venue. Publishing freezes the
 * draft as the next version — immutable — and the draft starts empty again. The shows section names
 * every show pinned to the venue (session 3); a publish changes none of them.
 */
export function PublishVenueSheet({
  open,
  onOpenChange,
  history,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  history: VenueHistory
}) {
  const next = (history.installed ?? 0) + 1
  const [note, setNote] = useState('')
  const [discardOpen, setDiscardOpen] = useState(false)
  const { data: diff, isFetching } = useVenueDiffQuery(
    { venueId: history.venueId, from: history.installed ?? 0, to: 'draft' },
    { skip: !open },
  )
  const [publish, { isLoading: publishing }] = usePublishVenueMutation()
  const changes = (diff?.output.length ?? 0) + (diff?.drawing.length ?? 0)
  const canPublish = !publishing && changes > 0 && note.trim().length > 0

  const close = () => {
    setNote('')
    onOpenChange(false)
  }

  const handlePublish = async () => {
    // A refusal (the draft emptied under us, a note too long) is reported by errorToastMiddleware.
    try {
      await publish({ venueId: history.venueId, note: note.trim() }).unwrap()
    } catch {
      return
    }
    close()
  }

  return (
    <>
      <Sheet open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())} unsavedChanges={note.trim().length > 0 && !publishing}>
        <SheetContent className="flex flex-col sm:max-w-md">
          <SheetHeader>
            <SheetTitle className="flex items-center gap-2">
              <Upload className="size-4" />
              Publish {history.name} v{next}
            </SheetTitle>
            <SheetDescription>
              v{next} becomes the venue as hung. It is immutable; the draft starts empty again.
            </SheetDescription>
          </SheetHeader>
          <SheetBody>
            <div className="space-y-2">
              <Label htmlFor="venue-note">What changed?</Label>
              <Textarea
                id="venue-note"
                value={note}
                maxLength={NOTE_MAX}
                onChange={(e) => setNote(e.target.value)}
                rows={3}
                placeholder="Rehung LX3 as two LED battens; re-gelled LX2 centre…"
                autoFocus
              />
            </div>
            {diff ? (
              <VenueChangeList
                diff={diff}
                empty={`Nothing to publish: the draft is ${history.installed != null ? `v${history.installed}` : 'empty'}.`}
              />
            ) : (
              <p className="text-sm text-muted-foreground">{isFetching ? 'Reading the draft…' : ''}</p>
            )}
            <VenueShowsList shows={history.shows ?? []} next={next} />
          </SheetBody>
          <SheetFooter className="flex-row justify-between">
            <Button
              variant="ghost"
              className="text-destructive hover:text-destructive"
              disabled={history.installed == null || changes === 0 || publishing}
              onClick={() => setDiscardOpen(true)}
            >
              Discard draft
            </Button>
            <div className="flex gap-2">
              <SheetClose asChild>
                <Button variant="outline" disabled={publishing}>Cancel</Button>
              </SheetClose>
              <Button onClick={handlePublish} disabled={!canPublish}>
                {publishing ? 'Publishing…' : `Publish v${next}`}
              </Button>
            </div>
          </SheetFooter>
        </SheetContent>
      </Sheet>
      {history.installed != null && (
        <DiscardDraftDialog
          open={discardOpen}
          onOpenChange={setDiscardOpen}
          venueId={history.venueId}
          installed={history.installed}
          changes={changes}
          onDiscarded={close}
        />
      )}
    </>
  )
}

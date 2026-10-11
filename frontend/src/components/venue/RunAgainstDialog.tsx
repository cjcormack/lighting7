import { XCircle } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { formatError } from '@/lib/formatError'
import { changeLine, changeSign } from '@/lib/venue'
import { useRunVenueAgainstMutation, useVenueUpdateReviewQuery } from '@/store/venueHold'

/**
 * *Run against vN…* (venue plan D7, P5; `Update.dc.html`'s callout 2): lift the hold for this
 * session, behind a confirm that lists what moved since the show's version — the addresses its old
 * patch would now drive. Whatever it sends, the installed version's trigger and command channels stay
 * idle and guarded, and the cannons cannot be armed until the show is updated. The list is the
 * update review's **changes output** (`GET …/venue/update`), which both roles may read.
 */
export function RunAgainstDialog({
  open,
  onOpenChange,
  projectId,
  pinned,
  installed,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  pinned: number
  installed: number
}) {
  const { data: review, isFetching } = useVenueUpdateReviewQuery({ projectId }, { skip: !open, refetchOnMountOrArgChange: true })
  const [runAgainst, { isLoading, error, reset }] = useRunVenueAgainstMutation()
  const moved = review?.output ?? []
  const close = (next: boolean) => {
    if (!next) reset()
    onOpenChange(next)
  }
  return (
    <AlertDialog open={open} onOpenChange={close}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Run against v{pinned}?</AlertDialogTitle>
          <AlertDialogDescription>
            For this session the desk sends this show as v{pinned} patched it, to a hall that is v{installed}. v{installed}&apos;s
            trigger and command channels stay idle and guarded whatever it sends, and nothing can be armed or fired until
            the show is updated.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="space-y-1.5">
          <p className="text-sm font-medium">What moved since v{pinned}</p>
          {moved.length > 0 ? (
            <ul className="max-h-60 space-y-1 overflow-y-auto" aria-label={`Changes since v${pinned}`}>
              {moved.map((change) => {
                const { title } = changeLine(change)
                return (
                  <li key={`${change.table}/${change.uuid ?? change.name}`} className="flex gap-2 rounded-md border bg-card px-2.5 py-1.5 text-sm">
                    <span aria-hidden className="w-3 shrink-0 text-center font-mono font-bold text-orange-600 dark:text-orange-400">
                      {changeSign(change)}
                    </span>
                    <span className="min-w-0 truncate">{title}</span>
                  </li>
                )
              })}
            </ul>
          ) : (
            <p className="text-sm text-muted-foreground">{isFetching ? 'Reading the changes…' : 'Nothing that changes output reaches this show.'}</p>
          )}
        </div>
        {error && (
          <Alert variant="destructive">
            <XCircle className="size-4" />
            <AlertDescription>{formatError(error)}</AlertDescription>
          </Alert>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isLoading}>Keep the hold</AlertDialogCancel>
          <AlertDialogAction
            disabled={isLoading}
            onClick={async (e) => {
              e.preventDefault()
              try {
                await runAgainst({ projectId, on: true }).unwrap()
              } catch {
                return
              }
              close(false)
            }}
          >
            {isLoading ? 'Lifting…' : `Run against v${pinned}`}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

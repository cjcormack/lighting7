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
import { useDiscardVenueDraftMutation } from '@/store/venues'
import { changesPhrase } from '@/lib/venue'

/**
 * *Discard draft*, asked first: the venue's rows go back to the installed version, uuids and all,
 * and the draft's changes are lost. Shared by the Publish sheet and History.
 */
export function DiscardDraftDialog({
  open,
  onOpenChange,
  venueId,
  installed,
  changes,
  onDiscarded,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  venueId: number
  installed: number
  changes: number
  onDiscarded?: () => void
}) {
  const [discard, { isLoading }] = useDiscardVenueDraftMutation()
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Discard the draft?</AlertDialogTitle>
          <AlertDialogDescription>
            The venue goes back to v{installed} as published, and the draft&apos;s {changesPhrase(changes)} are
            lost. This cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isLoading}>Keep the draft</AlertDialogCancel>
          <AlertDialogAction
            className="bg-destructive text-white hover:bg-destructive/90"
            disabled={isLoading}
            onClick={async (e) => {
              e.preventDefault()
              // A refusal is reported by errorToastMiddleware; the dialog stays for a retry.
              try {
                await discard(venueId).unwrap()
              } catch {
                return
              }
              onOpenChange(false)
              onDiscarded?.()
            }}
          >
            {isLoading ? 'Discarding…' : 'Discard draft'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

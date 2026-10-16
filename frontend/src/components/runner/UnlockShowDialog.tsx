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

/**
 * The question a refused edit asks while the show is locked (CLAUDE.md §The show-editing lock): the
 * cue sheet's refused cell verbs and every cue action but *Blind edit* open it, and *Unlock* lifts
 * the lock through [onUnlock].
 */
export function UnlockShowDialog({
  open,
  onOpenChange,
  onUnlock,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onUnlock: () => void
}) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Unlock the show to edit?</AlertDialogTitle>
          <AlertDialogDescription>
            The show is running and locked, so a stray click cannot change it. Unlocking leaves it
            running and re-locks itself on the next GO — your selection is kept either way.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Stay locked</AlertDialogCancel>
          <AlertDialogAction
            onClick={() => {
              onOpenChange(false)
              onUnlock()
            }}
          >
            Unlock
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

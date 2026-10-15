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
import { useRigSafety, useUndoRigSafeMutation } from '@/store/rigSafety'

/**
 * *Undo* for *Make rig safe* (venue-visit plan S6, D18), asked first wherever it is offered — the
 * banner and the Grand Master panel. A stray press would strike lamps that are still cooling, minutes
 * later, when nobody is looking.
 */
export function UndoRigSafeDialog({
  projectId,
  open,
  onOpenChange,
}: {
  projectId: number
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const rig = useRigSafety()
  const [undo] = useUndoRigSafeMutation()
  const lamps = rig.undo.length
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Undo Make rig safe?</AlertDialogTitle>
          <AlertDialogDescription>
            {lamps > 0
              ? `Strikes the ${lamps === 1 ? 'lamp' : `${lamps} lamps`} it turned off, one at a time, then releases the blackout. A lamp still cooling is struck when its restrike time is up.`
              : 'Releases the blackout: there are no lamps to strike.'}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          {/* Refusals reach the screen through errorToastMiddleware; progress through the banner. */}
          <AlertDialogAction onClick={() => void undo({ projectId })}>{lamps > 0 ? 'Strike and release' : 'Release'}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

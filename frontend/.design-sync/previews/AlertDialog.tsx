import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
  Button,
} from 'lighting-desk-ui'

// An alert dialog is the desk's destructive confirmation: no close glyph, no
// click-outside dismissal, an explicit Cancel and a named action. Rendered open,
// uncontrolled; the destructive trigger it belongs to sits underneath.
export const DeleteStack = () => (
  <div className="relative h-[520px] w-full p-6">
    <AlertDialog defaultOpen>
      <AlertDialogTrigger asChild>
        <Button variant="destructive">Delete stack</Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete cue stack "Act 2"?</AlertDialogTitle>
          <AlertDialogDescription>
            This removes the stack and its 23 cues from the show. Looks and templates the cues
            layered are kept in the library. This cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction className="bg-destructive text-white hover:bg-destructive/90">
            Delete stack
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </div>
)

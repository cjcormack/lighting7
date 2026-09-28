import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Loader2 } from "lucide-react"

interface ProjectSwitchConfirmDialogProps {
  open: boolean
  currentProjectName: string
  newProjectName: string
  isSwitching: boolean
  onConfirm: () => void
  onCancel: () => void
}

export default function ProjectSwitchConfirmDialog({
  open,
  currentProjectName,
  newProjectName,
  isSwitching,
  onConfirm,
  onCancel,
}: ProjectSwitchConfirmDialogProps) {
  return (
    <Dialog open={open} onOpenChange={open => !open && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Switch Project?</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <DialogDescription>
            Are you sure you want to switch from &quot;{currentProjectName}
            &quot; to &quot;{newProjectName}&quot;?
          </DialogDescription>
          <p className="text-sm text-amber-600 dark:text-amber-500">
            This will stop all running scenes, clear effects, and black out DMX
            output. Any unsaved changes will be lost.
          </p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel} disabled={isSwitching}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={isSwitching}>
            {isSwitching ? (
              <>
                <Loader2 className="animate-spin" />
                Switching...
              </>
            ) : (
              "Switch Project"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

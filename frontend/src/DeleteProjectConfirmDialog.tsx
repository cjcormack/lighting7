import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { AlertTriangle, XCircle } from "lucide-react"

interface DeleteProjectConfirmDialogProps {
  open: boolean
  projectName: string
  isCurrent: boolean
  isDeleting: boolean
  error?: string
  onConfirm: () => void
  onCancel: () => void
}

export default function DeleteProjectConfirmDialog({
  open,
  projectName,
  isCurrent,
  isDeleting,
  error,
  onConfirm,
  onCancel,
}: DeleteProjectConfirmDialogProps) {
  return (
    <Dialog open={open} onOpenChange={open => !open && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete Project?</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          {error && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          {isCurrent && (
            <Alert>
              <AlertTriangle className="size-4" />
              <AlertDescription>
                This is the currently active project and cannot be deleted.
                Please switch to another project first.
              </AlertDescription>
            </Alert>
          )}
          <DialogDescription>
            Are you sure you want to delete the project &quot;{projectName}
            &quot;?
          </DialogDescription>
          <p className="text-sm text-destructive">
            This action cannot be undone. All scripts and fixtures in
            this project will be permanently deleted.
          </p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel} disabled={isDeleting}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={onConfirm}
            disabled={isCurrent || isDeleting}
          >
            {isDeleting ? "Deleting..." : "Delete"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

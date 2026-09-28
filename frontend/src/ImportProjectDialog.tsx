import { Dispatch, SetStateAction, useEffect, useState } from "react"
import { useNavigate } from "react-router"
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { XCircle } from "lucide-react"
import { useImportProjectMutation } from "./store/projects"

interface ImportProjectDialogProps {
  open: boolean
  setOpen: Dispatch<SetStateAction<boolean>>
}

export default function ImportProjectDialog({
  open,
  setOpen,
}: ImportProjectDialogProps) {
  const [path, setPath] = useState("")
  const [nameOverride, setNameOverride] = useState("")
  const [importProject, { isLoading, error, reset }] = useImportProjectMutation()
  const navigate = useNavigate()

  useEffect(() => {
    if (open) {
      setPath("")
      setNameOverride("")
      reset()
    }
  }, [open, reset])

  const handleClose = () => {
    setOpen(false)
  }

  const handleImport = async () => {
    try {
      const response = await importProject({
        path: path.trim(),
        nameOverride: nameOverride.trim() || null,
      }).unwrap()
      handleClose()
      navigate(`/projects/${response.projectId}`)
    } catch {
      /* error rendered below via mutation state */
    }
  }

  const isValid = path.trim().length > 0

  const fallbackByStatus: Record<number, string> = {
    400: "Folder is not a valid project export",
    409: "A project with that UUID or name already exists",
    422: "Repo format is newer than this install supports",
  }
  const errorMessage = error
    ? (
        (error as { data?: { error?: string } }).data?.error ??
        fallbackByStatus[
          (error as { status?: number }).status as number
        ] ??
        "Failed to import project"
      )
    : undefined

  return (
    <Sheet open={open} onOpenChange={(o) => !o && handleClose()}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Import Project</SheetTitle>
          <SheetDescription>
            Read a project from a folder previously written by Export.
          </SheetDescription>
        </SheetHeader>
        <SheetBody>
          {errorMessage && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{errorMessage}</AlertDescription>
            </Alert>
          )}
          <div className="space-y-2">
            <Label htmlFor="import-path">Folder path *</Label>
            <Input
              id="import-path"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder="/Users/you/Library/Application Support/lighting7/exports/..."
              autoFocus
            />
            <p className="text-xs text-muted-foreground">
              Absolute server-side path to the folder produced by Export.
            </p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="import-name-override">
              Name override (optional)
            </Label>
            <Input
              id="import-name-override"
              value={nameOverride}
              onChange={(e) => setNameOverride(e.target.value)}
              placeholder="Use a different name on import"
            />
            <p className="text-xs text-muted-foreground">
              Required if a project with the imported name already exists.
            </p>
          </div>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={handleImport} disabled={!isValid || isLoading}>
            {isLoading ? "Importing..." : "Import"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

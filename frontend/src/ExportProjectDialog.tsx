import { Dispatch, SetStateAction, useEffect, useState } from "react"
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
import { CheckCircle2, XCircle } from "lucide-react"
import { useExportProjectMutation } from "./store/projects"
import { ExportProjectResponse } from "./api/projectApi"

interface ExportProjectDialogProps {
  open: boolean
  setOpen: Dispatch<SetStateAction<boolean>>
  sourceProjectId: number
  sourceProjectName: string
}

export default function ExportProjectDialog({
  open,
  setOpen,
  sourceProjectId,
  sourceProjectName,
}: ExportProjectDialogProps) {
  const [path, setPath] = useState("")
  const [result, setResult] = useState<ExportProjectResponse | null>(null)
  const [exportProject, { isLoading, error, reset }] = useExportProjectMutation()

  useEffect(() => {
    if (open) {
      setPath("")
      setResult(null)
      reset()
    }
  }, [open, reset])

  const handleClose = () => {
    setOpen(false)
  }

  const handleExport = async () => {
    try {
      const response = await exportProject({
        id: sourceProjectId,
        path: path.trim() || null,
      }).unwrap()
      setResult(response)
    } catch {
      /* error rendered below via mutation state */
    }
  }

  const errorMessage = error
    ? (error as { data?: { error?: string } }).data?.error ??
      "Failed to export project"
    : undefined

  return (
    <Sheet open={open} onOpenChange={(o) => !o && handleClose()}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Export Project</SheetTitle>
          <SheetDescription>
            Write &quot;{sourceProjectName}&quot; to a folder of canonical JSON
            files for backup or transfer.
          </SheetDescription>
        </SheetHeader>
        <SheetBody>
          {errorMessage && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{errorMessage}</AlertDescription>
            </Alert>
          )}
          {result && (
            <Alert>
              <CheckCircle2 className="size-4" />
              <AlertDescription>
                Wrote {result.fileCount} files to{" "}
                <code className="text-xs">{result.path}</code>
              </AlertDescription>
            </Alert>
          )}
          <div className="space-y-2">
            <Label htmlFor="export-path">Folder path (optional)</Label>
            <Input
              id="export-path"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder="Leave blank to use the default per-project folder"
              autoFocus
            />
            <p className="text-xs text-muted-foreground">
              Server-side path. Default is{" "}
              <code className="text-xs">
                ~/Library/Application Support/lighting7/exports/&lt;projectUuid&gt;/
              </code>{" "}
              on macOS.
            </p>
          </div>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={isLoading}>
            {result ? "Close" : "Cancel"}
          </Button>
          {!result && (
            <Button onClick={handleExport} disabled={isLoading}>
              {isLoading ? "Exporting..." : "Export"}
            </Button>
          )}
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

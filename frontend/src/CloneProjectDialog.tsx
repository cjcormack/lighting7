import { Dispatch, SetStateAction, useState, useEffect } from "react"
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
import { Textarea } from "@/components/ui/textarea"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { XCircle } from "lucide-react"
import { toast } from "sonner"
import { useCloneProjectMutation } from "./store/projects"

const plural = (n: number, noun: string) => `${n} ${noun}${n === 1 ? "" : "s"}`

interface CloneProjectDialogProps {
  open: boolean
  setOpen: Dispatch<SetStateAction<boolean>>
  sourceProjectId: number
  sourceProjectName: string
}

export default function CloneProjectDialog({
  open,
  setOpen,
  sourceProjectId,
  sourceProjectName,
}: CloneProjectDialogProps) {
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [cloneProject, { isLoading, error, reset }] = useCloneProjectMutation()

  useEffect(() => {
    if (open) {
      setName(`Copy of ${sourceProjectName}`)
      setDescription("")
      reset()
    }
  }, [open, sourceProjectName, reset])

  const handleClose = () => {
    setName("")
    setDescription("")
    setOpen(false)
  }

  const handleClone = async () => {
    try {
      const result = await cloneProject({
        id: sourceProjectId,
        name,
        description: description || undefined,
      }).unwrap()
      // The only confirmation an operator gets that the clone was complete. A clone is
      // export → fresh uuids → import over the whole project graph, so `recordsCloned` is the
      // number that says "all of it"; the four named counts are what a show is recognised by.
      toast.success(
        `Cloned "${result.project.name}": ${plural(result.scriptsCloned, "script")}, ` +
          `${plural(result.looksCloned, "look")}, ${plural(result.cuesCloned, "cue")}, ` +
          `${plural(result.cueStacksCloned, "stack")} — ` +
          `${plural(result.recordsCloned, "record")} in all`,
      )
      handleClose()
    } catch {
      // Error handled by mutation state
    }
  }

  const isValid = name.trim().length > 0

  const errorMessage =
    error && "status" in error && error.status === 409
      ? "A project with this name already exists"
      : error
        ? "Failed to clone project"
        : undefined

  return (
    <Sheet open={open} onOpenChange={open => !open && handleClose()}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Clone Project</SheetTitle>
          <SheetDescription>
            Clone &quot;{sourceProjectName}&quot; with everything in it &mdash;
            patch, looks, cues, stacks, scripts and settings.
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
            <Label htmlFor="clone-name">New Project Name *</Label>
            <Input
              id="clone-name"
              value={name}
              onChange={e => setName(e.target.value)}
              autoFocus
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="clone-description">Description (optional)</Label>
            <Textarea
              id="clone-description"
              value={description}
              onChange={e => setDescription(e.target.value)}
              rows={2}
              placeholder="Leave empty to use the original project's description"
            />
          </div>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={handleClone} disabled={!isValid || isLoading}>
            {isLoading ? "Cloning..." : "Clone"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

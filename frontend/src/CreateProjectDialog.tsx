import { Dispatch, SetStateAction, useState } from "react"
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
import { useCreateProjectMutation } from "./store/projects"

interface CreateProjectDialogProps {
  open: boolean
  setOpen: Dispatch<SetStateAction<boolean>>
  /**
   * `VENUE` is *Add venue* (venue plan session 2): the same sheet making an empty venue, whose rows
   * become its draft. A show by default.
   */
  kind?: "SHOW" | "VENUE"
}

export default function CreateProjectDialog({
  open,
  setOpen,
  kind = "SHOW",
}: CreateProjectDialogProps) {
  const isVenue = kind === "VENUE"
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [createProject, { isLoading }] = useCreateProjectMutation()

  const handleClose = () => {
    setName("")
    setDescription("")
    setOpen(false)
  }

  const handleCreate = async () => {
    // Reported by errorToastMiddleware; keep the sheet open (and the typed name) if the create
    // didn't land.
    try {
      await createProject({
        name,
        description: description || undefined,
        ...(isVenue ? { kind } : {}),
      }).unwrap()
    } catch {
      return
    }
    handleClose()
  }

  const isValid = name.trim().length > 0

  return (
    <Sheet open={open} onOpenChange={open => !open && handleClose()}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{isVenue ? "Add Venue" : "Create New Project"}</SheetTitle>
          {isVenue && (
            <SheetDescription>
              A venue holds a hall&apos;s rig: its patch, universes, bars, room and seating. Edit it
              as a draft, and publish a version when it is as hung.
            </SheetDescription>
          )}
        </SheetHeader>
        <SheetBody>
          <div className="space-y-2">
            <Label htmlFor="project-name">{isVenue ? "Venue Name *" : "Project Name *"}</Label>
            <Input
              id="project-name"
              value={name}
              onChange={e => setName(e.target.value)}
              autoFocus
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="project-description">Description</Label>
            <Textarea
              id="project-description"
              value={description}
              onChange={e => setDescription(e.target.value)}
              rows={2}
            />
          </div>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={handleClose} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={handleCreate} disabled={!isValid || isLoading}>
            {isLoading ? "Creating..." : isVenue ? "Add venue" : "Create"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

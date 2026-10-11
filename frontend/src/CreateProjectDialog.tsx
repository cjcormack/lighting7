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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useCreateProjectMutation, useProjectListQuery } from "./store/projects"

/** The Venue select's "none": a standalone show, which holds its whole rig as every project did. */
const NO_VENUE = "none"

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
  // A new show on a venue (venue plan session 3): the minimal select — the venue picker with its
  // versions and the stalls preset is the Projects page's, in session 7.
  const [venueId, setVenueId] = useState(NO_VENUE)
  const { data: projects } = useProjectListQuery(undefined, { skip: !open || isVenue })
  const venues = (projects ?? []).filter((p) => p.kind === "VENUE")
  const [createProject, { isLoading }] = useCreateProjectMutation()

  const handleClose = () => {
    setName("")
    setDescription("")
    setVenueId(NO_VENUE)
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
        ...(!isVenue && venueId !== NO_VENUE ? { venueId: Number(venueId) } : {}),
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
          {!isVenue && venues.length > 0 && (
            <div className="space-y-2">
              <Label htmlFor="project-venue">Venue</Label>
              <Select value={venueId} onValueChange={setVenueId}>
                <SelectTrigger id="project-venue">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {venues.map((v) => (
                    <SelectItem key={v.id} value={String(v.id)}>
                      {v.name}
                    </SelectItem>
                  ))}
                  <SelectItem value={NO_VENUE}>None — standalone</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                {venueId === NO_VENUE
                  ? "The project holds its whole rig, as every project does today."
                  : "The show starts from the venue's latest published version — referenced, not copied — and adds its own on top."}
              </p>
            </div>
          )}
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

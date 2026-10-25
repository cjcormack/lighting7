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
import { useSeatingPresetsQuery } from "./store/venues"
import { publishedDate } from "./lib/venue"
import type { ProjectDetail, ProjectSummary } from "./api/projectApi"
import { plural } from "@/lib/plural"

/** The Venue select's "none": a standalone show, which holds its whole rig as every project did. */
const NO_VENUE = "none"
/** The Stalls select's "none": the show seats nothing of its own. */
const NO_SEATING = "none"

interface CreateProjectDialogProps {
  open: boolean
  setOpen: Dispatch<SetStateAction<boolean>>
  /**
   * `VENUE` is *Add venue* (venue plan session 2): the same sheet making an empty venue, whose rows
   * become its draft. A show by default.
   */
  kind?: "SHOW" | "VENUE"
  /** After the desk has made it; the switcher and ⌘K open its home here. */
  onCreated?: (project: ProjectDetail) => void
}

/**
 * *Create Project* and *Add venue* (`Projects.dc.html`'s Create sheet). A new show picks a **venue** —
 * pinned to its latest published version, the default whenever one is published — or none, a
 * standalone project that holds its whole rig as every project did. On a venue it picks its **stalls**
 * too (venue plan D6, session 7): a copy of one of the version's Preset seating blocks, the show's own,
 * or none. A venue with nothing published is listed but cannot be chosen.
 */
export default function CreateProjectDialog({
  open,
  setOpen,
  kind = "SHOW",
  onCreated,
}: CreateProjectDialogProps) {
  const isVenue = kind === "VENUE"
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  // Null until the operator chooses: the default is the first published venue, once the list is in.
  const [venuePick, setVenueId] = useState<string | null>(null)
  const [seatingPick, setSeating] = useState<string | null>(null)
  const { data: projects } = useProjectListQuery(undefined, { skip: !open || isVenue })
  const venues = (projects ?? []).filter((p) => p.kind === "VENUE")
  const published = venues.filter((v) => v.venueSummary?.version != null)
  const venueId = venuePick ?? (published[0] ? String(published[0].id) : NO_VENUE)
  const venue = venues.find((v) => String(v.id) === venueId)
  const { data: offered } = useSeatingPresetsQuery(venue?.id ?? 0, { skip: !open || venue == null })
  const presets = venue != null && offered?.venueId === venue.id ? (offered.presets ?? []) : []
  // The operator's stalls while the venue offers them; else the venue's first preset.
  const seating = seatingPick != null && (seatingPick === NO_SEATING || presets.some((p) => p.uuid === seatingPick))
    ? seatingPick
    : (presets[0]?.uuid ?? NO_SEATING)
  const [createProject, { isLoading }] = useCreateProjectMutation()

  const handleClose = () => {
    setName("")
    setDescription("")
    setVenueId(null)
    setSeating(null)
    setOpen(false)
  }

  const handleCreate = async () => {
    // Reported by errorToastMiddleware; keep the sheet open (and the typed name) if the create
    // didn't land.
    let created: ProjectDetail
    try {
      created = await createProject({
        name,
        description: description || undefined,
        ...(isVenue ? { kind } : {}),
        ...(!isVenue && venue ? { venueId: venue.id } : {}),
        ...(!isVenue && venue && seating !== NO_SEATING ? { seatingPresetUuid: seating } : {}),
      }).unwrap()
    } catch {
      return
    }
    handleClose()
    onCreated?.(created)
  }

  const isValid = name.trim().length > 0

  return (
    <Sheet open={open} onOpenChange={open => !open && handleClose()}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{isVenue ? "Add Venue" : "Create New Project"}</SheetTitle>
          <SheetDescription>
            {isVenue
              ? "A venue holds a hall's rig: its patch, universes, bars, room and seating. Edit it as a draft, and publish a version when it is as hung."
              : venues.length > 0
                ? "A show. It starts from a venue's rig and layers its own changes on top."
                : "A show, holding its whole rig."}
          </SheetDescription>
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
                    <SelectItem key={v.id} value={String(v.id)} disabled={v.venueSummary?.version == null}>
                      {venueOptionLabel(v)}
                    </SelectItem>
                  ))}
                  <SelectItem value={NO_VENUE}>None — standalone</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                {venue == null
                  ? "The project holds its whole rig, as every project does today."
                  : versionLine(venue, offered?.venueId === venue.id ? offered?.note : null)}
              </p>
            </div>
          )}
          {!isVenue && venue != null && (
            <div className="space-y-2">
              <Label htmlFor="project-seating">Stalls seating</Label>
              <Select value={seating} onValueChange={setSeating}>
                <SelectTrigger id="project-seating">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {presets.map((p) => (
                    <SelectItem key={p.uuid} value={p.uuid}>
                      {p.name} · {p.seats} seats
                    </SelectItem>
                  ))}
                  <SelectItem value={NO_SEATING}>None</SelectItem>
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                {presets.length > 0
                  ? `The venue's movable layouts: ${presets.map((p) => p.name).join(" · ")} · None. The copy is this show's; moving chairs never changes the venue or another show.`
                  : "The venue offers no movable layouts."}
                {(venue.venueSummary?.fixedSeating?.length ?? 0) > 0 &&
                  ` ${venue.venueSummary!.fixedSeating!.join(" and ")} ${venue.venueSummary!.fixedSeating!.length === 1 ? "is" : "are"} fixed seating and come${venue.venueSummary!.fixedSeating!.length === 1 ? "s" : ""} with the venue.`}
              </p>
              <StartsWith venue={venue} />
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

/** `Commemoration Hall · v5 (latest)`, or `… · no version yet` for a venue that cannot be chosen. */
function venueOptionLabel(venue: ProjectSummary): string {
  const version = venue.venueSummary?.version
  return version == null ? `${venue.name} · no version yet` : `${venue.name} · v${version} (latest)`
}

/** `v5 latest · 3 Oct 2026 · “Added house lights on U4”`. */
function versionLine(venue: ProjectSummary, note: string | null | undefined): string {
  const s = venue.venueSummary
  if (s?.version == null) return `${venue.name} has no published version yet.`
  return [`v${s.version} latest`, s.publishedAt ? publishedDate(s.publishedAt) : null, note ? `“${note}”` : null]
    .filter(Boolean)
    .join(" · ")
}

/** *What the show starts with*: the venue's rig, referenced; what the show adds, its own. */
function StartsWith({ venue }: { venue: ProjectSummary }) {
  const s = venue.venueSummary
  if (s?.version == null) return null
  const fixed = s.fixedSeating ?? []
  return (
    <p className="rounded-md border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
      <span className="font-medium text-foreground">What the show starts with.</span> The venue&apos;s rig at v{s.version} —
      {(s.patches ?? 0) === 1 ? " its house fixture" : ` all ${s.patches ?? 0} house fixtures`}, {plural(s.universes ?? 0, "universe")},{" "}
      {plural(s.riggings ?? 0, "rigging")}, the room
      {fixed.length > 0 ? ` and ${fixed.join(" and ").toLowerCase()}` : ""} — referenced, not copied. Hired kit, the set and
      the stalls seating are the show&apos;s own.
    </p>
  )
}

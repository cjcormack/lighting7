import { useState } from "react"
import { useNavigate } from "react-router"
import { AlertTriangle, Loader2, XCircle } from "lucide-react"
import { toast } from "sonner"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  RESTORE_NOTE_MAX_LENGTH,
  useCloudSyncRestoreMutation,
  useCloudSyncRestoreReviewQuery,
  type RestoreChange,
  type RestoreProject,
  type RestoreResult,
  type RestoreReview,
  type RestoreStageSize,
  type RestoreVenue,
  type RestoreVenueRef,
} from "@/store/cloudSync"
import { formatSnapshotWhen, restoreRefusalMessage, staleReview } from "@/lib/snapshotRestore"

/**
 * *Review and restore…* (sync-restore plan §4): what restoring one snapshot would bring back, take
 * away and change, read from the desk's restore rolled back, then the restore itself. A
 * `RESTORE_REVIEW_STALE` answer puts its new review in place, to confirm again. Done, it toasts
 * the counts, the push and the desk's notes, with *Undo* opening the review of the show as it stood
 * before: a link to the Sync tab's `?review=`, so it works after this dialog and its panel are gone.
 */
export function RestoreReviewDialog({
  projectId,
  sha,
  onOpenChange,
}: {
  projectId: number
  sha: string
  onOpenChange: (open: boolean) => void
}) {
  const navigate = useNavigate()
  const { data, error: reviewError, isFetching } = useCloudSyncRestoreReviewQuery(
    { projectId, sha },
    { refetchOnMountOrArgChange: true },
  )
  const [restore, { isLoading: restoring }] = useCloudSyncRestoreMutation()
  const [stale, setStale] = useState<RestoreReview | null>(null)
  const [failure, setFailure] = useState<unknown>(null)
  const [note, setNote] = useState("")

  const review = stale ?? data

  const close = (next: boolean) => {
    if (!next && restoring) return
    onOpenChange(next)
  }

  const run = async () => {
    if (!review) return
    setFailure(null)
    try {
      const result = await restore({
        projectId,
        body: { sha: review.target.sha, token: review.token, note: note.trim() || null },
      }).unwrap()
      toastRestored(projectId, review, result, () =>
        navigate(`/projects/${projectId}/settings/sync?review=${encodeURIComponent(result.before.sha)}`),
      )
      onOpenChange(false)
    } catch (err) {
      const fresh = staleReview(err)
      if (fresh) setStale(fresh)
      else setFailure(err)
    }
  }

  return (
    <Dialog open onOpenChange={close}>
      <DialogContent className="flex max-h-[85vh] flex-col sm:max-w-lg" showCloseButton={!restoring}>
        <DialogHeader>
          <DialogTitle>
            {review ? `Restore to ${formatSnapshotWhen(review.target.whenMs)}?` : "Restore a snapshot?"}
          </DialogTitle>
          {review && (
            <DialogDescription>
              <span className="font-mono">{review.target.shortSha}</span>
              {" · "}
              {review.target.installFriendlyName ?? review.target.authorName}
              {" · "}“{review.target.message.split("\n")[0]}”
            </DialogDescription>
          )}
        </DialogHeader>

        <div className="-mx-6 min-h-0 flex-1 space-y-3 overflow-y-auto px-6 text-sm">
          {stale && (
            <Alert>
              <AlertTriangle className="size-4" />
              <AlertDescription>
                The show changed since this review was read. This is what restoring would do now.
              </AlertDescription>
            </Alert>
          )}
          {review ? (
            <ReviewBody review={review} />
          ) : reviewError ? (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{restoreRefusalMessage(reviewError)}</AlertDescription>
            </Alert>
          ) : (
            <p className="flex items-center gap-2 text-muted-foreground">
              {isFetching && <Loader2 className="size-4 animate-spin" />} Reading what the restore would change…
            </p>
          )}
          {review && !review.noChanges && (
            <div className="space-y-1">
              <Label htmlFor="restore-note">Note (optional)</Label>
              <Input
                id="restore-note"
                value={note}
                maxLength={RESTORE_NOTE_MAX_LENGTH}
                placeholder="Why the show goes back"
                disabled={restoring}
                onChange={(e) => setNote(e.target.value.replace(/[\r\n]+/g, " "))}
              />
              <p className="text-xs text-muted-foreground">Added to the restore&rsquo;s commit message.</p>
            </div>
          )}
          {failure != null && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{restoreRefusalMessage(failure)}</AlertDescription>
            </Alert>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" disabled={restoring} onClick={() => close(false)}>
            Cancel
          </Button>
          <Button disabled={!review || review.noChanges || restoring} onClick={run}>
            {restoring ? (
              <>
                <Loader2 className="size-4 animate-spin" /> Restoring…
              </>
            ) : (
              "Restore"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ReviewBody({ review }: { review: RestoreReview }) {
  const notes = reviewNotes(review)
  const settings = settingsLines(review.project)
  return (
    <>
      {review.noChanges ? (
        <p data-restore-no-changes="">This snapshot is the show as it is now: there is nothing to restore.</p>
      ) : (
        <div className="grid grid-cols-3 gap-2" role="group" aria-label="What the restore changes">
          <Count n={review.counts.back} label="come back" />
          <Count n={review.counts.gone} label="go" />
          <Count n={review.counts.changed} label="change back" />
        </div>
      )}
      {review.kinds.map((kind, i) => (
        <details key={kind.folder} open={i === 0} className="rounded-md border px-3 py-2" data-restore-kind={kind.folder}>
          <summary className="cursor-pointer font-medium">
            {capitalise(kind.records.length === 1 ? kind.noun : kind.plural)} · {kind.records.length}
          </summary>
          <ul className="mt-1 space-y-0.5">
            {kind.records.map((r) => (
              <li key={r.uuid} className="flex gap-2" data-change={r.change}>
                <span className={`w-3 shrink-0 font-mono ${CHANGE_CLASS[r.change]}`} aria-hidden="true">
                  {CHANGE_MARK[r.change]}
                </span>
                <span>
                  {r.label}
                  <span className="sr-only"> {CHANGE_WORD[r.change]}</span>
                </span>
              </li>
            ))}
          </ul>
        </details>
      ))}
      {settings.length > 0 && (
        <details open className="rounded-md border px-3 py-2" data-restore-kind="project">
          <summary className="cursor-pointer font-medium">Show settings · {settings.length}</summary>
          <ul className="mt-1 space-y-0.5">
            {settings.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </details>
      )}
      {notes.map((line) => (
        <p key={line} className="text-muted-foreground" data-restore-note="">
          {line}
        </p>
      ))}
    </>
  )
}

function Count({ n, label }: { n: number; label: string }) {
  return (
    <div className="rounded-md border px-3 py-2 text-center">
      <div className="text-lg font-semibold tabular-nums">{n}</div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  )
}

const CHANGE_MARK: Record<RestoreChange, string> = { BACK: "+", GONE: "−", CHANGED: "~" }
const CHANGE_WORD: Record<RestoreChange, string> = { BACK: "comes back", GONE: "goes", CHANGED: "changes back" }
const CHANGE_CLASS: Record<RestoreChange, string> = {
  BACK: "text-green-600 dark:text-green-400",
  GONE: "text-destructive",
  CHANGED: "text-muted-foreground",
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** The show's own fields the restore writes, one line each. */
function settingsLines(project: RestoreProject | null | undefined): string[] {
  if (!project) return []
  const lines: string[] = []
  if (project.name) lines.push(`Name: “${project.name.now ?? ""}” → “${project.name.then ?? ""}”`)
  if (project.description) {
    lines.push(project.description.then ? `Description: back to “${project.description.then}”` : "Description: cleared")
  }
  if (project.stageSize) {
    lines.push(`Stage size: ${stageSize(project.stageSize.now)} → ${stageSize(project.stageSize.then)}`)
  }
  if (project.origin) {
    lines.push(`Detached from: ${venueRef(project.origin.now)} → ${venueRef(project.origin.then)}`)
  }
  return lines
}

function stageSize(size: RestoreStageSize | null | undefined): string {
  const parts = [size?.widthM, size?.depthM, size?.heightM]
  if (parts.every((p) => p == null)) return "not set"
  return `${parts.map((p) => (p == null ? "?" : String(p))).join(" × ")} m`
}

function venueRef(ref: RestoreVenueRef | null | undefined): string {
  return ref ? `${ref.name ?? "a venue"} v${ref.version}` : "none"
}

/** The review's notes, in §4's order: the live show, the venue, addresses, the kept name, sync. */
function reviewNotes(review: RestoreReview): string[] {
  const notes: string[] = []
  if (review.noChanges) return notes
  notes.push(
    review.live
      ? "This show is live. It reloads and crossfades into the snapshot, as after a sync pull. Live cues are GO'd again and the programmer is kept. Undo history (⌘Z) starts again from here."
      : "Undo history (⌘Z) for this show starts again from here.",
  )
  const venue = review.venue && venueNote(review.venue, review.live)
  if (venue) notes.push(venue)
  const addresses = review.addresses
  if (addresses && addresses.copied > 0) {
    notes.push(
      `${addresses.copied} address${addresses.copied === 1 ? "" : "es"} this desk keeps for the venue come${addresses.copied === 1 ? "s" : ""} back to the show.`,
    )
  }
  if (addresses && addresses.missing.length > 0) {
    const n = addresses.missing.length
    notes.push(
      `${addresses.missing.join(", ")} come${n === 1 ? "s" : ""} back with no address on this desk. Set ${n === 1 ? "it" : "them"} in Universes after restoring.`,
    )
  }
  if (review.project?.nameKept) {
    notes.push(`Another project is now called “${review.project.nameKept}”, so this show keeps its current name.`)
  }
  if (!review.sync.repo) {
    notes.push("No repository is attached: the restore stays on this desk.")
  } else {
    const last = review.sync.lastSyncedAt
      ? `Last synced ${formatSnapshotWhen(Date.parse(review.sync.lastSyncedAt))}.`
      : "Not synced yet."
    if (review.sync.push) {
      notes.push(`${last} The desk syncs before restoring and pushes the restore after, so other desks take it on their next sync.`)
    } else {
      notes.push(`${last} The desk syncs before restoring.`)
      notes.push("This desk doesn't push, so the restore stays on this desk.")
    }
  }
  return notes
}

function sameRef(a: RestoreVenueRef | null | undefined, b: RestoreVenueRef | null | undefined): boolean {
  if (a == null || b == null) return a == b
  return a.uuid === b.uuid && a.version === b.version
}

function venueNote(venue: RestoreVenue, live: boolean): string | null {
  const { now, then } = venue
  if (sameRef(now, then)) return null
  const name = (ref: RestoreVenueRef) => ref.name ?? "the venue"
  if (then == null) {
    return `Venue: the snapshot is from before this show was on ${now ? name(now) : "a venue"}. It comes off the venue, with its rig as its own.`
  }
  let line =
    now == null
      ? `Venue: back onto ${name(then)} v${then.version}.`
      : now.uuid === then.uuid
        ? `Venue: back to ${name(then)} v${then.version} (now v${now.version}).`
        : `Venue: back onto ${name(then)} v${then.version}, from ${name(now)} v${now.version}.`
  if (venue.behind === "OUTPUT") {
    line +=
      live && venue.runAgainst
        ? ` Later versions change output, so this show will be behind its venue. It keeps running against v${then.version} now and opens held next time, until you update it.`
        : " Later versions change output, so this show will be behind its venue and open held until you update it."
  } else if (venue.behind === "DRAWING") {
    line += " Later versions change only what is drawn, so this show will be behind its venue."
  }
  return line
}

function toastRestored(projectId: number, review: RestoreReview, result: RestoreResult, undo: () => void) {
  const c = result.counts
  const total = c.back + c.gone + c.changed
  const pushed = (result.sync?.pushed ?? 0) > 0
  const changed = [
    total > 0 ? `${total} record${total === 1 ? "" : "s"} changed` : null,
    settingsLines(review.project).length > 0 ? "show settings changed" : null,
    review.venue && !sameRef(review.venue.now, review.venue.then) ? "venue version changed" : null,
  ].filter((part) => part != null)
  const summary = capitalise(changed.join(" · ")) + (pushed ? " · pushed to the repo" : "")
  // The sync's own `cloudSyncDone` toast shares this id, so the two are one toast.
  toast.success(`Restored to ${formatSnapshotWhen(review.target.whenMs)}`, {
    id: `cloud-sync-${projectId}`,
    duration: 15_000,
    description: [summary, ...result.notes].join("\n"),
    classNames: { description: "whitespace-pre-line" },
    action: { label: "Undo", onClick: undo },
  })
}

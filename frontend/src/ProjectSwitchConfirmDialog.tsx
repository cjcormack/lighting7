import { useEffect, useState } from "react"
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
import type { ProjectSummary } from "./api/projectApi"
import { switchConfirmOf, type SwitchConfirm } from "./lib/projectSwitch"
import { useProjectListQuery, useSetCurrentProjectMutation } from "./store/projects"
import { useRigSafety } from "./store/rigSafety"

interface ProjectSwitchConfirmDialogProps {
  /** The project to put on the rig; the dialog is open while it is set. */
  project: Pick<ProjectSummary, "id" | "name"> | null
  onClose: () => void
}

/**
 * The one confirmation every *Make current* goes through (navigation plan §4.3): the switcher, ⌘K,
 * the `NotOnRig` page and the Projects card. It owns the switch, and says what the switch does —
 * the outgoing show stops and every channel goes dark but the incoming show's parks — with a line
 * for each consequence that applies (`switchConfirmOf`).
 *
 * The incoming project's facts (its parks, its hold, a blackout left on it) are read from the project
 * list, which is refetched as the dialog opens so the count is the desk's now. A refusal is toasted
 * by `errorToastMiddleware`.
 */
export default function ProjectSwitchConfirmDialog({ project, onClose }: ProjectSwitchConfirmDialogProps) {
  // The last project asked about, so the words stay put while the dialog animates closed.
  const [shown, setShown] = useState(project)
  if (project != null && project.id !== shown?.id) setShown(project)

  return (
    <Dialog open={project != null} onOpenChange={(next) => !next && onClose()}>
      <DialogContent data-switch-confirm="">
        {shown && <SwitchConfirmBody project={shown} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

/** Mounted only while the dialog is, so a closed one subscribes to nothing. */
function SwitchConfirmBody({
  project,
  onClose,
}: {
  project: Pick<ProjectSummary, "id" | "name">
  onClose: () => void
}) {
  const { data: projects, refetch } = useProjectListQuery()
  const safety = useRigSafety()
  const [setCurrentProject, { isLoading: isSwitching }] = useSetCurrentProjectMutation()

  useEffect(() => {
    void refetch()
  }, [refetch])

  const incoming = projects?.find((p) => p.id === project.id) ?? { ...project, isCurrent: false, description: null }
  const current = projects?.find((p) => p.isCurrent) ?? null
  // Frozen at the press: the switch refetches the list before it answers, and the live read would
  // then describe the incoming show replacing itself while "Switching…" and the close draw.
  const [pressed, setPressed] = useState<SwitchConfirm | null>(null)
  const confirm = pressed ?? switchConfirmOf(incoming, current, safety)

  const handleConfirm = async () => {
    setPressed(confirm)
    try {
      await setCurrentProject(project.id).unwrap()
    } catch {
      // Toasted by errorToastMiddleware.
    }
    onClose()
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>{confirm.title}</DialogTitle>
      </DialogHeader>
      <div className="space-y-3">
        <DialogDescription>{confirm.body}</DialogDescription>
        {confirm.lines.map((line) => (
          <p key={line.kind} data-switch-line={line.kind} className="text-sm text-amber-700 dark:text-amber-400">
            {line.text}
          </p>
        ))}
      </div>
      <DialogFooter>
        <Button variant="outline" onClick={onClose} disabled={isSwitching}>
          Cancel
        </Button>
        <Button onClick={() => void handleConfirm()} disabled={isSwitching}>
          {isSwitching ? (
            <>
              <Loader2 className="animate-spin" />
              Switching…
            </>
          ) : (
            confirm.confirmLabel
          )}
        </Button>
      </DialogFooter>
    </>
  )
}

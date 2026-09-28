import { Navigate, useParams } from 'react-router'
import { Card } from '@/components/ui/card'
import { Loader2 } from 'lucide-react'
import { ShowHeader } from '@/components/ShowHeader'
import { ImmersiveEscape } from '@/components/ImmersiveEscape'
import { useShowBarProps } from '@/hooks/useShowBarProps'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { BuskingView } from '../components/busking/BuskingView'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

/** Bare `/busk` → the current project's busk view. Mirrors `ShowRedirect` and `ProgrammerRedirect`. */
export function BuskRedirect() {
  return <CurrentProjectRedirect to="busk" />
}

/**
 * Busk — the pad-first performance surface, and the fourth live view.
 *
 * It was `/projects/:id/fx`, a breadcrumb band over the pad grid with no show chrome at all: an
 * operator busking here could not see the cue they were on and could not GO. It is a live view in
 * every sense the other three are, so it takes the same header from the same hook — but **no
 * `ShowBar`, on any board** (busk-chrome plan D1): the bar was 56px of chrome above a rig band that
 * wanted the height, and the wrong shape of it for this view. What the bar carried is the side
 * sheet's **Show tab** now — the phone runner, `components/busking/ShowTab.tsx` — fed from the
 * `useShowBarProps` call this route still makes, so the tab, a pad press and the fold's live cue
 * number all act on one transport; the route hands that result to the view as `show`. Show and
 * the Prompt Book keep their bars; the programmer never had one.
 *
 * Two things it deliberately does **not** do:
 *
 *  - Bind transport keys (D5). Space on a focused pad activates the pad, and a key that also fired
 *    GO would be two effects from one press on a live rig. GO is the tab's footer, a MIDI `go`
 *    binding, or the Show view one pill away. There is no `showShortcuts` to answer any more, and
 *    nothing here calls `useTransportKeys`.
 *  - Pass `canOperate`. GO must work from a busk pad — that is the busk plan's D9, and the
 *    show-editing lock is a stray-click guard for editing surfaces rather than a transport gate.
 *
 * `frameRateProgress: false` for the reason `ProgrammerPage` passes it. The tab reads `transport`'s
 * *cursors*, which move once per cue, and its Current card reads its own fade through `useCueFade`
 * — nothing here reads `fadeProgress`, so the flag still keeps a running fade from re-rendering the
 * whole pad grid every frame.
 */
export function ProjectBusk() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const show = useShowBarProps(projectIdNum, { frameRateProgress: false })
  const { showHeaderProps } = show

  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    // Deliberately without the search string, unlike `/program*` → `/show`: `?page=` names a busk
    // page **id**, which is scoped to a project, so carrying it to another one would name nothing.
    return <Navigate to={`/projects/${currentProject.id}/busk`} replace />
  }

  // Both arms draw the immersive glyph over the card: with no header there would otherwise be no
  // way back on a touch screen (`ImmersiveEscape`).
  if (projectLoading || currentLoading) {
    return (
      <>
        <ImmersiveEscape />
        <Card className="m-4 p-4 flex items-center justify-center">
          <Loader2 className="size-6 animate-spin" />
        </Card>
      </>
    )
  }

  if (!project) {
    return (
      <>
        <ImmersiveEscape />
        <Card className="m-4 p-4 text-center text-muted-foreground">
          Project not found
        </Card>
      </>
    )
  }

  return (
    <div className="flex h-full flex-col">
      <ShowHeader
        view="busk"
        projectId={projectIdNum}
        projectName={project.name}
        {...showHeaderProps}
      />
      {/* No bar: the Show tab in the side sheet is where the transport, DBO and the programmer
          chip live on this view (busk-chrome plan D1). */}
      <BuskingView projectId={projectIdNum} show={show} />
    </div>
  )
}

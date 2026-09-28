import { useLocation, useParams, Navigate } from 'react-router'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

/**
 * Redirects for paths that no longer name a view.
 *
 * The rest of `routes/` follows one rule: a module owns a resource, and any redirect that *resolves*
 * that resource — bare `/show` → the current project's show, `/fixtures` → the current project's
 * fixtures — lives beside the page it resolves to. Those redirects are part of the resource.
 *
 * The ones here are not. Each is a path that was retired when two views merged or a view was
 * renamed, kept alive only so an old bookmark, a Cmd+K deep link or muscle memory lands somewhere.
 * They have no page of their own to sit next to, and parking them in whichever module happens to be
 * the destination is how `LegacyProgramRedirect` ended up in `ProgrammerPage.tsx` — reproducing the
 * exact `/program` vs `/programmer` confusion in the file layout.
 *
 * Every target here is byte-identical to what it was before the collection. `?cue=` deep links are
 * an external contract, minted by the Prompt Book's "Edit cue" rail card, so the search string is
 * carried across wherever the original did.
 *
 * The project-less spellings delegate to `CurrentProjectRedirect` rather than re-implementing the
 * wait-then-navigate body, for the reason its own doc comment gives (FS-DUP-REDIRECTS): it is the
 * single home for "follow the current project", and a second copy is a second thing to keep in
 * step with its loading state.
 */

/** `/projects/:id/<anything retired>` → `/projects/:id/show`, or the current project's show. */
function ToShow() {
  const { projectId } = useParams()
  if (projectId) {
    return <Navigate to={`/projects/${projectId}/show`} replace />
  }
  return <CurrentProjectRedirect to="show" />
}

/**
 * `/projects/:id/run` → `/projects/:id/show`.
 *
 * Run and Show merged in desk-simplification session 2b. They were never different destinations —
 * the only real distinction was whether a stray click can change the show, which is a *mode*, and
 * one the Prompt Book already modelled well. So the lock came across instead of the route, and
 * `/show` now serves both jobs: locked it is the runner, unlocked it is the editor, and both levels
 * of the view survive in both modes.
 *
 * Keeping the redirect rather than deleting the path outright: `/run` was never an external
 * contract the way `?cue=` is, but Cmd+K deep links, bookmarks and muscle memory all exist, and a
 * redirect is cheaper than any of them being a blank page.
 */
export function LegacyRunRedirect() {
  return <ToShow />
}

/**
 * Legacy `/cue-stacks` and `/projects/:id/cue-stacks` → `/show`.
 *
 * These once pointed at Run, and `/show` used to redirect here as well — the *playback* view was
 * called Show before it became Run. Both names now name one view, so there is no longer a
 * distinction for an old link to land on the wrong side of.
 */
export function LegacyCueStacksRedirect() {
  return <ToShow />
}

/**
 * Back-compat for the removed FX Cues view. `/cues`, `/cues/all`, `/cues/standalone` →
 * `/show`; `/cues/stacks/:stackId` → `/show/stacks/:stackId`.
 */
export function CuesLegacyRedirect() {
  const { projectId, stackId } = useParams()
  const target = stackId
    ? `/projects/${projectId}/show/stacks/${stackId}`
    : `/projects/${projectId}/show`
  return <Navigate to={target} replace />
}

/**
 * `/fx` and `/projects/:id/fx` → the busk view.
 *
 * The pad grid was called FX for as long as it was one thing among several the page could have
 * been about. It is the busking surface and nothing else, so it is named for what an operator
 * stands at it to do — and `/fx` sat one hyphen away from `/fx-library`, a genuinely different
 * destination that `Layout`'s route match got wrong twice (see `lib/navMatch.ts`).
 *
 * Here rather than in `Busk.tsx` because `/fx` no longer names a view: `BuskRedirect` resolves the
 * *resource* ("which project's busk view?") and belongs beside the page, while this one only keeps
 * a retired path alive. No search string to carry — `/fx` never took query params.
 */
export function LegacyFxRedirect() {
  const { projectId } = useParams()
  if (projectId) {
    return <Navigate to={`/projects/${projectId}/busk`} replace />
  }
  return <CurrentProjectRedirect to="busk" />
}

/**
 * `/projects/:id/program*` → `/projects/:id/show*`.
 *
 * Program was renamed to Show when the programmer became a page of its own — two live views one
 * letter apart was the collision that kept the programmer out of the nav last time. The search
 * string is carried across because `?cue=` deep links are how the Prompt Book's "Edit cue"
 * reaches a specific cue.
 */
export function LegacyProgramRedirect() {
  const { projectId, stackId } = useParams()
  const { search } = useLocation()
  const base = stackId
    ? `/projects/${projectId}/show/stacks/${stackId}`
    : `/projects/${projectId}/show`
  return <Navigate to={`${base}${search}`} replace />
}

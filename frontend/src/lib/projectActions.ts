import type { ProjectSummary } from '../api/projectApi'
import type { VenueHistory } from '../api/venueApi'
import { makeCurrentRefusal, venueMissing } from './projectSwitch'

/**
 * Every project action, defined once (navigation plan §6, P3): its label, who sees it, whether it
 * can run and why not, and what it does. The Projects card's ⋮, the home's ⋮, the switcher (its
 * *rig* group) and ⌘K's Project group all render from [projectActions], so an action cannot again
 * exist in one place only, nor be refused in one place and offered in another.
 *
 * Pure: an action's `run` goes through the [ProjectActionHost] the place hands in — a navigation,
 * or one of the dialogs `ProjectActionDialogs` mounts. The desk refuses whatever it refuses either
 * way; these predicates are what is offered, never a permission.
 */

export type ProjectActionId =
  | 'make-current'
  | 'open'
  | 'settings'
  | 'clone'
  | 'export'
  | 'update-venue'
  | 'detach'
  | 'attach'
  | 'extract'
  | 'history'
  | 'publish'
  | 'delete'

/** The ⋮ menu's sections, in this order, a separator between each. */
export type ProjectActionGroup = 'rig' | 'copy' | 'venue' | 'danger'

export const PROJECT_ACTION_GROUPS: readonly ProjectActionGroup[] = ['rig', 'copy', 'venue', 'danger']

/** Where the actions are drawn. Only *Open* depends on it: a card is itself the way in, and a home is where it leads. */
export type ProjectActionPlace = 'card' | 'home' | 'switcher' | 'palette'

/** The dialogs an action opens, which `ProjectActionDialogs` mounts for one project. */
export type ProjectDialog =
  | 'make-current'
  | 'clone'
  | 'export'
  | 'update-venue'
  | 'detach'
  | 'attach'
  | 'extract'
  | 'history'
  | 'publish'
  | 'delete'

export interface ProjectActionHost {
  navigate: (path: string) => void
  open: (dialog: ProjectDialog) => void
}

export interface ProjectActionContext {
  place: ProjectActionPlace
  isAdmin: boolean
  /**
   * The venue's history — its installed version and draft — for *Publish*; read only for an admin
   * on a venue, and absent while it loads.
   */
  venueHistory?: Pick<VenueHistory, 'installed' | 'draft'> | null
  host: ProjectActionHost
}

export interface ProjectAction {
  id: ProjectActionId
  label: string
  group: ProjectActionGroup
  visible: boolean
  enabled: boolean
  /** Why it cannot run, while it cannot. */
  reason: string | null
  /** The refusal in a few words, for a place with no room for the reason (the switcher's row). */
  refusalLabel?: string
  /** A destructive action, drawn in the destructive colour. */
  destructive?: boolean
  run: () => void
}

const ON_THE_RIG_DELETE = 'It’s on the rig: make another project current first'

export function projectActions(
  project: Pick<ProjectSummary, 'id' | 'name' | 'isCurrent' | 'kind' | 'venue'>,
  ctx: ProjectActionContext,
): ProjectAction[] {
  const { place, isAdmin, venueHistory, host } = ctx
  const isVenue = project.kind === 'VENUE'
  const isShow = !isVenue
  const venue = project.venue ?? null
  const refusal = makeCurrentRefusal(project)
  const missing = isShow && venueMissing(project)
  // A venue the desk holds at the pinned version: what Update and Detach work on (the desk refuses
  // both, `VENUE_MISSING`, for a pin it lacks).
  const venueHere = isShow && venue != null && venue.id != null && !missing
  const behindTo = venueHere && venue.installed != null && venue.installed > venue.version ? venue.installed : null
  const standalone = isShow && venue == null
  const nextVersion = (venueHistory?.installed ?? 0) + 1
  const draftChanges = venueHistory?.draft.changes

  const action = (
    id: ProjectActionId,
    label: string,
    group: ProjectActionGroup,
    visible: boolean,
    run: () => void,
    reason: string | null = null,
    extra: Partial<Pick<ProjectAction, 'refusalLabel' | 'destructive'>> = {},
  ): ProjectAction => ({ id, label, group, visible, enabled: reason == null, reason, run, ...extra })

  return [
    action('open', 'Open', 'rig', place === 'switcher' || place === 'palette', () => host.navigate(`/projects/${project.id}`)),
    action('make-current', 'Make current', 'rig', !project.isCurrent, () => host.open('make-current'), refusal, {
      // The venue itself, or only the version the show is pinned to.
      refusalLabel: refusal ? (venue?.id == null ? 'Venue not here' : 'Version not here') : undefined,
    }),
    action('settings', 'Settings', 'rig', true, () => host.navigate(`/projects/${project.id}/settings`)),
    // A clone of a show keeps its pin, so the desk refuses one whose venue version it lacks (VENUE_MISSING).
    action('clone', 'Clone…', 'copy', isShow || isAdmin, () => host.open('clone'), missing ? refusal : null),
    action('export', 'Export…', 'copy', isAdmin, () => host.open('export')),
    action('update-venue', `Update venue to v${behindTo ?? ''}…`, 'venue', behindTo != null, () => host.open('update-venue')),
    action('detach', 'Detach from venue…', 'venue', venueHere, () => host.open('detach')),
    action('attach', 'Attach to venue…', 'venue', standalone, () => host.open('attach')),
    action('extract', 'Extract a venue…', 'venue', isAdmin && standalone, () => host.open('extract')),
    action('history', 'History', 'venue', isVenue && isAdmin, () => host.open('history'), venueHistory ? null : 'Reading the history…'),
    action(
      'publish',
      `Publish v${nextVersion}…`,
      'venue',
      isVenue && isAdmin,
      () => host.open('publish'),
      venueHistory == null ? 'Reading the draft…' : draftChanges === 0 ? 'Nothing to publish' : null,
    ),
    action('delete', 'Delete…', 'danger', isShow || isAdmin, () => host.open('delete'), project.isCurrent ? ON_THE_RIG_DELETE : null, {
      destructive: true,
    }),
  ]
}

/**
 * A project row's actions — the switcher's rows and the venue home's shows: the *rig* group less
 * Settings, so *Open* and *Make current* (or its refusal). A row has room for two; Settings is a press
 * away on the home, the sidebar and ⌘K.
 */
export function rigRowActions(
  project: Pick<ProjectSummary, 'id' | 'name' | 'isCurrent' | 'kind' | 'venue'>,
  ctx: Omit<ProjectActionContext, 'place'>,
): ProjectAction[] {
  return projectActions(project, { ...ctx, place: 'switcher' }).filter(
    (a) => a.group === 'rig' && a.visible && a.id !== 'settings',
  )
}

/** The visible actions, in their groups' order — what a ⋮ menu draws, a separator between groups. */
export function menuGroupsOf(actions: readonly ProjectAction[]): ProjectAction[][] {
  return PROJECT_ACTION_GROUPS.map((group) => actions.filter((a) => a.visible && a.group === group)).filter(
    (g) => g.length > 0,
  )
}

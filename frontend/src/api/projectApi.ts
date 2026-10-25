import { InternalApiConnection } from "./internalApi";
import { Subscription } from "./subscription";
import { ScriptType } from "../store/scripts";
import type { DetachedFrom, VenueBehind } from "./venueApi";
import type { CueNumberClash } from "../store/cloudSync";

/**
 * What a project is (venue plan §3.1): a show, or a venue whose own rows are its draft. A
 * `VENUE_VERSION` is a frozen snapshot of one published version — never listed, never current,
 * read only through its venue's History. See `lib/venue.ts`.
 */
export type ProjectKind = 'SHOW' | 'VENUE' | 'VENUE_VERSION';

/**
 * The venue a show sits on (venue plan session 3): the venue by uuid — and its project id on this
 * desk, which *Open venue* goes to — its name, the version the show is pinned to and the venue's
 * installed (latest published) version, and how far behind it the show is. `id`, `name` and
 * `installed` are absent when the venue is not on this desk. Absent altogether for a standalone show
 * and a venue.
 */
export interface ProjectVenue {
  uuid: string;
  id?: number | null;
  name?: string | null;
  version: number;
  installed?: number | null;
  /**
   * How far the pin trails the installed version (session 6): `OUTPUT` opens the show held, `DRAWING`
   * is a banner. Absent (or `NONE`) from a desk older than session 6, and for a show on its latest.
   */
  behind?: VenueBehind | null;
  /** How many changes behind the pin reach the show as drawing only: the drawing-only banner's count. */
  drawingChanges?: number | null;
}

// Project summary for list views
export interface ProjectSummary {
  id: number;
  name: string;
  description: string | null;
  isCurrent: boolean;
  /** Absent from a desk older than venues, which has only shows. */
  kind?: ProjectKind;
  venue?: ProjectVenue | null;
  /** A venue's card (venue plan session 7); absent for a show, and from an older desk. */
  venueSummary?: VenueSummary | null;
  /**
   * The channels the project parks — its effective parks, a venue's included — which a switch to it
   * keeps at their parked levels. Absent from a desk older than the switcher (navigation plan §4.3).
   */
  parks?: number;
  /** *Make rig safe*'s blackout still holds on the project, so it opens dark. Runtime only. */
  safeBlackout?: boolean;
  /** Where a standalone show was detached from; absent for any other project and from an older desk. */
  detachedFrom?: DetachedFrom | null;
}

/**
 * A venue's card on the Projects page: its installed version (null before v1, when the counts are
 * the draft's), what it holds, and the shows on it — how many, and how many are behind it.
 */
export interface VenueSummary {
  version?: number | null;
  publishedAt?: string | null;
  patches?: number;
  universes?: number;
  riggings?: number;
  /** Fixed seating blocks (the balcony), by name. */
  fixedSeating?: string[];
  shows?: number;
  behind?: number;
}

// Full project details for editing/viewing
export interface ProjectDetail {
  id: number;
  name: string;
  description: string | null;
  isCurrent: boolean;
  scriptCount: number;
  /** Looks in the project. Was `fxPresetCount`, over the retired FX presets. */
  lookCount: number;
  cueCount: number;
  cueStackCount: number;
  stageWidthM: number | null;
  stageDepthM: number | null;
  stageHeightM: number | null;
  kind?: ProjectKind;
  /** The venue a show sits on; its stage size above is the venue's. */
  venue?: ProjectVenue | null;
  /** Where a standalone show was detached from; absent for any other project and from an older desk. */
  detachedFrom?: DetachedFrom | null;
}

// For creating a new project
export interface CreateProjectRequest {
  name: string;
  description?: string;
  /** `VENUE` is *Add venue*, an admin's; a show by default. */
  kind?: 'SHOW' | 'VENUE';
  /** A new show on this venue (venue plan session 3), pinned to its installed version. */
  venueId?: number;
  /** A Preset seating block of that version, copied in as the show's own stalls (venue plan D6). */
  seatingPresetUuid?: string;
}

// For updating a project
export interface UpdateProjectRequest {
  name?: string;
  description?: string | null;
  stageWidthM?: number | null;
  stageDepthM?: number | null;
  stageHeightM?: number | null;
}

// Script summary for project config dropdowns
export interface ProjectScript {
  id: number;
  name: string;
}

// Full script details from any project
export interface ProjectScriptDetail {
  id: number;
  name: string;
  script: string;
  scriptType: ScriptType;
  // Usage tracking fields
  usedByAttributes?: string[];
  canDelete?: boolean;
  cannotDeleteReason?: string | null;
  canEdit?: boolean;
  cannotEditReason?: string | null;
}

// Request for cloning a project
export interface CloneProjectRequest {
  name: string;
  description?: string;
}

// Request to export a project to a folder. `path` is server-side; null means use the
// per-project default under appDataDir().
export interface ExportProjectRequest {
  path?: string | null;
}

export interface ExportProjectResponse {
  path: string;
  fileCount: number;
}

// Request to import a project from a folder. `nameOverride` resolves a name collision
// with an existing project (the import otherwise refuses with 409).
export interface ImportProjectRequest {
  path: string;
  nameOverride?: string | null;
}

export interface ImportProjectResponse {
  projectId: number;
  projectUuid: string;
  name: string;
  /** Explicit cue numbers the archive held twice in one stack, and how each was resolved (sync v32). */
  numberClashes?: CueNumberClash[];
}

// Response from clone project endpoint
export interface CloneProjectResponse {
  project: ProjectDetail;
  scriptsCloned: number;
  looksCloned: number;
  cuesCloned: number;
  cueStacksCloned: number;
  /** Total records copied across every synced table — patches, groups, universes, cue children, … */
  recordsCloned: number;
  message: string;
}

// Request for copying a script to another project
export interface CopyScriptRequest {
  targetProjectId: number;
  newName?: string;
}

// Response from copy script endpoint
export interface CopyScriptResponse {
  scriptId: number;
  scriptName: string;
  targetProjectId: number;
  targetProjectName: string;
  message: string;
}

// WebSocket message types
type ProjectStateMessage = {
  type: 'projectState';
  projectId: number;
  projectName: string;
  description: string | null;
}

type ProjectChangedMessage = {
  type: 'projectChanged';
  previousProjectId: number;
  newProjectId: number;
  newProjectName: string;
}

/**
 * A project's own row changed — name, description or stage bounding box — through the REST edit or
 * the `set_stage` MCP tool. Any project, not only the current one.
 */
export interface ProjectDetailsChangedMessage {
  type: 'projectDetailsChanged';
  projectId: number;
}

type ProjectInMessage = ProjectStateMessage | ProjectChangedMessage | ProjectDetailsChangedMessage;

export interface ProjectApi {
  subscribe(fn: (state: ProjectStateMessage) => void): Subscription;
  subscribeToSwitch(fn: (data: ProjectChangedMessage) => void): Subscription;
  subscribeToDetails(fn: (data: ProjectDetailsChangedMessage) => void): Subscription;
  requestState(): void;
}

export function createProjectApi(conn: InternalApiConnection): ProjectApi {
  let nextSubscriptionId = 1;
  const stateSubscriptions = new Map<number, (state: ProjectStateMessage) => void>();
  const switchSubscriptions = new Map<number, (data: ProjectChangedMessage) => void>();
  const detailsSubscriptions = new Map<number, (data: ProjectDetailsChangedMessage) => void>();

  const notifyState = (state: ProjectStateMessage) => {
    stateSubscriptions.forEach((fn) => fn(state));
  };

  const notifySwitch = (data: ProjectChangedMessage) => {
    switchSubscriptions.forEach((fn) => fn(data));
  };

  const handleOnMessage = (message: ProjectInMessage | null) => {
    if (message == null) {
      return;
    }

    if (message.type === 'projectState') {
      notifyState(message);
    } else if (message.type === 'projectChanged') {
      notifySwitch(message);
      // Also notify state subscribers with new state
      notifyState({
        type: 'projectState',
        projectId: message.newProjectId,
        projectName: message.newProjectName,
        description: null,
      });
    } else if (message.type === 'projectDetailsChanged') {
      detailsSubscriptions.forEach((fn) => fn(message));
    }
  };

  // No `projectState` request on open: the server pushes one per connection.
  conn.subscribe((evType, _ev, message) => {
    if (evType === 'message') {
      handleOnMessage(message as ProjectInMessage | null);
    }
  });

  return {
    subscribe(fn: (state: ProjectStateMessage) => void): Subscription {
      const thisId = nextSubscriptionId;
      nextSubscriptionId++;

      stateSubscriptions.set(thisId, fn);

      return {
        unsubscribe: () => {
          stateSubscriptions.delete(thisId);
        },
      };
    },

    subscribeToSwitch(fn: (data: ProjectChangedMessage) => void): Subscription {
      const thisId = nextSubscriptionId;
      nextSubscriptionId++;

      switchSubscriptions.set(thisId, fn);

      return {
        unsubscribe: () => {
          switchSubscriptions.delete(thisId);
        },
      };
    },

    subscribeToDetails(fn: (data: ProjectDetailsChangedMessage) => void): Subscription {
      const thisId = nextSubscriptionId;
      nextSubscriptionId++;

      detailsSubscriptions.set(thisId, fn);

      return {
        unsubscribe: () => {
          detailsSubscriptions.delete(thisId);
        },
      };
    },

    requestState(): void {
      conn.send(JSON.stringify({ type: "projectState" }));
    },
  };
}

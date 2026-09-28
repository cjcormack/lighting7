import { InternalApiConnection } from "./internalApi";
import { Subscription } from "./subscription";
import { ScriptType } from "../store/scripts";

// Project summary for list views
export interface ProjectSummary {
  id: number;
  name: string;
  description: string | null;
  isCurrent: boolean;
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
}

// For creating a new project
export interface CreateProjectRequest {
  name: string;
  description?: string;
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
  usedByProperties?: string[];
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

type ProjectInMessage = ProjectStateMessage | ProjectChangedMessage;

export interface ProjectApi {
  subscribe(fn: (state: ProjectStateMessage) => void): Subscription;
  subscribeToSwitch(fn: (data: ProjectChangedMessage) => void): Subscription;
  requestState(): void;
}

export function createProjectApi(conn: InternalApiConnection): ProjectApi {
  let nextSubscriptionId = 1;
  const stateSubscriptions = new Map<number, (state: ProjectStateMessage) => void>();
  const switchSubscriptions = new Map<number, (data: ProjectChangedMessage) => void>();

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

    requestState(): void {
      conn.send(JSON.stringify({ type: "projectState" }));
    },
  };
}

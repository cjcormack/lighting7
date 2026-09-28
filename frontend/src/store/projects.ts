import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import {
  ProjectSummary,
  ProjectDetail,
  CreateProjectRequest,
  UpdateProjectRequest,
  ProjectScriptDetail,
  CloneProjectRequest,
  CloneProjectResponse,
  CopyScriptRequest,
  CopyScriptResponse,
  ExportProjectRequest,
  ExportProjectResponse,
  ImportProjectRequest,
  ImportProjectResponse,
} from "../api/projectApi"
import {
  Script,
  ScriptInput,
  CompileRequest,
  CompileResult,
  RunRequest,
  RunResult,
} from "./scripts"

// Subscribe to WebSocket project changes - invalidate all caches on project switch
lightingApi.projects.subscribeToSwitch(function() {
  // When project switches, invalidate all project-scoped data
  store.dispatch(restApi.util.invalidateTags([
    'ProjectList',
    'Project',
    'Script',
    'Fixture',
    'Look',
    'LookList',
    // Locate state is in-memory on the backend and dies with the old Show — a stale
    // "located" button would APPLY a locate instead of releasing one.
    'Locate',
  ]))
})

// Bridge `scriptListChanged` into cache invalidation.
//
// The script endpoints live in this slice rather than in `store/scripts.ts` (types only), so the
// bridge does too. Module scope is safe here for the same reason the project-switch bridge above
// is: this module already touches `lightingApi` at evaluation time.
//
// `FxLibrary` rides along because an `FX_DEFINITION` script *is* an entry in the effect library —
// the same pairing the script mutations below invalidate. The backend fires the two frames from
// different routes, so a definition edited through `/fx/definitions` arrives on
// `fxDefinitionListChanged` instead, bridged in `store/fixtureFx.ts` beside its library query.
lightingApi.scripts.subscribe(function () {
  store.dispatch(restApi.util.invalidateTags(['Script', 'FxLibrary']))
})

export const projectsApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      // List all projects
      projectList: build.query<ProjectSummary[], void>({
        query: () => 'projects',
        providesTags: ['ProjectList'],
      }),

      // Get current project details
      currentProject: build.query<ProjectDetail, void>({
        query: () => 'projects/current',
        providesTags: ['Project'],
        async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
          try {
            const { data } = await queryFulfilled
            // Also cache under the project ID for consistency
            dispatch(
              projectsApi.util.upsertQueryData('project', data.id, data)
            )
          } catch {
            // Query failed, nothing to cache
          }
        },
      }),

      // Get specific project details
      project: build.query<ProjectDetail, number>({
        query: (id) => `projects/${id}`,
        providesTags: (_result, _error, id) => [{ type: 'Project', id }],
      }),

      // Create new project
      createProject: build.mutation<ProjectDetail, CreateProjectRequest>({
        query: (body) => ({
          url: 'projects',
          method: 'POST',
          body,
        }),
        invalidatesTags: ['ProjectList'],
      }),

      // Update project
      updateProject: build.mutation<ProjectDetail, { id: number } & UpdateProjectRequest>({
        query: ({ id, ...body }) => ({
          url: `projects/${id}`,
          method: 'PUT',
          body,
        }),
        invalidatesTags: (_result, _error, { id }) => [
          'ProjectList',
          { type: 'Project', id },
          'Project', // Also invalidate current project in case it was updated
        ],
      }),

      // Delete project
      deleteProject: build.mutation<void, number>({
        query: (id) => ({
          url: `projects/${id}`,
          method: 'DELETE',
        }),
        invalidatesTags: ['ProjectList'],
      }),

      // Switch to project
      setCurrentProject: build.mutation<ProjectDetail, number>({
        query: (id) => ({
          url: `projects/${id}/set-current`,
          method: 'POST',
        }),
        // Cache invalidation handled by WebSocket subscription above
      }),

      // Get scripts for any project. The list carries each script in full — `source` included
      // — so the editor opens straight from a row; there is no single-script read to warm.
      // (One stood here, with an `onQueryStarted` that upserted every row into it. Nothing ever
      // subscribed to that cache, so the whole warming loop ran per list fetch for no reader.)
      projectScripts: build.query<ProjectScriptDetail[], number>({
        query: (projectId) => `projects/${projectId}/scripts`,
        providesTags: ['Script'],
      }),

      // Clone a project
      cloneProject: build.mutation<CloneProjectResponse, { id: number } & CloneProjectRequest>({
        query: ({ id, ...body }) => ({
          url: `projects/${id}/clone`,
          method: 'POST',
          body,
        }),
        invalidatesTags: ['ProjectList'],
      }),

      // Export a project to a server-side folder
      exportProject: build.mutation<ExportProjectResponse, { id: number } & ExportProjectRequest>({
        query: ({ id, ...body }) => ({
          url: `projects/${id}/export`,
          method: 'POST',
          body,
        }),
      }),

      // Import a project from a server-side folder
      importProject: build.mutation<ImportProjectResponse, ImportProjectRequest>({
        query: (body) => ({
          url: 'projects/import',
          method: 'POST',
          body,
        }),
        invalidatesTags: ['ProjectList'],
      }),

      // Copy a script to another project
      copyScript: build.mutation<CopyScriptResponse, { projectId: number; scriptId: number } & CopyScriptRequest>({
        query: ({ projectId, scriptId, ...body }) => ({
          url: `projects/${projectId}/scripts/${scriptId}/copy`,
          method: 'POST',
          body,
        }),
        // Invalidate the target project's data since it now has a new script
        invalidatesTags: (_result, _error, { targetProjectId }) => [
          { type: 'Project', id: targetProjectId },
        ],
      }),

      // Compile script
      compileProjectScript: build.mutation<CompileResult, { projectId: number } & CompileRequest>({
        query: ({ projectId, ...request }) => ({
          url: `projects/${projectId}/scripts/compile`,
          method: 'POST',
          body: request,
        }),
      }),

      // Run script
      runProjectScript: build.mutation<RunResult, { projectId: number } & RunRequest>({
        query: ({ projectId, ...request }) => ({
          url: `projects/${projectId}/scripts/run`,
          method: 'POST',
          body: request,
        }),
        invalidatesTags: ['FxLibrary'],
      }),

      // Save script
      saveProjectScript: build.mutation<Script, { projectId: number; scriptId: number } & Partial<ScriptInput>>({
        query: ({ projectId, scriptId, ...request }) => ({
          url: `projects/${projectId}/scripts/${scriptId}`,
          method: 'PUT',
          body: request,
        }),
        invalidatesTags: ['Script', 'FxLibrary'],
      }),

      // Delete script
      deleteProjectScript: build.mutation<void, { projectId: number; scriptId: number }>({
        query: ({ projectId, scriptId }) => ({
          url: `projects/${projectId}/scripts/${scriptId}`,
          method: 'DELETE',
        }),
        invalidatesTags: ['Script', 'FxLibrary'],
      }),

      // Create script
      createProjectScript: build.mutation<Script, { projectId: number } & ScriptInput>({
        query: ({ projectId, ...script }) => ({
          url: `projects/${projectId}/scripts`,
          method: 'POST',
          body: script,
        }),
        invalidatesTags: ['Script', 'FxLibrary'],
      }),
    }
  },
  overrideExisting: false,
})

export const {
  useProjectListQuery,
  useCurrentProjectQuery,
  useProjectQuery,
  useCreateProjectMutation,
  useUpdateProjectMutation,
  useDeleteProjectMutation,
  useSetCurrentProjectMutation,
  useProjectScriptsQuery,
  useCloneProjectMutation,
  useExportProjectMutation,
  useImportProjectMutation,
  useCopyScriptMutation,
  useCompileProjectScriptMutation,
  useRunProjectScriptMutation,
  useSaveProjectScriptMutation,
  useDeleteProjectScriptMutation,
  useCreateProjectScriptMutation,
} = projectsApi

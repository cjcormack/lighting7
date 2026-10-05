import { restApi } from './restApi'

/**
 * Fixture commands over REST (fixture optics plan session 7, D13) — a reset, a lamp strike, a lamp
 * off. `POST projects/{id}/patches/{pid}/commands/{name}` runs one: the desk holds its level for the
 * command's declared time and **answers when the hold ends**, `completed: false` when it was cut
 * short (a project switch, the fixture repatched away). No socket frame — the hold is the request,
 * so only the window that asked shows a countdown.
 *
 * Refusals, by `code`: `COMMAND_BUSY` (the unit is running another), `COMMAND_BLIND` (the programmer
 * is blind), `COMMAND_PARKED` (a channel it holds is parked), `COMMAND_UNKNOWN`, and on the desk's
 * public listener `REMOTE_COMMANDS_DISABLED` unless an admin has allowed fixture commands.
 */
export interface FixtureCommandResponse {
  fixture: string
  fixtureName: string
  command: string
  label: string
  holdMs: number
  startedAt: string
  endedAt: string
  completed: boolean
}

const commandsApiSlice = restApi.injectEndpoints({
  endpoints: (build) => ({
    runFixtureCommand: build.mutation<FixtureCommandResponse, { projectId: number; patchId: number; command: string }>({
      query: ({ projectId, patchId, command }) => ({
        url: `projects/${projectId}/patches/${patchId}/commands/${encodeURIComponent(command)}`,
        method: 'POST',
      }),
    }),
  }),
  overrideExisting: false,
})

export const { useRunFixtureCommandMutation } = commandsApiSlice

import {ChannelsApi, createChannelsApi} from "./channelsApi";
import {ChannelMappingApi, createChannelMappingApi} from "./channelMappingApi";
import {createStatusApi, StatusApi} from "./statusApi";
import {createInternalApiConnection} from "./internalApi";
import {createUniversesApi, UniversesApi} from "./universesApi";

import {createFixtureApi, FixturesApi} from "./fixturesApi";
import {createProjectApi, ProjectApi} from "./projectApi";
import {createFxApi, FxApi} from "./fxApi";
import {createLooksWsApi, LooksWsApi} from "./looksWsApi";
import {createScriptsWsApi, ScriptsWsApi} from "./scriptsWsApi";
import {createFxDefinitionsWsApi, FxDefinitionsWsApi} from "./fxDefinitionsWsApi";
import {createTemplatesWsApi, TemplatesWsApi} from "./templatesWsApi";
import {createCuesWsApi, CuesWsApi} from "./cuesWsApi";
import {createCueStacksWsApi, CueStacksWsApi} from "./cueStacksWsApi";
import {createCueSlotsWsApi, CueSlotsWsApi} from "./cueSlotsWsApi";
import {createBuskWsApi, BuskWsApi} from "./buskWsApi";
import {createPatchApi, PatchApi} from "./patchApi";
import {createRiggingApi, RiggingApi} from "./riggingApi";
import {createStageRegionApi, StageRegionApi} from "./stageRegionApi";
import {createStageElementApi, StageElementApi} from "./stageElementApi";
import {createStageViewpointApi, StageViewpointApi} from "./stageViewpointApi";
import {createParkApi, ParkApi} from "./parkApi";
import {createPromptBooksWsApi, PromptBooksWsApi} from "./promptBooksWsApi";
import {createBootStatusWsApi, BootStatusWsApi} from "./bootStatusWsApi";
import {createSurfacesWsApi, SurfacesWsApi} from "./surfacesApi";
import {createSelectionWsApi, SelectionWsApi} from "./selectionApi";
import {createBuskPageWsApi, BuskPageWsApi} from "./buskPageApi";
import {createCloudSyncWsApi, CloudSyncWsApi} from "./cloudSyncWsApi";
import {createProgrammerApi, ProgrammerApi} from "./programmerWsApi";
import {createSpeedMastersWsApi, SpeedMastersWsApi} from "./speedMastersWsApi";
import {AuthWsApi, createAuthWsApi} from "./authWsApi";
import {createUsersWsApi, UsersWsApi} from "./usersWsApi";
import {createInstallWsApi, InstallWsApi} from "./installWsApi";
import {createUpdateWsApi, UpdateWsApi} from "./updateWsApi";
import {createRemoteAccessWsApi, RemoteAccessWsApi} from "./remoteAccessWsApi";
import {createWindowsWsApi, WindowsWsApi} from "./windowsApi";
import {createHandWsApi, HandWsApi} from "./handApi";
import {createStageRenderWsApi, StageRenderWsApi} from "./stageRenderApi";

interface LightingApi {
  universes: UniversesApi
  channels: ChannelsApi
  channelMapping: ChannelMappingApi
  status: StatusApi
  auth: AuthWsApi
  users: UsersWsApi
  install: InstallWsApi
  updates: UpdateWsApi
  remoteAccess: RemoteAccessWsApi

  fixtures: FixturesApi
  projects: ProjectApi
  fx: FxApi
  fxDefinitions: FxDefinitionsWsApi
  scripts: ScriptsWsApi
  looks: LooksWsApi
  templates: TemplatesWsApi
  speedMasters: SpeedMastersWsApi
  cues: CuesWsApi
  cueStacks: CueStacksWsApi
  cueSlots: CueSlotsWsApi
  busk: BuskWsApi
  patches: PatchApi
  riggings: RiggingApi
  stageRegions: StageRegionApi
  stageElements: StageElementApi
  stageViewpoints: StageViewpointApi
  park: ParkApi
  promptBooks: PromptBooksWsApi
  surfaces: SurfacesWsApi
  selection: SelectionWsApi
  buskPage: BuskPageWsApi
  windows: WindowsWsApi
  hand: HandWsApi
  stageRender: StageRenderWsApi
  cloudSync: CloudSyncWsApi
  bootStatus: BootStatusWsApi
  programmer: ProgrammerApi
}

/**
 * The one WS/REST client, built at module-evaluation time.
 *
 * Store slices subscribe to it three different ways — at module scope (the default), deferred
 * behind a `startXBridge()` called from `main.tsx` (for slices on the earliest render path, where
 * an import cycle back to this module would otherwise TDZ), or per cache entry inside
 * `onCacheEntryAdded` (for state that is streamed rather than fetched). Which one a new slice
 * should use, and why the split is not stylistic, is written down once in `CLAUDE.md` §"Where a WS
 * bridge subscribes".
 */
export const lightingApi = createLightingApi()

function getWebSocketUrl() {
  if (import.meta.env.VITE_SOCKET_URL) {
    return import.meta.env.VITE_SOCKET_URL
  } else {
    return 'ws://' + window.location.href.split('/')[2] + '/api'
  }
}

function createLightingApi(): LightingApi {
  const baseUrl = '/api/'
  const wsUrl = getWebSocketUrl()

  const connection = createInternalApiConnection(baseUrl, wsUrl)

  const universesApi = createUniversesApi(connection)
  const channelsApi = createChannelsApi(connection)
  const channelMappingApi = createChannelMappingApi(connection)
  const statusApi = createStatusApi(connection)
  const authWsApi = createAuthWsApi(connection)
  const usersWsApi = createUsersWsApi(connection)
  const installWsApi = createInstallWsApi(connection)
  const updateWsApi = createUpdateWsApi(connection)
  const remoteAccessWsApi = createRemoteAccessWsApi(connection)
  const fixtureApi = createFixtureApi(connection)
  const projectApi = createProjectApi(connection)
  const fxApi = createFxApi(connection)
  const fxDefinitionsWsApi = createFxDefinitionsWsApi(connection)
  const scriptsWsApi = createScriptsWsApi(connection)
  const looksWsApi = createLooksWsApi(connection)
  const templatesWsApi = createTemplatesWsApi(connection)
  const cuesWsApi = createCuesWsApi(connection)
  const cueStacksWsApi = createCueStacksWsApi(connection)
  const cueSlotsWsApi = createCueSlotsWsApi(connection)
  const buskWsApi = createBuskWsApi(connection)
  const patchApi = createPatchApi(connection)
  const riggingApi = createRiggingApi(connection)
  const stageRegionApi = createStageRegionApi(connection)
  const stageElementApi = createStageElementApi(connection)
  const stageViewpointApi = createStageViewpointApi(connection)
  const parkApi = createParkApi(connection)
  const promptBooksWsApi = createPromptBooksWsApi(connection)
  const surfacesWsApi = createSurfacesWsApi(connection)
  const cloudSyncWsApi = createCloudSyncWsApi(connection)
  const selectionWsApi = createSelectionWsApi(connection)
  const buskPageWsApi = createBuskPageWsApi(connection)
  const windowsWsApi = createWindowsWsApi(connection)
  const handWsApi = createHandWsApi(connection)
  const stageRenderWsApi = createStageRenderWsApi(connection)
  const bootStatusWsApi = createBootStatusWsApi(connection)
  const programmerApi = createProgrammerApi(connection)
  const speedMastersWsApi = createSpeedMastersWsApi(connection)

  return {
    universes: universesApi,
    channels: channelsApi,
    channelMapping: channelMappingApi,
    status: statusApi,
    auth: authWsApi,
    users: usersWsApi,
    install: installWsApi,
    updates: updateWsApi,
    remoteAccess: remoteAccessWsApi,

    fixtures: fixtureApi,
    projects: projectApi,
    fx: fxApi,
    fxDefinitions: fxDefinitionsWsApi,
    scripts: scriptsWsApi,
    looks: looksWsApi,
    templates: templatesWsApi,
    speedMasters: speedMastersWsApi,
    cues: cuesWsApi,
    cueStacks: cueStacksWsApi,
    cueSlots: cueSlotsWsApi,
    busk: buskWsApi,
    patches: patchApi,
    riggings: riggingApi,
    stageRegions: stageRegionApi,
    stageElements: stageElementApi,
    stageViewpoints: stageViewpointApi,
    park: parkApi,
    promptBooks: promptBooksWsApi,
    surfaces: surfacesWsApi,
    selection: selectionWsApi,
    buskPage: buskPageWsApi,
    windows: windowsWsApi,
    hand: handWsApi,
    stageRender: stageRenderWsApi,
    cloudSync: cloudSyncWsApi,
    bootStatus: bootStatusWsApi,
    programmer: programmerApi,
  }
}

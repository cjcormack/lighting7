import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type {
  ControlSurfaceBinding,
  ControlSurfaceType,
  CreateSurfaceBindingRequest,
  UpdateSurfaceBindingRequest,
  SurfaceDeviceInfo,
  PickupChange,
  ScalerState,
  SurfaceControlStates,
  EncoderBankSelection,
} from "../api/surfacesApi"

export type {
  ControlSurfaceBinding,
  ControlSurfaceType,
  CreateSurfaceBindingRequest,
  UpdateSurfaceBindingRequest,
  SurfaceDeviceInfo,
  PickupChange,
  ScalerState,
  BindingTarget,
  FixturePropertyTarget,
  GroupPropertyTarget,
  CueStackGoTarget,
  CueStackBackTarget,
  CueStackPauseTarget,
  FireCueTarget,
  FlashTarget,
  BlackoutTarget,
  GrandMasterToggleTarget,
  SetBankTarget,
  SpeedMasterBpmTarget,
  SpeedMasterTapTarget,
  SelectionPropertyTarget,
  SelectTargetTarget,
  SelectMode,
  ClearSelectionTarget,
  LocateSelectionTarget,
  StripTarget,
  EncoderBankSetTarget,
  ColourAxis,
  EncoderBankSelection,
  UnknownTarget,
  ControlDescriptor,
  FaderControl,
  EncoderControl,
  ButtonControl,
  BankButtonControl,
  BankDefinition,
  StripDefinition,
  LayoutCell,
  LayoutRegion,
  SurfaceLayout,
  SurfaceControlStates,
  ControlState,
  LedState,
  RingState,
  TakeoverPolicy,
  LearnEvent,
  BindingHealth,
} from "../api/surfacesApi"

lightingApi.surfaces.subscribeBindingsChanged((event) => {
  store.dispatch(
    restApi.util.invalidateTags([{ type: 'SurfaceBinding', id: event.projectId }]),
  )
})

/** Soft-takeover state keyed by [pickupKey]. */
export type PickupStates = Readonly<Record<string, PickupChange>>

// Shared empties, so a stream that has never pushed hands every reader the same identity
// rather than a fresh object per render.
const NO_DEVICES: SurfaceDeviceInfo[] = []
const NO_BANKS: Record<string, string> = {}
const NO_PICKUPS: PickupStates = {}
const NO_CONTROLS: SurfaceControlStates = {}
const NO_ENCODER_BANKS: Record<string, EncoderBankSelection> = {}
/** What the scaler is before its first frame: nothing blacked out, grand master live. */
const SCALER_AT_REST: ScalerState = { blackoutEnabled: false, grandMasterEnabled: true }

/** The key a pickup state is stored under — one control on one attached device. */
export function pickupKey(displayKey: string, controlId: string): string {
  return `${displayKey}|${controlId}`
}

/**
 * Fold one pickup transition into the map. Pure, and returns the map unchanged when the
 * transition says nothing new: ENGAGED means the fader has caught up with the value it was
 * chasing, so there is no takeover left to show.
 */
export function applyPickupChange(
  pickups: PickupStates,
  change: PickupChange,
): PickupStates {
  const key = pickupKey(change.displayKey, change.controlId)
  if (change.state !== 'ENGAGED') return { ...pickups, [key]: change }
  // `Object.hasOwn`, not `key in`: the map is a plain object, and `in` answers for the
  // prototype chain too.
  if (!Object.hasOwn(pickups, key)) return pickups
  const next = { ...pickups }
  delete next[key]
  return next
}

export const surfacesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    // All known device-family profiles (not attached instances — see useSurfaceDevices hook).
    controlSurfaceTypeList: build.query<ControlSurfaceType[], void>({
      query: () => 'control-surface-types',
      providesTags: ['ControlSurfaceType'],
    }),

    // All bindings for a project.
    surfaceBindings: build.query<ControlSurfaceBinding[], number>({
      query: (projectId) => `projects/${projectId}/surface-bindings`,
      providesTags: (_result, _error, projectId) => [
        { type: 'SurfaceBinding', id: projectId },
      ],
    }),

    createSurfaceBinding: build.mutation<
      ControlSurfaceBinding,
      { projectId: number } & CreateSurfaceBindingRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/surface-bindings`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'SurfaceBinding', id: projectId },
      ],
    }),

    updateSurfaceBinding: build.mutation<
      ControlSurfaceBinding,
      { projectId: number; bindingId: number } & UpdateSurfaceBindingRequest
    >({
      query: ({ projectId, bindingId, ...body }) => ({
        url: `projects/${projectId}/surface-bindings/${bindingId}`,
        method: 'PATCH',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'SurfaceBinding', id: projectId },
      ],
    }),

    /**
     * *Fader only…* — replace a strip row with the four single rows it was deriving, in one
     * transaction. The derivation happens server-side, at the encoder bank the device is on right
     * now, so the four bindings the operator ends up with are the four they could see a moment
     * ago; doing it here would be a second copy of `deriveStripTarget` that could disagree with
     * the desk about what the encoder was doing.
     */
    expandSurfaceBinding: build.mutation<
      ControlSurfaceBinding[],
      { projectId: number; bindingId: number }
    >({
      query: ({ projectId, bindingId }) => ({
        url: `projects/${projectId}/surface-bindings/${bindingId}/expand`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'SurfaceBinding', id: projectId },
      ],
    }),

    deleteSurfaceBinding: build.mutation<
      void,
      { projectId: number; bindingId: number }
    >({
      query: ({ projectId, bindingId }) => ({
        url: `projects/${projectId}/surface-bindings/${bindingId}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'SurfaceBinding', id: projectId },
      ],
    }),

    // The six WS-driven live states below are cache entries rather than `useState` +
    // `useEffect` hooks, so that two components reading one stream share a single
    // subscription and RTK Query owns the teardown — the pattern `speedMasters.ts`
    // documents and seven other slices follow.
    //
    // Each seeds from the WS layer's cached snapshot, which is what makes them equivalent
    // to the hooks they replaced: `subscribeX` replays the last frame synchronously, so a
    // late mount never waited for a round-trip, and `getX()` gives the seed that same value.
    // Subscribing only after `cacheDataLoaded` matters for the same reason: `updateCachedData`
    // on an entry that does not exist yet is silently dropped, and awaiting first means the
    // replay lands *in* the entry — so a frame that arrived while the seed was resolving is
    // carried by the replay rather than lost.
    // No tags and no REST: these are pushed, never fetched, so nothing can invalidate them.

    /** Attached MIDI devices (both matched and unmatched). */
    surfaceDevices: build.query<SurfaceDeviceInfo[], void>({
      queryFn: () => ({ data: lightingApi.surfaces.getDevices() ?? NO_DEVICES }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.surfaces.subscribeDevices((devices) => {
          updateCachedData(() => devices)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /** Active bank per `deviceTypeKey`. */
    surfaceBanks: build.query<Record<string, string>, void>({
      queryFn: () => ({ data: lightingApi.surfaces.getBanks() ?? NO_BANKS }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.surfaces.subscribeBanks((banks) => {
          updateCachedData(() => banks)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /** Per-`(displayKey, controlId)` soft-takeover state. */
    surfacePickups: build.query<PickupStates, void>({
      // Pickup is a transition stream with no snapshot behind it — nothing to seed from and
      // nothing to replay — so the fold lives here. It accumulates in a local rather than off
      // the draft, which means the whole map is rewritten each frame: a transition that
      // arrived before this subscription existed is carried by the next one rather than
      // leaving the cache permanently a step behind.
      queryFn: () => ({ data: NO_PICKUPS }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        let pickups: PickupStates = NO_PICKUPS
        const subscription = lightingApi.surfaces.subscribePickup((change) => {
          const next = applyPickupChange(pickups, change)
          if (next === pickups) return
          pickups = next
          updateCachedData(() => next)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /**
     * Every attached device's control state — what the hardware has been told (plan D7).
     *
     * The two-frame fold lives in the WS layer, not here, so this is the plain replacing
     * subscription the other three are. See `SurfacesWsApi.subscribeControls`.
     */
    surfaceControls: build.query<SurfaceControlStates, void>({
      queryFn: () => ({ data: lightingApi.surfaces.getControls() ?? NO_CONTROLS }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.surfaces.subscribeControls((controls) => {
          updateCachedData(() => controls)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /** What each device's strip encoders drive, `deviceTypeKey` → selection; absent is `dimmer`. */
    surfaceEncoderBanks: build.query<Record<string, EncoderBankSelection>, void>({
      queryFn: () => ({ data: lightingApi.surfaces.getEncoderBanks() ?? NO_ENCODER_BANKS }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.surfaces.subscribeEncoderBanks((properties) => {
          updateCachedData(() => properties)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),

    /** Global blackout / grand-master state. */
    surfaceScaler: build.query<ScalerState, void>({
      queryFn: () => ({ data: lightingApi.surfaces.getScaler() ?? SCALER_AT_REST }),
      async onCacheEntryAdded(_, { cacheDataLoaded, updateCachedData, cacheEntryRemoved }) {
        await cacheDataLoaded
        const subscription = lightingApi.surfaces.subscribeScaler((scaler) => {
          updateCachedData(() => scaler)
        })
        await cacheEntryRemoved
        subscription.unsubscribe()
      },
    }),
  }),
  overrideExisting: false,
})

export const {
  useControlSurfaceTypeListQuery,
  useSurfaceBindingsQuery,
  useCreateSurfaceBindingMutation,
  useUpdateSurfaceBindingMutation,
  useExpandSurfaceBindingMutation,
  useDeleteSurfaceBindingMutation,
  useSurfaceDevicesQuery,
  useSurfaceBanksQuery,
  useSurfacePickupsQuery,
  useSurfaceControlsQuery,
  useSurfaceEncoderBanksQuery,
  useSurfaceScalerQuery,
} = surfacesApi

/** Attached MIDI devices (both matched and unmatched). */
export function useSurfaceDevices(): SurfaceDeviceInfo[] {
  const { data } = useSurfaceDevicesQuery()
  return data ?? NO_DEVICES
}

/** Active bank per `deviceTypeKey`. */
export function useActiveBanks(): Record<string, string> {
  const { data } = useSurfaceBanksQuery()
  return data ?? NO_BANKS
}

/** Per-`(displayKey, controlId)` soft-takeover state; index it with [pickupKey]. */
export function usePickupStates(): PickupStates {
  const { data } = useSurfacePickupsQuery()
  return data ?? NO_PICKUPS
}

/**
 * Every attached device's control state, keyed `displayKey` → `controlId`. What the hardware was
 * told, never a recomputation from DMX (plan D7).
 */
export function useSurfaceControls(): SurfaceControlStates {
  const { data } = useSurfaceControlsQuery()
  return data ?? NO_CONTROLS
}

/** What each device's strip encoders drive, `deviceTypeKey` → selection; absent is `dimmer`. */
export function useEncoderBanks(): Record<string, EncoderBankSelection> {
  const { data } = useSurfaceEncoderBanksQuery()
  return data ?? NO_ENCODER_BANKS
}

/** Global blackout / grand-master state. */
export function useScalerState(): ScalerState {
  const { data } = useSurfaceScalerQuery()
  return data ?? SCALER_AT_REST
}

import { isRejectedWithValue } from '@reduxjs/toolkit'
import type { Middleware } from '@reduxjs/toolkit'
import { toast } from 'sonner'
import { formatError } from '../lib/formatError'

/**
 * Endpoints whose call sites already report their own failures. Everything *not* listed here
 * toasts automatically — silence is opt-in and has to be justified with a comment, so a new
 * mutation can never fail invisibly just because nobody remembered to handle it.
 *
 * A per-call opt-out flag isn't viable: mutations build their request body by rest-spreading the
 * arg (`query: ({ projectId, ...body }) => ({ body })`), so an extra flag would be sent to the
 * server. Hence a deny-list keyed on endpoint name.
 *
 * `errorToastMiddleware.test.ts` asserts every name here actually exists on `restApi`, so a
 * renamed or deleted endpoint can't silently leave a hole.
 */
export const SILENT_ENDPOINTS: ReadonlySet<string> = new Set([
  // Inline <Alert variant="destructive"> rendered from the mutation's own `error` state
  // The library sheets' copy routes (library-sheets plan D14), and every caller reports its own
  // refusal: *Copy to…* through `CopyToProjectSheet`, which names each record that failed in its
  // alert and keeps it for a retry; the sheets' Duplicate by a keyed toast; and `copyLook`'s third
  // caller, the Look detail sheet's Duplicate (`duplicateOne` in routes/Looks.tsx), by a toast too.
  'copyLook', // components/looks/LookSheet.tsx (Duplicate, and Copy to… through sheet/CopyToProjectSheet.tsx), routes/Looks.tsx duplicateOne
  'copyTemplate', // components/templates/TemplateSheet.tsx (Duplicate, and Copy to… through sheet/CopyToProjectSheet.tsx)
  'copyScript', // src/CopyScriptDialog.tsx, and components/scripts/ScriptSheet.tsx (Copy to… through sheet/CopyToProjectSheet.tsx)
  'cloneProject', // src/CloneProjectDialog.tsx
  'importProject', // src/ImportProjectDialog.tsx
  'exportProject', // src/ExportProjectDialog.tsx
  'deleteProject', // src/routes/Projects.tsx
  // BUSK_PAGE_NAME_TAKEN is an ordinary step in naming a page, shown beside the field.
  'createBuskPage', // src/components/busking/BuskPageStrip.tsx
  // A saved view's duplicate name is an ordinary step in naming it, shown beside the field.
  'createStageViewpoint', // src/components/stage3d/SaveViewpointSheet.tsx
  'renameBuskPage', // src/components/busking/BuskPageStrip.tsx
  // The rig's commit queue reports its own refusal **by code** (`rigWriteFailureMessage`) after
  // restoring the last confirmed rig; a second, generic toast from here would say less, twice.
  'saveBuskRig', // src/store/busk.ts, useBuskRigCommit
  'runFixtureCommand', // src/components/fixtures/FixtureCommandsMenu.tsx — toasts the refusal naming the unit and the command
  'recordProgrammer', // src/components/programmer/RecordSheet.tsx
  'recordLook', // src/components/programmer/RecordLookSheet.tsx
  'includeIntoProgrammer', // src/components/programmer/IncludeSheet.tsx
  'updateProgrammer', // src/components/programmer/UpdateDialog.tsx
  // The LOOK_IN_USE 409 is an ordinary step in the delete flow, not a failure: it opens the
  // "delete anyway" confirmation, which names the cues that lose a layer. A duplicate-name 409 on
  // create/save is likewise rendered beside the field the operator has to change.
  //
  // NB: `deleteLook` has one call site, `useLookDelete` — the Look sheet's batch and the detail
  // sheet's single delete both go through it (library-sheets plan D13) — and only the 409 is a flow
  // step: every *other* failure is toasted by `useBatchDelete`, or a delete that quietly did
  // nothing would look like a success. A second caller must report its own.
  'deleteLook', // src/components/looks/useLookDelete.tsx — LookSheet and LookDetailSheet
  // LookDetailSheet renders a clash inline; the Look sheet's name and Notes cells toast by column
  // (`reportSheetWriteFailure`, D14); LookRowStore's layer-scope write reports its own.
  'saveLook', // components/looks/LookDetailSheet.tsx, components/looks/LookSheet.tsx, programmer/LookRowStore.tsx
  // `createLook` stood here. It went with the endpoint in session 3: a Look is recorded now, never
  // hand-authored, so no client sends `POST /looks`. `errorToastMiddleware.test.ts` is what caught
  // the stale name — it asserts every entry names an endpoint that exists.
  //
  // The template trio, for exactly the reasons above one entity along. `TEMPLATE_IN_USE` opens the
  // same "delete anyway" guard; a duplicate name and each of the four write-boundary rules (one
  // family, closed vocabulary, right intent shape, no group rows) are 400s the editor renders beside
  // the field. `useTemplateDelete` — the sheet's batch and the editor's Delete — toasts anything
  // else on delete (D13), and the template sheet's cells toast a refused write by column (D14).
  'deleteTemplate', // src/components/templates/useTemplateDelete.tsx — TemplateSheet and routes/Templates.tsx (TemplateEditor)
  'createTemplate', // src/components/templates/TemplateEditor.tsx — inline alert
  'saveTemplate', // ...same editor (via routes/Templates.tsx), and components/templates/TemplateSheet.tsx
  // Silent too: its sheet stays open on failure and renders the error inline (the strip's chip is
  // what opens it), so a toast beside that alert would say the same thing twice.
  'createTemplateFromProgrammer', // src/components/programmer/NewTemplateFromSelectionSheet.tsx
  // The 409s are ordinary steps in this flow, not failures: SPEED_MASTER_IN_USE opens the batch
  // delete's "delete anyway" dialog, and SPEED_MASTER_PROTECTED can only be reached by a stale
  // client (master 1 is skipped by name before anything is sent). Every other delete failure is
  // toasted by `useBatchDelete` itself, which both callers go through (library-sheets plan D13).
  'deleteSpeedMaster', // src/components/speedMasters/useSpeedMasterDelete.tsx — the sheet and SpeedMasterDetailSheet
  // The Scripts and FX Library deletes (library-sheets plan D13, D14). Neither desk route has an
  // in-use refusal — a script's registered effects are held back client-side, unsent — so every
  // failure is a refusal `useBatchDelete` toasts itself, under one id per sheet; the middleware
  // would say it a second time, generically. Each hook is the sheet's batch **and** its editor's
  // Delete (`ScriptForm`, `EditFxDefinitionSheet`), so a third caller must report its own.
  'deleteProjectScript', // src/components/scripts/useScriptDelete.tsx — ScriptSheet and routes/ProjectScripts.tsx (ScriptForm)
  'deleteFxDefinition', // src/components/fxLibrary/useFxDefinitionDelete.ts — FxLibrarySheet and routes/FxLibrary.tsx (EditFxDefinitionSheet)
  // Two call sites, both reporting their own refusals: the detail sheet renders a duplicate name
  // (409) inline, and the Speed Masters sheet's cells toast every refused write by code, keyed per
  // column (`reportSheetWriteFailure`, library-sheets plan D14). A third caller must do one or the other.
  'saveSpeedMaster', // src/components/speedMasters/SpeedMasterDetailSheet.tsx, SpeedMasterSheet.tsx
  // These three take a password or a name the user typed and render failures inline in
  // their own sheet/page, not as a toast.
  'createUser', // src/components/users/CreateUserSheet.tsx
  'setUserPassword', // src/components/users/UserDetailSheet.tsx
  'redeemResetToken', // src/routes/ResetPasswordPage.tsx
  // 409 LAST_ADMIN/SELF_TARGET are ordinary flow steps rendered inline in
  // UserDetailSheet; a toast would double-report the same failure.
  'updateUser', // src/components/users/UserDetailSheet.tsx
  'deleteUser', // ...same sheet
  // The QR sheet mints on open and shows a failure in place of the code it couldn't
  // produce. Its sibling `cancelResetToken` is deliberately *not* silenced: a failure there
  // means a reset link the admin just tried to revoke is still live, and the history row it
  // fires from can only say "still Live" — which reads as a slow refresh, not a refusal.
  'createResetToken', // src/components/users/ResetQrSheet.tsx
  // Same shape, one flow along: the device-login sheet mints on open and renders its failure
  // where the QR would have been. `cancelDeviceLogin` stays noisy for the reason above, and
  // more so — what is still live there is a way *into* the account, not just a way to
  // re-password it.
  'createDeviceLogin', // src/components/auth/DeviceLoginSection.tsx
  'redeemDeviceLogin', // src/routes/DeviceLoginPage.tsx
  // The update panel renders every outcome itself, and its 409s are ordinary flow steps rather
  // than failures: NOTHING_STAGED and DOWNLOAD_IN_PROGRESS mean another tab got there first, and
  // VERSION_MISMATCH means this page is stale. A toast on top of the inline alert would say the
  // same thing twice, in a surface the user is already looking at.
  'checkForUpdate', // src/components/updates/UpdatePanel.tsx
  'startUpdateDownload', // src/components/updates/UpdatePanel.tsx
  'cancelUpdateDownload', // src/components/updates/UpdatePanel.tsx
  'applyUpdate', // src/components/updates/ApplyUpdateDialog.tsx
  'setUpdateSettings', // src/components/updates/UpdatePanel.tsx
  // Remote access renders a refused save (`REMOTE_ACCESS_INVALID` — a bad domain, no accounts yet)
  // beside the form, and a rejected enable is the switch staying off.
  'saveTunnelSettings', // src/components/remoteAccess/RemoteAccessPanel.tsx


  // Call site raises its own toast.error()
  'updateProject', // src/routes/ProjectSettings.tsx
  'updateInstall', // src/routes/InstallSettings.tsx
  // NB: 'updateStageRegion' and 'updateRigging' have TWO call sites each — the
  // edit form and the stage drag handler in src/routes/Stage.tsx. The drag path
  // used to swallow failures with `.catch(() => {})`, so a rejected move was
  // completely silent *and* left the rejected position in the cache. Both paths
  // now toast and roll back; if a third call site appears, it must do the same or
  // these names have to come off this list.
  'createStageRegion', // src/components/stage/EditStageRegionForm.tsx
  'updateStageRegion', // ...and the drag handler in src/routes/Stage.tsx
  'deleteStageRegion',
  'createRigging', // src/components/rigging/EditRiggingForm.tsx
  'updateRigging', // ...and the drag handler in src/routes/Stage.tsx
  'deleteRigging',
  // The scene element trio (stage-view plan session 5). A 400 lists every problem the desk's
  // `validateStageElement` found, and `EditSceneElementForm` draws each beside its field; a 409
  // (a name taken, seat views that would lose their seat) is a step in the form, not a failure. The
  // route's `+ Scenery` placement and its section drag report their own, as the region's do.
  'createStageElement', // src/routes/Stage.tsx (+ Scenery and ⌘D)
  'updateStageElement', // src/components/stage/EditSceneElementForm.tsx, and the drag in src/routes/Stage.tsx
  'deleteStageElement', // src/components/stage/EditSceneElementForm.tsx
  // NB: the script endpoints are deliberately *not* listed. `createProjectScript` has two call
  // sites — ScriptForm and CueTriggerEditor's inline-script step — and only one of them reported
  // anything, so deny-listing it made the other silent again. Both now rely on this middleware.

  // `commitPlacements` in store/stagePlacement.ts reports the outcome of a bulk
  // placement as one toast naming the operation ("Align left: 2 of 8 failed"),
  // which is more use than the raw transport error. It is the only permitted
  // caller of this endpoint.
  'bulkPlacements',

  // Cloud sync — src/routes/CloudSync.tsx and src/components/cloudSync/*
  'updateCloudSyncConfig',
  'cloudSyncReconnect',
  'cloudSyncRun',
  'cloudSyncDisconnect',
  'cloudSyncSnapshot',
  'cloudSyncImport',
  'cloudSyncResolve',
  'cloudSyncApply',
  'cloudSyncAbort',
  'setCloudSyncCredentials',
  'clearCloudSyncCredentials',
  'createGithubRepo',
  'startGithubDeviceFlow',
  'pollGithubDeviceFlow',
  'disconnectOAuthGithub',

  // Auth forms. Every failure here is something the operator typed — a wrong
  // password, a name already taken, a password the policy rejects — so it belongs
  // next to the field, not in a corner toast. Each renders the message itself via
  // <Alert variant="destructive">.
  'login', // src/components/auth/LoginScreen.tsx
  'setup', // src/components/auth/SetupScreen.tsx
  'changePassword', // src/components/auth/ProfileSheet.tsx
  // Same sheet, and the same reason twice over: the only thing that fails here is a display
  // name the user typed, and the field it belongs beside is right there.
  'updateProfile', // src/components/auth/ProfileSheet.tsx
  // The three WebSocket-backed mutations. They look like REST from the outside but their
  // transport is a fire-and-forget frame, and they now report a dropped write as a real error so
  // `.unwrap()` and `isError` agree with the rig. `sendGesture` has already raised the toast by
  // then — under one shared id, so a drag collapses to one — and a second toast here would say
  // the same thing again, per endpoint, out of sync with the first.
  'parkChannel', // src/api/wsGesture.ts raises it
  'unparkChannel', // ...same
  'updateChannel', // ...same
  // `render_view`'s answers (stage-view plan session 4). The render runs on an operator's screen
  // mid-show for a model that asked over MCP, and a refused answer — the desk gave up waiting, or
  // the window closed its request — is the model's to hear through the tool's own named error,
  // never a toast on that screen. `StageRenderHost` logs it to the console instead.
  'uploadStageRender', // src/components/stageRender/StageRenderHost.tsx
  'failStageRender', // ...same
])

/**
 * Swallows a rejected `.unwrap()` promise.
 *
 * [errorToastMiddleware] fires on the Redux action, which is independent of the promise
 * `.unwrap()` returns — so it reports the failure but does *not* stop the rejection becoming an
 * unhandled promise rejection. Attach this wherever a mutation is fired and nothing further
 * depends on its result: `void save(...).unwrap().catch(ignoreReportedError)`.
 *
 * Do not use it where subsequent code must be skipped on failure — use try/catch and return.
 */
export function ignoreReportedError(): void {}

/** Shape of the `meta` RTK Query attaches to a rejected endpoint action. */
interface RejectedMeta {
  arg?: { type?: string; endpointName?: string }
  condition?: boolean
}

/**
 * Surfaces failed RTK Query **mutations** as toasts.
 *
 * Without this a rejected mutation is completely invisible — no toast, no console line, no state
 * change — so a failing button just appears to do nothing. Uses RTK's own `isRejectedWithValue`
 * pattern rather than a listener middleware; there's no effect or cancellation logic to warrant
 * the heavier API.
 *
 * Queries are deliberately excluded: they retry and refetch on window focus, so a flaky
 * connection would produce a stream of duplicate toasts for something the app recovers from
 * on its own. Mutations are user-initiated and one-shot — if one fails, the user needs to know.
 */
export const errorToastMiddleware: Middleware = () => (next) => (action) => {
  if (isRejectedWithValue(action)) {
    const meta = action.meta as RejectedMeta | undefined
    const endpointName = meta?.arg?.endpointName

    // `condition` marks a request the client skipped or aborted (e.g. an unmounted component),
    // not something that actually failed.
    const isReportableMutation =
      meta?.arg?.type === 'mutation' && !meta.condition && endpointName !== undefined

    if (isReportableMutation && !SILENT_ENDPOINTS.has(endpointName)) {
      // A stable per-endpoint id makes sonner *replace* rather than stack, so a burst of failing
      // keystroke-driven saves (patchCue fires per edit) collapses into one toast, not ten.
      toast.error(formatError(action.payload), { id: `mutation-error:${endpointName}` })
    }
  }

  return next(action)
}

import React from "react"
import { Toaster } from "sonner"
import { AuthGate } from "./AuthGate"
import { BootGate } from "./BootGate"
import Layout from "./Layout"
import {createBrowserRouter, Navigate, useLocation, useParams} from "react-router";
// v8 keeps the DOM-specific entry points in `react-router/dom`.
import {RouterProvider} from "react-router/dom";
import {
  ChannelsRedirect,
  ChannelsBaseRedirect,
  ProjectChannels,
  ProjectChannelsDefaultUniverse,
} from "./routes/Channels";
import { ProjectChannelsTable } from "./routes/ChannelsTable";
import { FixturesRedirect, ProjectFixtures } from "./routes/Fixtures";
import { FixturesListRedirect, ProjectFixturesList } from "./routes/FixturesList";
import { GroupsRedirect, ProjectGroups } from "./routes/Groups";
import { GroupsListRedirect, ProjectGroupsList } from "./routes/GroupsList";
import Projects from "./routes/Projects";
import ProjectScripts, { ScriptsRedirect } from "./routes/ProjectScripts";
import { ProjectFxLibrary, FxLibraryRedirect } from "./routes/FxLibrary";

import { BuskRedirect, ProjectBusk } from "./routes/Busk";
import { LooksRedirect, ProjectLooks } from "./routes/Looks";
import { TemplatesRedirect, ProjectTemplates } from "./routes/Templates";
import { SpeedMastersRedirect, ProjectSpeedMasters } from "./routes/SpeedMasters";
import ProjectOverview, { ProjectOverviewRedirect } from "./routes/ProjectOverview";
import { PatchesRedirect, ProjectPatches } from "./routes/Patches";
import { ShowPage, ShowRedirect } from "./routes/ShowPage";
import {
  ProgrammerPage,
  ProgrammerRedirect,
  ProgrammerFxRedirect,
} from "./routes/ProgrammerPage";
// Redirects for paths that no longer name a view — see the module header for why they are
// collected rather than parked in whichever module happens to be the destination.
import {
  CuesLegacyRedirect,
  LegacyCueStacksRedirect,
  LegacyFxRedirect,
  LegacyProgramRedirect,
  LegacyRunRedirect,
} from "./routes/legacyRedirects";
import { SurfacesRedirect } from "./routes/Surfaces";
import { DiagnosticsRedirect } from "./routes/Diagnostics";
import { CloudSyncHubRedirect } from "./routes/CloudSync";
import { ProjectSettings, ProjectSettingsRedirect } from "./routes/ProjectSettings";
import { InstallSettings } from "./routes/InstallSettings";
import { ResetPasswordPage } from "./routes/ResetPasswordPage";
import { DeviceLoginPage } from "./routes/DeviceLoginPage";
import { isPublicPath } from "./lib/publicPath";

// Route-level lazy boundaries for two of the four heavy islands.
//
// `routes/Stage` pulls @react-three/fiber, drei and postprocessing (~290 kB);
// `routes/PromptBookPage` pulls react-pdf and pdfjs (~415 kB). Neither is on the path to the
// login screen, so importing them statically made every cold boot — and every post-update
// restart — parse both before anything painted. The redirects live in the same modules and so
// ride the same chunks: visiting `/stage` or `/prompt-book` fetches the chunk to then redirect
// into a page that needs it anyway.
//
// The Suspense boundary these resolve against is in `Layout`, around the router `Outlet`; both
// routes are children of Layout. `AuthGate` and `BootGate` stay statically imported — they are
// what decides whether a router renders at all, and neither may sit behind a chunk fetch.
//
// This does *not* take `three` itself out of the entry chunk: `lib/stageCoords.ts` is Three-typed
// and reaches non-3D code through `hooks/useProjectedPatches`, which the Layout's stage overview
// panel uses. Separating those helpers is a job of its own.
const Stage = React.lazy(() => import("./routes/Stage").then((m) => ({ default: m.Stage })));
const StageRedirect = React.lazy(() =>
  import("./routes/Stage").then((m) => ({ default: m.StageRedirect })),
);
const PromptBookViewerPage = React.lazy(() =>
  import("./routes/PromptBookPage").then((m) => ({ default: m.PromptBookViewerPage })),
);
const PromptBookRedirect = React.lazy(() =>
  import("./routes/PromptBookPage").then((m) => ({ default: m.PromptBookRedirect })),
);

// The patch list was the Patch List tab of Project Settings between the tabbed-settings
// restructure and the list shell (CLAUDE.md §List shell); a bookmark or a link into the tab lands
// on the page, `?action=new` and all.
function SettingsPatchesToPatches() {
  const { projectId } = useParams()
  const { search } = useLocation()
  return <Navigate to={`/projects/${projectId}/patches${search}`} replace />
}
function SurfacesToSettings() {
  const { projectId } = useParams()
  return <Navigate to={`/projects/${projectId}/settings/surfaces`} replace />
}
function ProjectSyncToSettings() {
  const { projectId } = useParams()
  return <Navigate to={`/projects/${projectId}/settings/sync`} replace />
}

// `isPublicPath` (see `lib/publicPath.ts` for what it matches and why) decides which pages skip
// both `AuthGate` and `BootGate`.
//
// Read once at module scope, which is safe only because these pages are siblings of the
// router's Layout rather than routes inside it, and because the one that finishes with a
// session (`/device/`) does so with `window.location.assign` — a document load, which
// re-evaluates this. **A react-router `navigate('/')` from either page would render the whole
// app with both gates bypassed.** `DeviceLoginPage`'s test pins the document-level navigation
// for exactly that reason.
const publicPath = isPublicPath(window.location.pathname)

function App() {
  const router = createBrowserRouter([
    {
      path: "/",
      element: <Layout />,
      children: [
        {
          index: true,
          element: <ProjectOverviewRedirect />,
        },
        {
          path: "projects/:projectId",
          element: <ProjectOverview />,
        },
        {
          path: "projects/:projectId/fixtures/list",
          element: <ProjectFixturesList />,
        },
        {
          path: "fixtures/list",
          element: <FixturesListRedirect />,
        },
        {
          path: "projects/:projectId/fixtures",
          element: <ProjectFixtures />,
        },
        {
          path: "projects/:projectId/stage",
          element: <Stage />,
        },
        {
          path: "stage",
          element: <StageRedirect />,
        },
        {
          path: "projects/:projectId/groups/list",
          element: <ProjectGroupsList />,
        },
        {
          path: "groups/list",
          element: <GroupsListRedirect />,
        },
        {
          path: "projects/:projectId/groups",
          element: <ProjectGroups />,
        },
        {
          path: "groups",
          element: <GroupsRedirect />,
        },
        {
          path: "projects/:projectId/busk",
          element: <ProjectBusk />,
        },
        {
          path: "busk",
          element: <BuskRedirect />,
        },
        // The busk view was `/fx` until the busking-view plan's session 3. Kept so a bookmark, a
        // Cmd+K deep link or muscle memory lands. Distinct from `/fx-library`, which is untouched.
        {
          path: "projects/:projectId/fx",
          element: <LegacyFxRedirect />,
        },
        {
          path: "fx",
          element: <LegacyFxRedirect />,
        },
        // The Look library: **one route, no filter**. Recorded states over named fixtures; a Look
        // spans families by nature, so there is nothing here for a family filter to partition.
        {
          path: "projects/:projectId/looks",
          element: <ProjectLooks />,
        },
        {
          path: "looks",
          element: <LooksRedirect />,
        },
        // The template library: **one route**, with the sticky in-page family filter that used to
        // live on `/looks`. Here a family really is an exact partition — a template is in exactly
        // one — so `?family=` deep-links from Cmd+K land on a filtered view of one page.
        {
          path: "projects/:projectId/templates",
          element: <ProjectTemplates />,
        },
        {
          path: "templates",
          element: <TemplatesRedirect />,
        },
        {
          path: "projects/:projectId/speed-masters",
          element: <ProjectSpeedMasters />,
        },
        {
          path: "speed-masters",
          element: <SpeedMastersRedirect />,
        },
        // Legacy FX Cues routes — the view was folded into Program. Redirect to keep bookmarks alive.
        {
          path: "projects/:projectId/cues",
          element: <CuesLegacyRedirect />,
        },
        {
          path: "projects/:projectId/cues/all",
          element: <CuesLegacyRedirect />,
        },
        {
          path: "projects/:projectId/cues/standalone",
          element: <CuesLegacyRedirect />,
        },
        {
          path: "projects/:projectId/cues/stacks/:stackId",
          element: <CuesLegacyRedirect />,
        },
        {
          path: "cues",
          element: <ShowRedirect />,
        },
        {
          path: "projects/:projectId/settings",
          element: <ProjectSettings />,
        },
        {
          path: "projects/:projectId/settings/:tab",
          element: <ProjectSettings />,
        },
        {
          path: "settings",
          element: <ProjectSettingsRedirect />,
        },

        {
          path: "install",
          element: <InstallSettings />,
        },
        {
          path: "install/:tab",
          element: <InstallSettings />,
        },

        // Cloud sync hub now lives as the Sync tab inside Install Settings, and the
        // per-project sync UI lives in Project Settings → Sync. /sync and the old
        // drill-in remain as back-compat redirects.
        {
          path: "sync",
          element: <CloudSyncHubRedirect />,
        },
        {
          path: "sync/projects/:projectId",
          element: <ProjectSyncToSettings />,
        },

        {
          path: "projects/:projectId/patches",
          element: <ProjectPatches />,
        },
        // Legacy per-project paths — keep working but redirect to the new location.
        {
          path: "projects/:projectId/settings/patches",
          element: <SettingsPatchesToPatches />,
        },
        {
          path: "projects/:projectId/surfaces",
          element: <SurfacesToSettings />,
        },
        {
          path: "projects/:projectId/diagnostics",
          element: <Navigate to="/install/diagnostics" replace />,
        },
        {
          path: "projects/:projectId/sync",
          element: <ProjectSyncToSettings />,
        },

        // Legacy bare paths (no project context) — keep until Cmd+K migrates.
        {
          path: "patches",
          element: <PatchesRedirect />,
        },
        {
          path: "surfaces",
          element: <SurfacesRedirect />,
        },
        {
          path: "diagnostics",
          element: <DiagnosticsRedirect />,
        },
        {
          path: "projects/:projectId/channels",
          element: <ProjectChannelsDefaultUniverse />,
        },
        {
          path: "projects/:projectId/channels/:universe",
          element: <ProjectChannels />,
        },
        // The DMX sheet — the cards route's sibling, reached through the Cards · Table switcher
        // and the sticky preference, never from the sidebar (§Navigation Registry).
        {
          path: "projects/:projectId/channels/:universe/table",
          element: <ProjectChannelsTable />,
        },
        {
          path: "channels",
          element: <ChannelsBaseRedirect />,
        },
        {
          path: "channels/:universe",
          element: <ChannelsRedirect />,
        },
        {
          path: "scripts/:scriptId?",
          element: <ScriptsRedirect />,
        },
        {
          path: "projects/:projectId/scripts/:scriptId?",
          element: <ProjectScripts />,
        },
        {
          path: "fx-library",
          element: <FxLibraryRedirect />,
        },
        {
          path: "projects/:projectId/fx-library",
          element: <ProjectFxLibrary />,
        },

        // Show — the cue/stack authoring surface. It was `/program` until the programmer moved out
        // of it into a page of its own; `/program*` below keeps every existing link alive.
        {
          path: "projects/:projectId/show",
          element: <ShowPage />,
        },
        {
          path: "projects/:projectId/show/stacks/:stackId",
          element: <ShowPage />,
        },
        // The cue sheet — the stack's cards route's sibling, reached through the Cards · Table
        // switcher on the stack header and the sticky preference (§Navigation Registry).
        {
          path: "projects/:projectId/show/stacks/:stackId/table",
          element: <ShowPage stackView="list" />,
        },
        {
          path: "show",
          element: <ShowRedirect />,
        },
        // The programmer: values, layers and effects on one screen. It WAS its own page, then
        // became three tabs of a pane inside Program, and is a page again — the tabs were three
        // readings of one live object and could never be seen together, which is the whole point.
        {
          path: "projects/:projectId/programmer",
          element: <ProgrammerPage />,
        },
        {
          path: "programmer",
          element: <ProgrammerRedirect />,
        },
        // `/programmer/fx` was a route when the FX sheet was a destination. It is a section of the
        // page now, so the path survives only to land a bookmark.
        {
          path: "projects/:projectId/programmer/fx",
          element: <ProgrammerFxRedirect />,
        },
        // Legacy `/program*` → `/show*`, search preserved: `?cue=` is an external contract, minted
        // by the Prompt Book's "Edit cue" (its rail card — the only live producer of one).
        {
          path: "projects/:projectId/program",
          element: <LegacyProgramRedirect />,
        },
        {
          path: "projects/:projectId/program/stacks/:stackId",
          element: <LegacyProgramRedirect />,
        },
        {
          path: "program",
          element: <ShowRedirect />,
        },
        // Legacy `/run` → `/show`. Run folded into Show in session 2b: the two were never different
        // destinations, only different answers to "can a stray click change the show", which is a
        // mode. `/run` is an internal path rather than an external contract like `?cue=`, but a
        // redirect costs nothing and Cmd+K deep links and bookmarks both exist.
        {
          path: "projects/:projectId/run",
          element: <LegacyRunRedirect />,
        },
        {
          path: "run",
          element: <ShowRedirect />,
        },
        {
          path: "projects/:projectId/prompt-book",
          element: <PromptBookViewerPage />,
        },
        {
          path: "prompt-book",
          element: <PromptBookRedirect />,
        },
        // Legacy `/cue-stacks` → `/show`. `/show` used to live here too, back when the PLAYBACK
        // view was called Show; it now names the one merged view, so there is no longer a
        // distinction for an old bookmark to land on the wrong side of.
        {
          path: "projects/:projectId/cue-stacks",
          element: <LegacyCueStacksRedirect />,
        },
        {
          path: "cue-stacks",
          element: <LegacyCueStacksRedirect />,
        },
        {
          path: "fixtures",
          element: <FixturesRedirect />,
        },
        {
          path: "projects",
          element: <Projects />,
        },
      ],
    },
    // Siblings of Layout, not children: a phone that scans one of these QRs has no session,
    // no project context, and no business rendering the sidebar or the ShowBar. Paired with
    // the `publicPath` bypass above, which keeps both gates out of their way.
    {
      path: "reset/:token",
      element: <ResetPasswordPage />,
    },
    {
      path: "device/:token",
      element: <DeviceLoginPage />,
    },
  ])

  return (
      <React.StrictMode>
        <AuthGate bypass={publicPath}>
          <BootGate bypass={publicPath}>
            <RouterProvider router={router}/>
          </BootGate>
        </AuthGate>
        <Toaster position="bottom-right" />
      </React.StrictMode>
  )
}

export default App

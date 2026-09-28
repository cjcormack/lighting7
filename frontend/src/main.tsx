import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';
import { Provider } from "react-redux"
import { store } from "./store"
import { applyThemeClass, getInitialTheme } from "./lib/theme"
import { startOAuthIdentityBridge } from "./store/oauthGithub"
import { startLooksBridge } from "./store/looks"
import { startTemplatesBridge } from "./store/templates"
import { startProgrammerErrorBridge } from "./store/programmerErrors"
import { windowName } from "./lib/windowIdentity"
import { applyImmersiveLaunch } from "./lib/immersive"

// Apply the stored (or system-preferred) theme before React mounts. The boot
// loading overlay renders before the user menu's ThemeMenuItem effect runs, so without
// this the overlay would be light regardless of preference; this also removes
// the flash-of-light on normal loads. Resolution is shared with ThemeMenuItem.
applyThemeClass(getInitialTheme())

// Keep the GitHub identity cache live for the whole app, so the sidebar badge and the re-auth
// banner learn about a rejected authorisation without a sync page being open. Started here
// rather than on import of the slice: see startOAuthIdentityBridge for why touching lightingApi
// at that module's evaluation time breaks the slice outright.
startOAuthIdentityBridge()

// Same reason, one slice along: store/looks is imported from the sidebar's nav registry and from
// pickers that mount everywhere, so its WS bridge cannot run at module-eval time either.
startLooksBridge()
startTemplatesBridge()

// The programmer's WS write path reports its refusals on `programmer.error` and nothing else —
// there is no REST action for `errorToastMiddleware` to catch — so an unheard frame means a
// slider that moved while the rig did not. See startProgrammerErrorBridge.
startProgrammerErrorBridge()

// This tab's name (multi-screen plan D10): `?window=` is read once and stripped here, before the
// router is created, so the router never sees the parameter and a bookmark of the stripped URL
// does not re-mint the name on the next tab. The announce that carries it to the desk's windows
// registry is `useWindowsBridge`, inside the router. See lib/windowIdentity.ts.
windowName()
// `?immersive=on` rides the same read and is stripped with it (busk-chrome plan D9): applied here
// so a live view's first paint is already the shape the link asked for. See lib/immersive.ts.
applyImmersiveLaunch()

const root = ReactDOM.createRoot(
  document.getElementById('root') as HTMLElement
);
root.render(
  <React.StrictMode>
    <Provider store={store}>
      <App />
    </Provider>
  </React.StrictMode>
);

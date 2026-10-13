import { fileURLToPath } from "node:url"
import { sentryVitePlugin } from "@sentry/vite-plugin"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"
import { defineConfig, searchForWorkspaceRoot } from "vite"

// Gradle's `buildFrontend` passes both (Sentry plan D10, D14): the release from :generateBuildInfo's
// values when the build carries a commit, and the auth token only in the release workflow. Without
// the token there are no source maps and no upload, so a local or CI build ships neither.
const release = process.env.LIGHTING7_RELEASE ?? ""
const uploadSourceMaps = !!process.env.SENTRY_AUTH_TOKEN

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    ...(uploadSourceMaps
      ? [
          sentryVitePlugin({
            org: "lighting7",
            project: "lighting7-react",
            authToken: process.env.SENTRY_AUTH_TOKEN,
            telemetry: false,
            release: { name: release || undefined, inject: false, setCommits: { auto: true, ignoreMissing: true } },
            sourcemaps: { filesToDeleteAfterUpload: ["./dist/**/*.map"] },
          }),
        ]
      : []),
  ],
  define: {
    __LIGHTING7_RELEASE__: JSON.stringify(release),
  },
  build: {
    sourcemap: uploadSourceMaps ? "hidden" : false,
  },
  resolve: {
    alias: [
      { find: "@", replacement: fileURLToPath(new URL("./src", import.meta.url)) },
    ],
  },
  server: {
    open: true,
    proxy: {
      // Covers /api/rest, the WebSocket, /api/script-editor and the Sentry tunnel.
      "/api": "http://localhost:8413/",
    },
    fs: {
      // lib/sentryScrub.ts imports the backend's list of secret patterns.
      allow: [searchForWorkspaceRoot(process.cwd()), fileURLToPath(new URL("../src/main/resources/telemetry/", import.meta.url))],
    },
  },
})

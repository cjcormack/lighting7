import { fileURLToPath } from "node:url"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vitest/config"

export default defineConfig({
  // The React plugin is needed to transform JSX in component tests; it's a
  // no-op for the plain-TS unit tests. The `@ -> ./src` alias mirrors
  // vite.config.ts so `@/...` imports resolve under vitest too.
  plugins: [react()],
  resolve: {
    alias: [
      { find: "@", replacement: fileURLToPath(new URL("./src", import.meta.url)) },
    ],
  },
  test: {
    // Node is the default so the pure-function unit tests stay fast. Component
    // tests opt into jsdom per-file with a `// @vitest-environment jsdom`
    // docblock at the top of the file.
    environment: "node",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    // Registers testing-library cleanup + jest-dom matchers for the jsdom files, and
    // does nothing at all for the node ones. See the file for why cleanup can't be
    // left to each test to remember.
    setupFiles: ["./src/test/setup.ts"],
  },
})

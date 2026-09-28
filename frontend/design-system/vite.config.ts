import { fileURLToPath } from "node:url"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"
import { defineConfig } from "vite"

// Library build of the design system for the design-sync converter. Everything
// from node_modules stays external: the converter bundles dependencies itself
// and maps react / react-dom onto the host page's copies. The Tailwind plugin
// compiles src/index.css against the whole repo, so the emitted stylesheet
// carries every token and every utility the app actually uses.
const here = (p: string) => fileURLToPath(new URL(p, import.meta.url))

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: [{ find: "@", replacement: here("../src") }],
  },
  build: {
    outDir: here("./dist"),
    emptyOutDir: true,
    sourcemap: false,
    minify: false,
    lib: {
      entry: here("./index.ts"),
      formats: ["es"],
      fileName: () => "index.js",
      cssFileName: "index",
    },
    rollupOptions: {
      external: (id) =>
        !id.startsWith(".") && !id.startsWith("/") && !id.startsWith("@/") && !id.startsWith("\0"),
    },
  },
})

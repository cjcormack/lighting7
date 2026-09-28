import { ThemeToggle } from 'lighting-desk-ui'

// The toggle is coloured for the app header (`text-primary-foreground`), so it sits on a
// primary strip here the way it does in Layout.
export const InHeader = () => (
  <div className="flex h-12 items-center justify-between rounded-md bg-primary px-3 text-primary-foreground">
    <span className="text-sm font-semibold">Lighting Desk · Autumn Tour 2026</span>
    <ThemeToggle />
  </div>
)

export const Standalone = () => (
  <div className="flex items-center gap-3">
    <div className="flex h-12 w-12 items-center justify-center rounded-md bg-primary">
      <ThemeToggle />
    </div>
    <p className="text-xs text-muted-foreground">
      Ghost icon button. Toggles the <code>dark</code> class on the document root and remembers the
      choice in localStorage.
    </p>
  </div>
)

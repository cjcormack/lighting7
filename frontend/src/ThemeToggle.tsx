import { Moon, Sun } from "lucide-react"
import { DropdownMenuItem } from "@/components/ui/dropdown-menu"
import { toggleTheme, useTheme } from "@/lib/theme"

/**
 * The light/dark choice, read from `lib/theme.ts`'s one store and applied and persisted there.
 *
 * It was a `useState` seeded once from storage inside this file, which is why **exactly one
 * instance** of the control was allowed: two mount-time snapshots drift, and the one you did not
 * press goes on claiming the old theme. The busk-chrome plan's session B gave the theme a second
 * door — a ⌘K command, because an immersive window has no user menu (D10) — so the fact moved
 * into a subscribable and both controls read it. The reason it is not `lib/syncStore.ts` is
 * recorded on the store: that JSON-encodes, and `theme` is the bare string `dark` that
 * `getInitialTheme` reads before React exists.
 */
function useThemeChoice() {
  const theme = useTheme()
  return {
    theme,
    next: theme === "light" ? ("dark" as const) : ("light" as const),
    toggle: toggleTheme,
  }
}

/**
 * The theme control as a row of the user menu, which is where it lives now.
 *
 * It was a ninth icon button in the app header. That row does not fit an iPhone in portrait — nine
 * controls came to 439px against a 375px viewport, and the avatar at the end of it was the part
 * pushed off the screen — and of everything on it, a per-viewer display preference is the one
 * thing that is not a desk control at all. So it sits with the other per-viewer things instead,
 * one press further away in exchange for 40px on every width.
 */
export function ThemeMenuItem() {
  const { theme, next, toggle } = useThemeChoice()
  return (
    <DropdownMenuItem onSelect={toggle}>
      {theme === "light" ? <Moon className="size-4" /> : <Sun className="size-4" />}
      Switch to {next} mode
    </DropdownMenuItem>
  )
}

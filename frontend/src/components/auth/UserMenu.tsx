import { useState } from "react"
import { CircleUser, LogOut, Maximize2, Minimize2, MonitorSmartphone } from "lucide-react"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useAuthStatusQuery, useLogoutMutation } from "@/store/auth"
import { RoleBadge } from "@/components/users/RoleBadge"
import { ProfileSheet } from "./ProfileSheet"
import { ThemeMenuItem } from "@/ThemeToggle"
import { canFullscreen, enterFullscreen, exitFullscreen, useFullscreenState } from "@/lib/fullscreen"
import { useWindowName } from "@/lib/windowIdentity"
import { useDeskWindows } from "@/store/windows"
import { openScreensSheet } from "@/components/screens/screensSheetState"

// Words are filtered to those that *start* with a letter or digit, not merely contain one, so
// a name like "Chris C (desk)" initials as "CC" rather than "C(" — a parenthesised qualifier
// is the commonest shape here (a desk, a room, a role) and it should never be what the avatar
// shows. Anything with no usable word at all falls back to "?" rather than rendering
// punctuation.
function initials(displayName: string): string {
  const words = displayName
    .trim()
    .split(/\s+/)
    .filter((word) => /^[\p{L}\p{N}]/u.test(word))
  if (words.length === 0) return "?"
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[words.length - 1][0]).toUpperCase()
}

export function UserMenu() {
  const { data } = useAuthStatusQuery()
  const [logout] = useLogoutMutation()
  const [profileOpen, setProfileOpen] = useState(false)
  const windowName = useWindowName()
  const windows = useDeskWindows()
  const { active: fullscreen } = useFullscreenState()

  // Bootstrap-open: no users exist yet, so there is no identity to show — the setup screen is what
  // prompts for one — but the menu still opens, because three of its items are per-*viewer* and
  // not per-account: the theme, full screen and Screens…. It used to return a bare `ThemeToggle`
  // here, which kept the theme reachable and quietly lost the other two the day they were added.
  // Handled in this one place rather than by `Layout` re-testing `!user` beside its `<UserMenu/>` —
  // the same rule stated twice is this repo's recurring way of having it come true in only one of
  // them.
  const user = data?.user ?? null

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="text-primary-foreground hover:bg-primary-foreground/10"
            aria-label={user ? `Signed in as ${user.displayName}` : "Menu"}
          >
            {user ? (
              <Avatar className="size-7">
                <AvatarFallback className="bg-primary-foreground/15 text-xs text-primary-foreground">
                  {initials(user.displayName)}
                </AvatarFallback>
              </Avatar>
            ) : (
              <CircleUser className="size-5" />
            )}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuLabel className="space-y-1">
            {user && (
              <>
                <div className="truncate font-medium">{user.displayName}</div>
                <div className="flex items-center gap-2">
                  <span className="truncate text-xs font-normal text-muted-foreground">
                    {user.username}
                  </span>
                  <RoleBadge role={user.role} />
                </div>
              </>
            )}
            {/* Which window this is (multi-screen plan §4, `Screens.dc.html` §1). The exit glyph
                beside it is the one place full screen is drawn *in* the app — never a floating
                button on a live view — and only while full screen, when Esc may be locked. */}
            <div className="flex items-center gap-2 text-xs font-normal text-muted-foreground">
              <span className="min-w-0 flex-1 truncate">
                this window is <span className="text-foreground">{windowName}</span>
              </span>
              {fullscreen && (
                <button
                  type="button"
                  aria-label="Exit full screen"
                  title="Exit full screen"
                  className="rounded-xs text-muted-foreground hover:text-foreground"
                  onClick={() => void exitFullscreen()}
                >
                  <Minimize2 className="size-3.5" />
                </button>
              )}
            </div>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          {/* Three account items, deliberately. Everything self-service — name, password, devices, the
              sign-in QR — is in the one sheet, and "Manage users" is gone: the `users` nav entry
              is already `adminOnly`, so the sidebar and Cmd+K carry that page and a second entry
              point here only meant role-filtering the same destination twice.

              Theme is the third, and it is the one thing here that is not about the account: it is
              a per-*viewer* display preference, which is the same kind of thing as the rest of
              this menu and not the same kind as the desk controls it used to sit among in the
              header — where, as a ninth icon button, it also did not fit a phone. */}
          {user && (
            <DropdownMenuItem onSelect={() => setProfileOpen(true)}>
              <CircleUser className="size-4" />
              Profile…
            </DropdownMenuItem>
          )}
          <ThemeMenuItem />
          {/* Full screen and Screens… (D14: nothing new in the header row — the two go here, with
              the other per-viewer things, and in ⌘K). Full screen is feature-detected and absent
              rather than disabled where the browser has no Fullscreen API (D13). */}
          {canFullscreen() && (
            <DropdownMenuItem onSelect={() => void (fullscreen ? exitFullscreen() : enterFullscreen())}>
              {fullscreen ? <Minimize2 className="size-4" /> : <Maximize2 className="size-4" />}
              {fullscreen ? "Exit full screen" : "Full screen"}
              <DropdownMenuShortcut>⇧F</DropdownMenuShortcut>
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onSelect={openScreensSheet}>
            <MonitorSmartphone className="size-4" />
            Screens…
            <DropdownMenuShortcut>
              {windows.length} {windows.length === 1 ? "window" : "windows"}
            </DropdownMenuShortcut>
          </DropdownMenuItem>
          {user && (
            <DropdownMenuItem onSelect={() => void logout()}>
              <LogOut className="size-4" />
              Log out
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {user && <ProfileSheet user={user} open={profileOpen} onOpenChange={setProfileOpen} />}
    </>
  )
}

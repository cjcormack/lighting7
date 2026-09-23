# Desk screens — two windows, one programmer

The theatre desk runs two touch screens and, usually, an iPad. Every console makes a second screen
a **view on one programmer** rather than a second programmer, and so does this one: the fixture
selection, its attribute mask, the busk page, blind, the fade and the programmer's layer stack are
all **desk facts** — one value, server-owned, broadcast to everyone (`state/DeskSelection.kt`,
`docs/lighting-composition-model.md` §"Layer 2"). A window is a *viewport*, and the only things it
owns are what it is looking at and how it is framed.

**Two of those a window may decline to follow**, and the desk keeps holding them either way: the
selection (`lib/deskFollow.ts` in the client) and, since 2026-09-16, the busk page
(`lib/buskPageFollow.ts`). Each is a per-tab flag defaulting to follow, and each is marked either
way: a small link badge while it follows, a dashed chip while it does not — *This window* for the
selection, *Page: Own* for the page — and each is the toggle: press the badge to leave, the chip to
come back. Both can also be set from any window's Screens sheet: the selection with
`windows.follow`, the page with the row's *Page · Paged with the desk | Own page* segment. The
page is a **paging group** — every window paged with the desk, and the MIDI page buttons, page
together — so its badge names the other windows in the group, and a tab click on one pages them all.
The selection can only be declined where the window both selects and acts, which is busk Split and
the Programmer; Rig and Pads focus always follow
([`plans/completed/desk-follow-plan.md`](plans/completed/desk-follow-plan.md) D1–D11). The two flags
are deliberately independent — a second screen showing a *position* page while the first shows
a *colour* page, both pressing onto the one selection, is the case the page half was added for. The
desk fact does not change: a hardware *next page* button still moves `BuskPageState`, and every
window paged with the desk still moves with it.

This document is the operator-facing half: how a desk screen is opened, what names it, and the one
platform rule that silently breaks the whole thing when it is got wrong. The wire is in
[`websocket-engineering.md`](websocket-engineering.md) §"Windows"; the decisions and their reasons
are in [`plans/completed/multi-screen-plan.md`](plans/completed/multi-screen-plan.md) §2–§3.

## The one rule: open the desk screens at `http://localhost:8413/`

The desk serves **plain HTTP**. Three of the browser features a chromeless desk screen wants are
**secure-context** features — they exist only on an origin the browser considers
potentially-trustworthy:

| Feature | Used for | Secure context? |
|---|---|---|
| Web app installation (manifest `display`) | the chromeless launch with no Esc trap | yes |
| `Keyboard.lock(['Escape'])` | keeping Esc for the app instead of the browser | yes |
| Window Management (`getScreenDetails`) | *Open on Display 2* | yes |
| Fullscreen API (`requestFullscreen`) | the in-app *Full screen* item | **no** |

`http://localhost` **is** potentially-trustworthy; `http://lighting7-<host>.local:8413/` is not. So:

- **The two desk screens must be opened at `http://localhost:8413/`.** This is why the launcher's
  tray items hard-code that URL, and why *Copy link for another device* — which mints the LAN
  name — is a separate item.
- **The iPad gets none of the three.** It reaches the desk over the LAN, so it has the Fullscreen
  API and nothing else. That is the whole of the iPad story: *Add to Home Screen* on the copied
  link, which gives `standalone` from the manifest, plus the in-app *Full screen* item with
  Safari's own overlay button and swipe-down exit.

Nothing in the UI announces this. A desk screen that will not install, whose Esc keeps leaving
full screen, and whose *Open on Display 2* row is missing, has almost certainly been opened at the
`.local` URL. `FU-DESK-TLS` is the item for making the LAN origin trustworthy; until then, the rule
above is the whole mitigation.

## Opening a desk screen

### Windows — the tray items

The launcher's tray menu carries **Open Screen 1** and **Open Screen 2** beside *Open*. Each
spawns the installed browser in app mode:

```
msedge.exe --app=http://localhost:8413/?window=Screen%201
```

- **Edge first, then Chrome** (`launcher/DeskScreens.kt`), because a Windows desk has Edge and a
  second browser is the operator's own choice. Both `Program Files` roots are searched, and
  Chrome's per-user install under `%LOCALAPPDATA%` — a Chrome installed without admin rights is
  still a desk browser.
- **The items are absent, not disabled, where there is nothing to open them with**: on macOS, and
  on a Windows host with neither browser installed. `--app=` is a Chromium flag; a greyed-out item
  saying "use Chrome" would be a worse answer than no item.
- **Where each window opens is not remembered yet.** The two screens land wherever the browser
  last put them and the operator drags one across. `--window-position` is `FU-LAUNCHER-SCREEN-POSITION`.

### macOS — Add to Dock

Safari is a first-class desk browser here, not a fallback, and it has no `--app=`. The durable
route is **Add to Dock** (Safari 17, macOS Sonoma) on `http://localhost:8413/?window=Screen%201`,
then macOS full screen from the window's green button. That is OS full screen rather than the
Fullscreen API, so Esc does not leave it and no Keyboard Lock is needed — which is just as well,
because Safari has none.

The macOS tray therefore gains **no** screen items. Whether Safari will hold two Dock apps of one
origin with two different `?window=` start URLs, and whether it keeps the query on launch, is not
documented and is desk check S2.7; `FU-LAUNCHER-SCREEN-POSITION` covers whatever that finds.

### Anything else — the copied link

*Copy link for another device* in the Screens sheet mints `http://<lanUrl>/?window=<name>`. That is
how the iPad and any ad-hoc laptop arrive. They are ordinary windows in the registry and can be
moved from any other screen.

## What names a window

A window's name is what the Screens sheet lists, what the desk chip reads (*Desk · from Screen 1*)
and what a `selection.state` `source` carries.

- **`?window=Screen%202` on the launch URL wins**, once. It is read at boot into that tab's
  `sessionStorage` and stripped from the address bar. This is what makes a tray item, a Dock app
  and an *Add to Home Screen* icon name their windows without a per-browser store.
- **`sessionStorage`, never `localStorage`.** The two desk screens are two windows of one browser
  profile, and `localStorage` is shared by the profile — a name kept there would be one name for
  both screens. `sessionStorage` is per tab and survives a reload, which is exactly the lifetime a
  window name wants.
- **A tab opened by hand** gets *Window* plus a short suffix, and can be renamed for the life of
  that tab — from its own user menu or from any other window's Screens sheet.
- **A duplicated tab copies its `sessionStorage`**, so two windows can carry the same name and the
  same client-minted `windowId`. They are still two rows and still separately addressable: the id
  the desk uses is minted per socket, not by the client.

## Kiosk mode

Not built, and nothing to build. For a screen nobody should be able to leave — a front-of-house
tablet, a lobby display — use the browser's or the OS's own kiosk mode:

```
msedge.exe --kiosk http://localhost:8413/?window=Lobby --edge-kiosk-type=fullscreen
```

It is a stronger guarantee than anything the app can offer from inside a page, and it is a
launch-time decision rather than an app setting, which is why it lives here as a note rather than
as a control in the Screens sheet.

## Related documentation

- [WebSocket Protocol](websocket-engineering.md) §"Windows" — the `windows.*` family: the registry,
  the announce, and the five commands one screen sends another
- [Composition Model](lighting-composition-model.md) — why the selection is one desk fact and not
  one per window
- [Windows Updates](windows-updates.md) — the launcher these tray items live in
- [`plans/completed/multi-screen-plan.md`](plans/completed/multi-screen-plan.md) — the decisions, the browser support
  matrix (§3.6) and the sessions

SURVEY = """
<div class="col" style="gap:8px;">
  <div class="lbl">Survey · six desks, from their manuals</div>
  <h1 class="h1">Nobody makes follow a window setting. Selection follows an identity; the page follows a group.</h1>
  <p class="lede">What each desk shares between screens and stations, and where the switch lives. <span class="tag call">unverified</span> marks what the manuals did not settle; the sources are listed underneath.</p>
</div>

<table>
  <tr><th style="width:120px;">Desk</th><th style="width:360px;">Selection shared by…</th><th style="width:420px;">Page shared by…</th><th>Per-window link · where the switch lives</th></tr>
  <tr><td class="name">grandMA3</td><td><b>User profile.</b> It holds programmer, selection, selected sequence and current page; users on one profile share them. Log a station in as a different profile to separate.</td><td><b>User profile</b> — every screen of a user is on one page. The Playback window has a <i>Page</i> setting pointing it at a page. <span class="tag call">unverified</span> whether it can follow the current page.</td><td>Sheets have <b>Link Type: Fixed · Selected · LastGo</b>; a layout view follows the selected layout or a fixed one. In the window's title-bar settings.</td></tr>
  <tr><td class="name">ETC Eos</td><td><b>User ID.</b> Devices on one ID share command line, selected channels, selected cue, live/blind. Default 1, set in Setup.</td><td><b>Paging groups.</b> Paging any bank in a group pages the whole group. Internal banks default to group 1, external wings to 2.</td><td>Fader-to-fader links only. In Setup › Device.</td></tr>
  <tr><td class="name">Hog 4</td><td><b>Console</b> — each keeps its own programmer. <span class="tag call">unverified</span> any user or shared-editor model.</td><td><b>Console tracking</b>: consoles opt into tracking each other's page, master and playback state, keeping their own programmers.</td><td>None found.</td></tr>
  <tr><td class="name">MagicQ</td><td><b>Console</b> by default; a slave opts in with <b>Sync Programmers</b>, switchable at any time. On overwrites its programmer; off keeps what it has. CTRL+INCLUDE copies once.</td><td><b>Playback sync</b> per slave (PB sync · multi control · Inhibit · Inhibit, sync on swap). The on-screen Playbacks window is paged <b>separately</b> from the hardware page; wing banks tie to a bank, or to themselves.</td><td>Setup › View Settings › Multi Console.</td></tr>
  <tr><td class="name">Titan</td><td><b>User</b> — each user, remotes too, has a programmer. Same user and handle world mirror each other.</td><td><b>Handle world</b>, with a named option: <b>Follow World Page Change</b> — whether this user's page moves when another console on the world pages. Handles can be locked to every page.</td><td>Per-monitor window layouts, no link setting. Avo+User, Disk › Handle Worlds.</td></tr>
  <tr><td class="name">Onyx</td><td><b>Everything</b> — X-Net mirrors the programmer between consoles.</td><td><b>Wing IDs</b>: each screen's playback view or surface follows the main bank by default, or takes its own ID to show a different bank.</td><td>Menu › Displays, <b>or</b> the Bank Selection pop-up on the view itself.</td></tr>
</table>

<div class="grid3">
  <div class="note key">
    <p><b>Selection: one shared, by default, everywhere.</b> Separating it is always an identity (another user, profile, console) or an explicit sync switch — MagicQ's <i>Sync Programmers</i> is the nearest thing to our <i>local</i>, and it is a rare, deliberate act, not a mode a screen falls into.</p>
  </div>
  <div class="note key">
    <p><b>Page: shared by a group, with a per-surface opt-out.</b> Eos paging groups, Titan's <i>Follow World Page Change</i>, Onyx's Wing IDs and MagicQ's bank ties are all "page with the main, or on your own". That is our page-follow flag, and it is the norm — so the question is how it is <b>named and set</b>, not whether it exists.</p>
  </div>
  <div class="note key">
    <p><b>Where the switch lives: both places.</b> On the view (MA3's title bar, Onyx's Bank Selection pop-up) and in a central display setup (Onyx Displays, MagicQ Multi Console, Eos Setup). Onyx does exactly the pairing proposed here: set it on the view, or from the Displays menu.</p>
  </div>
</div>

<p class="cap" style="font-size:10px; line-height:1.6;">Sources: help.malighting.com grandMA3 2.3 — user_create, user, wvm_settings, executor · etcconnect.com Eos online help — Changing Fader Pages, Wing Paging Groups (snippet), User ID (summary; 404 on fetch), Ion manual p.302 · etcconnect.com Hog HTML — HogNet network §3.3.7 · secure.chamsys.co.uk MagicQ manual — networking_consoles, playback · manual.avolites.com — multi-user-operation, playback-controls, workspace-windows · support.obsidiancontrol.com Onyx manual — X-Net, Wing IDs.</p>
"""

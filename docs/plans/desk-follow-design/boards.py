from survey import SURVEY

MAIN = """
<div class="col" style="gap:8px;">
  <div class="lbl">Desk follow · review · 2026-09-23</div>
  <h1 class="h1">Two follow flags, one idea, and neither where the windows are managed</h1>
  <p class="lede">A window <b>follows the desk</b> twice over: its fixture selection (multi-screen D1/D8) and its busk page (2026-09-16). D18 then hid both chips while following. Reviewing D18 surfaced the question underneath: <b>when does a window's own selection mean anything, and what is following the page for?</b></p>
</div>

<div class="grid2">
  <div class="col">
    <div class="lbl">What we have</div>
    <div class="note">
      <p><b>Selection follow</b> — <span class="mono">desk.follow</span>, per tab, default on. Unlinking snapshots the desk's selection; re-linking adopts the desk's. The way out is ⌘K alone; the chip (drawn only while unlinked, D18) is the way back. The Screens sheet <b>reports</b> the flag and cannot set it.</p>
    </div>
    <div class="note">
      <p><b>Page follow</b> — <span class="mono">busk.pageFollows</span>, per tab, default on, tri-state for <span class="mono">?page=</span> arrival. A tab click on a following window moves <b>the desk's</b> page, so every other following window moves with it. The Screens row's page picker unlinks a window onto a page; its chip relinks.</p>
    </div>
  </div>
  <div class="col">
    <div class="lbl">What the review found</div>
    <div class="note bad">
      <p><b>1 · A local selection is only meaningful where the window can both select and press.</b> Rig focus selects and has no pads; Pads focus presses and has no tiles. Local there is a selection nothing acts on, or presses onto heads the operator can't see or change.</p>
    </div>
    <div class="note bad">
      <p><b>2 · A touch-only window cannot leave following</b>, and the Screens sheet — the one place built to set another window up — only reports it.</p>
    </div>
    <div class="note warn">
      <p><b>3 · Following the page is a paging group, and that part is right.</b> The desk page's readers are following windows and the <span class="mono">BuskPageSet</span> LEDs — it is what lets the X-Touch page the screens. Every surveyed desk has the same thing (Eos paging groups, Titan's <i>Follow World Page Change</i>, Onyx Wing IDs). What is wrong is the <b>setting</b>: the Screens row can take a window off the desk's page but cannot put it back, and "follows the desk" names the mechanism, not the job.</p>
    </div>
  </div>
</div>

<div class="col">
  <div class="lbl">Proposed — every call answered 2026-09-23, see Model</div>
  <div class="grid2">
    <div class="note key">
      <p><span class="tag prop">A</span> <b>Selection: follow stays the default, and local is offered only where a window can select and act</b> — busk Split and the Programmer. Rig and Pads focus always follow; entering either relinks the window and says so.</p>
    </div>
    <div class="note key">
      <p><span class="tag prop">B</span> <b>Follow is set where windows are managed</b>: a <i>Selection · Desk | This window</i> segment on the Screens row, beside ⌘K and the chip. One new command, <span class="mono">windows.follow</span>, so one screen can set another up.</p>
    </div>
    <div class="note key">
      <p><span class="tag prop">C</span> <b>Pages: keep the desk page, and say what it is — a paging group.</b> A busk window is <i>Paged with the desk</i> (with the X-Touch and every other window so paged) or on its <i>Own page</i>. The Screens row gets that segment, settable both ways; today it can only unlink. Per-window pages everywhere was drafted first and dropped: every surveyed desk shares a page by group by default and lets a surface opt out.</p>
    </div>
    <div class="note">
      <p><span class="tag call">Not now</span> <b>Named selections</b> — Eos's model, where devices sharing a user share a selection. It is how a <i>second operator</i> would be done, and this desk has one. Recorded as the path if that changes; nothing proposed here blocks it.</p>
    </div>
  </div>
</div>

<p class="cap">Boards: <b>Survey</b> (how other desks do it) · <b>Selection</b> (where local means something) · <b>Pages</b> (what following the page is for) · <b>Screens</b> (the controls) · <b>Model</b> (wire, code, sessions, the calls).</p>
"""

SELECTION = """
<div class="col" style="gap:8px;">
  <div class="lbl">Selection</div>
  <h1 class="h1">A window's own selection means something only where it can select and act on it</h1>
  <p class="lede">D18 was written from one case — Rig focus, where a local selection applies to nothing. It generalises. Walk every surface that can hold a selection and ask two questions: <b>can this window change the selection?</b> and <b>can it do anything with it?</b> Local is useful only where both are yes.</p>
</div>

<table>
  <tr><th style="width:150px;">Surface</th><th style="width:170px;">Selects here</th><th style="width:200px;">Acts here</th><th>What a local selection would be</th><th style="width:210px;">Proposed</th></tr>
  <tr><td class="name">Busk · Split</td><td class="yes">Yes — the rig tiles</td><td class="yes">Yes — pads, the sheet's Colour · Spread, the verbs</td><td>A self-contained second station: select and press here, the desk's selection untouched.</td><td><span class="tag prop">Follow default · local allowed</span></td></tr>
  <tr><td class="name">Busk · Pads</td><td class="no">No — summary only</td><td class="yes">Yes — pads, Spread · Locate · Highlight</td><td><b>A trap.</b> Presses land on a frozen snapshot the operator can't see on this screen and can't change from it.</td><td><span class="tag prop">Always follows</span></td></tr>
  <tr><td class="name">Busk · Rig</td><td class="yes">Yes — every row</td><td class="meh">Barely — the band's verbs, and the sheet's Colour · Spread if it is open</td><td><b>D18's case.</b> Rig focus exists to be the selector for another screen's pads; locally it selects for nobody. The sheet is the one way it could act; it follows anyway (decision 1).</td><td><span class="tag prop">Always follows</span></td></tr>
  <tr><td class="name">Programmer</td><td class="yes">Yes — the list and marquee</td><td class="yes">Yes — values, templates, Record</td><td>Build or record on a different selection while busk screens select for the desk. Record already scopes on what this window shows.</td><td><span class="tag prop">Follow default · local allowed</span></td></tr>
  <tr><td class="name">Show · Prompt Book</td><td class="no">No</td><td class="no">No selection gestures</td><td>Nothing — they never read it.</td><td><span class="tag today">No control drawn</span></td></tr>
  <tr><td class="name">Fixtures · Groups lists</td><td class="yes">Yes</td><td class="yes">Record scope</td><td>Already always local: they never bridge (multi-screen D1).</td><td><span class="tag today">Unchanged</span></td></tr>
</table>

<div class="grid2">
  <div class="col">
    <div class="lbl">The rule</div>
    <div class="note key">
      <p><b>A window follows whenever it cannot both select and act.</b> On the busk view that is a fact about focus, so the effective flag is <span class="mono">follow || focus ∈ {rig, pads}</span>. On the Programmer it is always free.</p>
      <p><b>Entering Rig or Pads relinks the window</b> — the local copy is dropped, not held — and a toast on that window says so. A MIDI <span class="mono">BuskFocusSet</span> or a Screens row can cause it from elsewhere, which is why it is said out loud rather than silent.</p>
    </div>
    <div class="toast">
      <div><b>Screen 2 follows the desk selection again</b><div class="d">Pads focus presses onto the desk's selection. Your own was dropped.</div></div>
    </div>
  </div>
  <div class="col">
    <div class="lbl">What that does to D18 — the badge and the chip</div>
    <div class="note">
      <p><b>D18 is revisited: a window always says which selection it is on</b> (decision 8). While following, a small <b>link badge</b> sits beside the family pill — glyph only, hover <i>Following the desk selection</i>. While local, the dashed <i>Targets: This window</i> chip takes its place and presses back. Same rule as the page's badge.</p>
      <p>Where: the rig row in Split and Rig, the <b>pad row in Pads</b> (always following now, so always the badge), the compact strip, and the Programmer's row C. Both rows are tight: it is a glyph at every width, measured into their ladders.</p>
      <p><b>The survey agrees on the shape</b>: one shared selection by default everywhere, and leaving it a deliberate act — MagicQ's <i>Sync Programmers</i> switch is the nearest thing to our local, and nobody lets a screen fall into it as a side effect of its layout.</p>
      <p><b>The way in gets a second and third door</b> (the Screens row, below; the chip keeps being the way back). ⌘K's <i>Stop following…</i> is withheld in Rig and Pads, where it would be refused anyway.</p>
    </div>
    <div class="note warn">
      <p><b>What this costs.</b> An operator who unlinked Screen 2 in Split, glanced at Pads and came back has lost their own selection. Holding it dormant instead was declined (decision 2) — more state, and a chip explaining a selection nothing uses.</p>
    </div>
  </div>
</div>
"""

PAGES = """
<div class="col" style="gap:8px;">
  <div class="lbl">Pages</div>
  <h1 class="h1">What is following the page for? Paging several surfaces together</h1>
  <p class="lede">The desk's showing page (<span class="mono">state/BuskPageState.kt</span>) is read by <b>windows that follow it</b> and by the <b><span class="mono">BuskPageSet</span> LEDs</b>. No binding presses "the third pad on the showing page" — <span class="mono">PressPad</span> names a pad by uuid — so the page is not state the rig depends on. It is a <b>paging group</b>: the X-Touch's Next · Prev · Set and every window in the group page together, and a window can leave the group to show a page of its own.</p>
</div>

<div class="grid2">
  <div class="col">
    <div class="row" style="align-items:center;"><span class="tag today">Today</span><span class="h3">The mechanism is the consoles' — the setting is half-built</span></div>
    <div class="flow">
      <div class="box">X-Touch <b>Next · Prev · Set</b></div><span class="arrow">→</span>
      <div class="box desk"><b>Desk page</b></div><span class="arrow">→</span>
      <div class="box win"><b>Paged-with windows</b> + LEDs</div>
    </div>
    <div class="flow">
      <div class="box">Tab click on a paged-with window</div><span class="arrow">→</span>
      <div class="box desk"><b>Desk page</b></div><span class="arrow">→</span>
      <div class="box win">…the whole group pages</div>
    </div>
    <div class="note warn">
      <p><b>Leaving the group</b>: the Screens row's page picker, a <span class="mono">?page=</span> arrival, a click that never reached the desk. <b>Rejoining</b>: only the page chip, on that window. A touch-only immersive screen put on its own page from another screen stays there until someone walks over to it.</p>
      <p><b>The words</b> — <i>follows the desk</i> / <i>own page</i> on the row, <i>Page: This window</i> on the chip — name how it works, not what it does: nothing tells the operator that a tab click here also pages Screen 2.</p>
    </div>
  </div>
  <div class="col">
    <div class="row" style="align-items:center;"><span class="tag prop">Proposed</span><span class="h3">Keep the group; name it, and set it both ways</span></div>
    <div class="note key">
      <p><b>Paged with the desk · Own page</b> — Titan's and Onyx's shape, in our words. A segment on the Screens row beside the page picker; picking a page on a paged-with row pages the <b>group</b> (as a tab click there does), picking one on an own-page row pages that window.</p>
      <p><b>Rejoining from the row</b>: the row writes <span class="mono">windows.viewOptions {pageFollows: true}</span>. Windows already announce <span class="mono">pageFollows</span>; they only need to apply it — a client change, no new frame.</p>
      <p><b>The chip keeps D18's rule</b> — drawn only on an own-page window — and reads <i>Page: Own</i>, pressing back to <i>Paged with the desk</i>. On narrow rows the words shorten: <i>With desk · Own</i> on the segment, <i>Own</i> on the chip.</p>
    </div>
    <div class="col" style="gap:8px;">
      <div class="lbl">The link badge — decision 4</div>
      <div class="band"><span class="rl">PADS</span><span class="seg"><span class="on">Colour</span><span>Position</span><span>Beam</span></span><span class="badge" title="Paged with the desk">⛓</span><span class="gap"></span><span class="seg"><span>Split</span><span class="on">Pads</span><span>Rig</span></span></div>
      <div class="band"><span class="rl">PADS</span><span class="seg"><span class="on">Colour</span><span>Position</span><span>Beam</span></span><span class="badge" title="Paged with the desk, and with Screen 2 — a tab click pages it too">⛓ Screen 2</span><span class="gap"></span><span class="seg"><span>Split</span><span class="on">Pads</span><span>Rig</span></span></div>
      <div class="band"><span class="rl">PADS</span><span class="seg"><span class="on">Colour</span><span>Position</span><span>Beam</span></span><span class="pill local" style="height:24px;">Page: Own</span><span class="gap"></span><span class="seg"><span>Split</span><span class="on">Pads</span><span>Rig</span></span></div>
      <p class="cap"><b>Always drawn while the window is paged with the desk</b> — a small link badge beside the tabs, so a linked window is never silent about it. It names any other open window paged with it (<i>⛓ Screen 2</i>), folding to the glyph alone on the row's ladder; the hover says a tab click pages them too. <b>On its own page</b> the badge gives way to the <i>Page: Own</i> chip, which presses back. The page is always marked one way or the other, which revisits D18 for the page: a small badge, not the old full chip. The glyph is a character here; the app uses lucide's.</p>
    </div>
    <div class="note">
      <p><b>Drafted and dropped: per-window pages everywhere</b> (no desk page, hardware bindings naming a window). It deleted the arrival tri-state and the page chip, but every surveyed desk shares a page by group by default, and it would have lost "the X-Touch pages both screens" for a desk that wants it. Decision 3 kept the group.</p>
    </div>
  </div>
</div>

<table>
  <tr><th style="width:360px;">Scenario</th><th>Today</th><th>Proposed</th></tr>
  <tr><td class="name">One busk screen, X-Touch pages it</td><td class="yes">Paged with the desk</td><td class="yes">Unchanged</td></tr>
  <tr><td class="name">Two screens paged together by the X-Touch</td><td class="yes">Both paged with the desk</td><td class="yes">Unchanged — the paging group</td></tr>
  <tr><td class="name">Colour page on Screen 1, position page on Screen 2 <span class="badge">2026-09-16</span></td><td class="meh">Put one on its own page — only from that screen's chip to undo</td><td class="yes">One segment on either screen's row, both ways</td></tr>
  <tr><td class="name">Screen 2 in Rig focus</td><td class="meh">Pages with the group, invisibly</td><td class="yes">Same — the page folds away, and so does the question</td></tr>
  <tr><td class="name">A tab click on a window paged with another</td><td class="meh">Pages both, silently</td><td class="yes">Pages both, and the link badge names the other window</td></tr>
  <tr><td class="name">Reload · a new window from a link</td><td class="yes">Tri-state arrival rules</td><td class="yes">Unchanged</td></tr>
</table>
"""

SCREENS = """
<div class="col" style="gap:8px;">
  <div class="lbl">The controls</div>
  <h1 class="h1">Follow is set where windows are managed</h1>
  <p class="lede">The Screens sheet already sets another window's view, focus, sheet, page and chrome. Selection follow is the one fact it only <b>reports</b>, which is what leaves a touch-only screen stranded. Proposed: a <b>Selection</b> segment on every Busk and Programmer row, written through a new <span class="mono">windows.follow</span> command.</p>
</div>

<div class="row" style="gap:32px; align-items:flex-start;">
  <div class="panel" style="width:640px; flex:0 0 auto;">
    <div class="hd">Screens <span style="flex:1"></span><span class="badge">3 windows</span></div>
    <div class="bd">
      <div class="wrow">
        <div class="top"><span class="field" style="flex:1;">Screen 1</span><span class="badge">this window</span><span class="field">Busk ▾</span><button class="btn" type="button">Full screen</button></div>
        <div class="opts">
          <span class="opt">Focus <span class="seg"><span class="on">Split</span><span>Pads</span><span>Rig</span></span></span>
          <span class="opt">Sheet <span class="seg"><span class="on">Speed</span><span>Colour</span><span>Spread</span><span>Show</span><span>—</span></span></span>
          <span class="opt">Page <span class="seg"><span class="on">With the desk</span><span>Own</span></span> <span class="field" style="height:26px;font-size:12px;">Colour ▾</span></span>
          <span class="opt">Selection <span class="seg"><span class="on">Desk</span><span>This window</span></span></span>
        </div>
      </div>
      <div class="wrow">
        <div class="top"><span class="field" style="flex:1;">Screen 2</span><span class="field">Busk ▾</span><button class="btn" type="button">Full screen</button></div>
        <div class="opts">
          <span class="opt">Focus <span class="seg"><span>Split</span><span class="on">Pads</span><span>Rig</span></span></span>
          <span class="opt">Page <span class="seg"><span>With the desk</span><span class="on">Own</span></span> <span class="field" style="height:26px;font-size:12px;">Position ▾</span></span>
          <span class="opt">Selection <span class="seg dis"><span class="on">Desk</span><span>This window</span></span> <span>Pads focus follows</span></span>
        </div>
      </div>
      <div class="wrow">
        <div class="top"><span class="field" style="flex:1;">iPad</span><span class="field">Programmer ▾</span></div>
        <div class="opts">
          <span class="opt">Chrome <span class="seg"><span class="on">App</span><span>Immersive</span></span></span>
          <span class="opt">Selection <span class="seg"><span>Desk</span><span class="on">This window</span></span></span>
        </div>
      </div>
      <p class="cap"><b>Page</b>: the segment replaces the <i>follows the desk / own page</i> caption and works both ways; the picker pages the group on a <i>With the desk</i> row and that window on an <i>Own</i> row. <b>Selection</b>: disabled with its reason in Rig and Pads, never hidden, so the row reads the same shape in every focus.</p>
    </div>
  </div>

  <div class="col" style="flex:1; gap:20px;">
    <div class="col" style="gap:8px;">
      <div class="lbl">The selection mark, in Split — decision 8</div>
      <div class="band"><span class="rl">RIG</span><span class="btn">Cells: All ▾</span><span class="btn">‹</span><span class="btn">›</span><span class="btn">Spread</span><span class="btn">Locate</span><span class="btn">Clear</span><span class="pill" style="height:24px;">Colour</span><span class="badge" title="Following the desk selection">⛓</span><span class="gap"></span><span class="seg"><span class="on">Split</span><span>Pads</span><span>Rig</span></span></div>
      <div class="band"><span class="rl">RIG</span><span class="btn">Cells: All ▾</span><span class="btn">‹</span><span class="btn">›</span><span class="btn">Spread</span><span class="btn">Locate</span><span class="btn">Clear</span><span class="pill local">Targets: <span>This window</span></span><span class="gap"></span><span class="seg"><span class="on">Split</span><span>Pads</span><span>Rig</span></span></div>
      <p class="cap">Top: following — the link badge beside the family pill. Bottom: local — the dashed chip, whose press follows the desk again. In Pads and Rig only the badge is ever drawn, because those always follow.</p>
    </div>
    <div class="col" style="gap:8px;">
      <div class="lbl">⌘K</div>
      <div class="cmd">
        <div class="q">follow</div>
        <div class="g lbl">Screens</div>
        <div class="it hi">Stop following the desk selection in this window <span class="det">Split</span></div>
        <div class="it">Screen 2 · follow the desk selection <span class="det">own selection</span></div>
        <div class="it">iPad · follow the desk selection <span class="det">own selection</span></div>
      </div>
      <p class="cap">The per-window arm is new and mirrors <i>Show &lt;view&gt; on &lt;window&gt;</i>. Paging gets the same pair per window: <i>Screen 2 · page with the desk</i> / <i>· own page</i>.</p>
    </div>
    <div class="col" style="gap:8px;">
      <div class="lbl">MIDI — later</div>
      <p class="cap"><span class="mono">SelectionFollowSet(windowName, on)</span>, <span class="mono">BuskFocusSet</span>'s twin. Not proposed now: a hardware door to a second station is a two-operator feature.</p>
    </div>
  </div>
</div>
"""

MODEL = """
<div class="col" style="gap:8px;">
  <div class="lbl">Model</div>
  <h1 class="h1">What changes, and what was decided</h1>
  <p class="lede">Small on the wire: <b>one new windows command</b>. Everything else is client — a rule, two Screens segments, ⌘K arms and a window applying a view option it already announces.</p>
</div>

<div class="grid3">
  <div class="col">
    <div class="lbl">Wire · lighting7</div>
    <div class="note">
      <p><b><span class="mono">windows.follow {targetId, on}</span></b> — a fifth windows command, rebroadcast like <span class="mono">show</span> and <span class="mono">viewOptions</span>; the named window unlinks or relinks itself and re-announces. Not a view option: follow belongs to the window, not to a view, and a Programmer row has no busk options to carry it.</p>
    </div>
    <div class="note">
      <p><b>Nothing for pages.</b> <span class="mono">pageFollows</span> already rides <span class="mono">viewOptions</span> both ways; the registry never learns the vocabulary.</p>
    </div>
    <div class="note">
      <p><span class="tag call">Later</span> <span class="mono">SelectionFollowSet(windowName, on)</span> as a MIDI target — <span class="mono">BuskFocusSet</span>'s twin (call 6).</p>
    </div>
  </div>
  <div class="col">
    <div class="lbl">Client · lighting-react</div>
    <div class="note">
      <p><b><span class="mono">deskFollow.ts</span></b> gains the effective rule; <span class="mono">BuskingView</span> relinks on entering Rig or Pads and toasts; <span class="mono">useWindowsBridge</span> handles <span class="mono">windows.follow</span>.</p>
    </div>
    <div class="note">
      <p><b><span class="mono">applyBuskViewOptions</span></b> applies <span class="mono">pageFollows</span> — <i>true</i> relinks the page, <i>false</i> keeps the window's current page as its own.</p>
    </div>
    <div class="note">
      <p><b>Screens row</b>: the Selection and Page segments. <b>⌘K</b>: per-window follow and paging arms, withheld where refused. <b>Marks</b>: a link badge while linked, for the page (by the tabs) and the selection (by the family pill); <i>Page: Own</i> and <i>Targets: This window</i> while not.</p>
    </div>
    <div class="note">
      <p><span class="tag goes">Goes</span> <i>Stop following…</i> in Rig and Pads; the <i>follows the desk / own selection / own page</i> captions; any desk chip on the pad row.</p>
    </div>
  </div>
  <div class="col">
    <div class="lbl">Sessions</div>
    <div class="note">
      <p><b>1 · lighting7</b> — <span class="mono">windows.follow</span>, its test, <span class="mono">websocket-engineering.md</span>. Small.</p>
    </div>
    <div class="note">
      <p><b>2 · lighting-react</b> — the rule and the relink toast; the command's handler; both Screens segments; <span class="mono">pageFollows</span> applied; ⌘K; the chip's words; CLAUDE.md §The busk layout and §One selection, two shapes.</p>
    </div>
    <p class="cap">About one busk-chrome session of client work. Nothing to migrate: both flags stay per-tab <span class="mono">sessionStorage</span> with the same keys and defaults.</p>
  </div>
</div>

<div class="col">
  <div class="lbl">Decisions — answered by Chris, 2026-09-23</div>
  <table>
    <tr><th style="width:40px;">#</th><th style="width:430px;">Question</th><th style="width:260px;">Decided</th><th>Why</th></tr>
    <tr><td class="name">1</td><td>Rig focus with the sheet's Colour · Spread open can act. Always follow anyway?</td><td class="yes">Always follow</td><td>Rig focus exists to be the selector for another screen. A second station that selects and colours without pads is what Split is for.</td></tr>
    <tr><td class="name">2</td><td>Entering Rig or Pads while local: drop the local selection, or hold it until Split?</td><td class="yes">Drop it, and toast</td><td>A held selection needs a chip, in a focus that has none, to explain a selection nothing is using.</td></tr>
    <tr><td class="name">3</td><td>Keep the desk page as a paging group, or make every window's page its own?</td><td class="yes">Keep the paging group</td><td>Every surveyed desk pages by group with a per-surface opt-out, and MIDI surfaces paging several screens together is the reason it exists.</td></tr>
    <tr><td class="name">4</td><td>Mark on screen that a window is paged with the desk?</td><td class="yes">Yes — a link badge by the tabs, always while linked</td><td>A linked window always says so, even if only with a glyph, and names any other window paged with it. On an own page the <i>Page: Own</i> chip takes its place. Revisits D18 for the page: a small badge, not the old chip.</td></tr>
    <tr><td class="name">5</td><td>Named selections for a second operator (Eos User ID, MA3 profiles)?</td><td class="yes">Later — a follow-up</td><td><i>Desk | This window</i> becomes <i>Desk | B | This window</i> without undoing anything here.</td></tr>
    <tr><td class="name">6</td><td>A MIDI <span class="mono">SelectionFollowSet</span> target?</td><td class="yes">Later — a follow-up</td><td>The Screens sheet from another window covers the touch-only case.</td></tr>
    <tr><td class="name">7</td><td>The page words?</td><td class="yes">Paged with the desk · Own page; chip <i>Page: Own</i></td><td>Kept, with a short form on narrow rows: <i>With desk · Own</i>, and the chip down to <i>Own</i> on the pad row's folds — measured into its ladder like every other word there.</td></tr>
    <tr><td class="name">8</td><td>Mark the selection while following, as the page is?</td><td class="yes">Yes — a link badge by the family pill</td><td>A window always says which selection it is on. Glyph only, on the rig row, the pad row, the compact strip and row C; the dashed chip replaces it while local. Revisits D18 for the selection as decision 4 does for the page.</td></tr>
  </table>
</div>
"""

BOARDS = [
    ("Main.dc.html", "Overview · what we have, what's wrong, what's proposed", 1440, 1120, 0, 0, MAIN),
    ("Survey.dc.html", "Survey · how other desks share selection and pages", 1440, 1300, 1520, 0, SURVEY),
    ("Selection.dc.html", "Selection · where a window's own selection means something", 1440, 1320, 0, 1420, SELECTION),
    ("Pages.dc.html", "Pages · what following the page is for", 1440, 1500, 1520, 1420, PAGES),
    ("Screens.dc.html", "Screens · the controls", 1440, 960, 0, 3040, SCREENS),
    ("Model.dc.html", "Model · wire, code, sessions, decisions", 1440, 1400, 1520, 3040, MODEL),
]

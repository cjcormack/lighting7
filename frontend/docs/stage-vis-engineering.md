# Stage visualisation: sources, appearance and the renderer

How the stage surfaces decide **what a fixture looks like**. Two independent seams:

- a **channel source** — which layer of the lighting cascade the numbers come from;
- a **fixture appearance** — how a fixture's colour source turns those numbers into a colour and a
  level.

And, since the stage-view plan's session 0, how the 3D view keeps within a browser's memory and
survives losing its graphics context — §"The 3D renderer"; since session 1, its cameras, the
window's viewpoint and the Positions panel — §"Cameras and viewpoints"; since session 4, how a
window draws a viewpoint offscreen for Claude — §"Rendering for `render_view`"; since session 5,
editing on the Plan, Front and Side sections, which retired the SVG plot — §"Editing on the
sections"; and since session 6, the fixture bodies, their cells and beams that leave the aperture —
§"Fixture bodies" at the end.

## The vis source

*Scenery follows the source too — §"Scenery that moves with the show (session 8)".*

The desk transmits one merged DMX frame, and until this landed every stage surface drew exactly
that. In **Blind** that is the wrong picture: the programmer is gated out of the merge, so the stage
keeps showing the pre-blind look while the operator builds a cue they cannot see. The programmer
sheet already solved this at cell granularity (`fixtures-list/ownership.ts::applyStagedValue`); the
stage did not.

`hooks/useVisSource.ts` holds the operator's choice of four:

| Source | What it draws |
|---|---|
| `output` | Final merged DMX — what the desk is transmitting. |
| `outputProgrammer` | Output with the programmer laid over it. Identical to `output` unless Blind is on. |
| `programmer` | Only what the programmer holds; every other channel reads 0. |
| `nextGo` | The look the next GO would produce, over live output. |

`programmer` is literal on purpose: an empty programmer shows an empty stage, and a fixture given
intensity but no position sits at pan/tilt 0 rather than borrowing a position from the wire.

It is a **module store read through `useSyncExternalStore`**, not `usePersistentState`. Two surfaces
read it — the Stage route's View menu and the globally-mounted Positions panel — and
`usePersistentState` reads its key once in a `useState` initialiser and never listens for changes,
so two components sharing a key drift apart the moment one writes.

**It is the window's, since stage-view plan session 3** — `sessionStorage` (`stage.source`), where it
was one `localStorage` value per profile (`stageVisSource`). A hall screen on Next GO and a desk
screen on Output are two facts, so the source rides `windows.viewOptions` as `source` beside
`viewpoint` under the Stage view (`stageViewOptions` in `lib/stageViewpoint.ts`), and the Screens
sheet's Stage row draws a **Source** segment from `lib/windowViews.ts`'s `STAGE_SOURCE_OPTION` —
*Output · Next GO*, the two `Screens.dc.html` §1 draws; a window on either programmer source shows
neither lit, and the View menu still offers all four. The sheet stays generic: the segment is the
descriptor's `enum` kind. A Screens row's *Copy link* carries `source=`, which the Stage route applies
on arrival and strips with `viewpoint=` (`consumeLaunchStageOptions`); `applyStageViewOptions` and
that consumer ignore a value outside the four. A window with nothing of its own starts from the
legacy profile value, so a desk keeps the source it had across the change.

The stored value is narrowed through `isVisSource` on read. A value written by a later build must
not reach code that has no case for it.

### The fourth source: Next GO

Previewing the look the next GO would produce is the one source that isn't a merge of what the desk
already holds — it needs a *hypothetical* cue set composed. The backend does that:
`POST /project/{id}/cue-stacks/{stackId}/preview` returns the channel values a cue would produce,
run through the real `CueAssignmentResolver`, and "next" is a server-owned concept broadcast on
`cueRunStateChanged`. See lighting7 `docs/cue-stacks-engineering.md` §"Preview compose" and
§"Standby".

`hooks/useNextGoPreview.ts` is the client half. Four decisions in it:

**It is pushed, not derived.** `createPushChannelSource` (`api/channelSource.ts`) is a channel
source whose map is *handed to it*, so it is deliberately **not** a `DerivedChannelSource` — there
is no upstream to subscribe to and `refresh`/`dispose` would mean nothing. It carries `holds`
because that is all `createOverlayChannelSource` structurally needs, and it reuses the same
`createFanOut` as the programmer source, so a channel dropping *out* of a look notifies exactly
like one changing value.

**It overlays the wire, and that is not cosmetic.** The endpoint reports only the channels the cue
asserts; everything else is *absent*, not 0. Reading absent as 0 would black out every fixture the
next cue doesn't touch. So `nextGo` resolves to
`createOverlayChannelSource(outputChannelSource, preview)`, exactly as `outputProgrammer` does, and
"nothing to preview" is expressed by emptying the source rather than by a special case.

**"Which cue" is read out of the RTK cache, not off the socket.** The target is the *current*
project (the route sits behind `withCurrentProject` and 409s otherwise), the project playhead's
stack (`projectProgramState.activeStackId` — the stack the Runner's GO fires), and that stack's
`nextCueId` from `projectCueStackList`. The global `cueRunStateChanged` subscriber in
`store/cueStacks.ts` already patches `nextCueId` into that cache, so reading it here *is* following
the run state: no second socket listener, and no re-request on a standby-only or connect-snapshot
frame that left the next cue where it was. The request names the cue id explicitly rather than
sending `null`, so the response can't answer a different cue than the effect keyed on; a superseded
response is dropped by an `ignore` flag in the effect cleanup.

**The request is a query, not a mutation, despite being a POST.** Two things follow from that, and
both are load-bearing. Several stage surfaces can be mounted at once — the Stage route's canvas and
the Positions panel Layout hangs under the header — and RTK Query collapses their identical
args into one request and one cache entry, so two open surfaces cost one request and a GO produces
one POST rather than one per surface.

The overview panel used to render whether or not it was expanded, and this paragraph used to say a
collapsed panel therefore cost nothing. It no longer renders collapsed: `CollapsiblePanel` unmounts
its body once the collapse animation finishes, taking the panel's `StageChannelSourceProvider` and
this query's subscription with it. Nothing above changes — a collapsed panel now costs nothing
because it isn't there — but reopening is a remount, so it re-subscribes, and past RTK Query's
one-minute retention of an unsubscribed entry it refetches rather than reading cache. And every subscriber sees the
same `isError`, which is what lets the status line report the *request* rather than the target.
`refetchOnMountOrArgChange` is set, so re-selecting the source recomposes instead of replaying a
cached look. `cueId` is a required arg rather than "null means the effective next", because under a
query the arg *is* the cache key: a key meaning "whatever the server currently thinks" would serve
one cue's look under another cue's identity. Being a query also puts it outside
`errorToastMiddleware` and `saveStatusSlice` entirely, both of which only see mutations.

Four limits shape what the source can promise. The first two the View menu states outright,
because an operator would otherwise read them as bugs; the last two it does not:

* **Layer 4 only.** A cue whose look is carried by a cue-band effect or a timed preset previews as
  little or nothing. The menu hint says "cue values only".
* **No show running, no preview.** With no active stack — or at the end of a non-looping stack,
  where the backend answers **400** — the source empties and the stage shows plain output. That is
  indistinguishable from a working preview unless it is stated, so `useNextGoStatus` puts "No cue on
  deck — showing output." under the menu entry, and "Preview unavailable — showing output." when
  the request itself fails. The status is read off the shared query's `isError`/`isSuccess` rather
  than off the target, so it can never name a cue the stage isn't actually drawing — there is no
  toast to fall back on, since a 400 here is an ordinary state and queries don't reach
  `errorToastMiddleware` anyway.
* **Another stack's GO doesn't refresh it.** The preview composes against every *other* stack's
  live rows, so a GO elsewhere changes the answer — but the refresh is keyed on the previewed
  stack's own `nextCueId`, which such a GO leaves alone. Within the previewed stack it is exact.
  Deliberate: the alternative is re-requesting on every run-state frame from every stack.
* **Editing the cue on deck doesn't refresh it either.** Same cause: the request effect is keyed on
  `projectId`/`stackId`/`cueId` alone, so changing the *contents* of the previewed cue — a level, a
  layer, a colour — leaves the previously composed look on the stage until the desk moves the
  next cue on. `projectCueStackList` carries no revision that moves when a cue assignment changes,
  so fixing this needs one; a partial key off `presetCount` would refresh for some
  edits and silently not for others, which is worse than the known limit. The operator's handle in
  the meantime is to reselect the source, which `refetchOnMountOrArgChange` makes recompose.

### Injection is at channel level

`api/channelSource.ts` defines `ChannelSource` — `get`, `getByKey`, `subscribeToChannel` — and
`hooks/useChannelSource.tsx` supplies one by context, defaulting to the wire. Every reader takes a
source; nothing but the source knows about layers.

Channel level rather than property level, because `FixtureModel`'s per-frame beam director reads 17
channels by key (pan, tilt, their fine axes, zoom, focus, iris, frost, two gobo wheels, gobo
rotation with its fine byte and its wheel's function channel, prism, prism rotation, and two
macros), and a head with framing shutters eight more (each blade's insertion and rotation). Substituting at property level would mean touching each of those
reads and knowing which descriptor backs each; substituting at channel level means they all work
unchanged.

`ChannelSource` deliberately has **no `getAll()`**. A composing source would have to allocate a
merged map per fixture per frame to answer it. `getByKey` keeps every source O(1) and
allocation-free, which is what that frame loop requires.

**The provider wraps only the canvases** — the `Stage3D` element in `routes/Stage.tsx`
and the Positions panel's rows and Plan tab (`components/positions/`). Not `<main>`: the docked
`StageFixtureControlPanel` renders `FixtureDetailView`, a live editing surface that must keep
reading and writing the real wire whatever the stage is previewing. The same reasoning keeps
`useVirtualDimmer` on the default source — all of its consumers are editing controls.

R3F's `<Canvas>` is a separate reconciler root, but context still crosses it: `@react-three/fiber`
v9 wraps the Canvas root in an `its-fine` context bridge (`useBridge()`). Nothing else in this app
depends on that, so it is worth a smoke test after any R3F upgrade.

### Resolving the programmer to channels

`lib/programmerChannels.ts` turns `programmer.state` into a channel map. Two things to know:

**`ProgrammerState.channels` is the sideband, not the programmer's output.** It carries only what
the property model can't lift: raw `updateChannel` writes on unbacked channels, raw pan/tilt axis
writes, and unpark hand-downs. A dimmer or colour written through `programmer.set` lands in a
*property* entry and never appears there. Reading the picture off `channels` alone yields an almost
empty stage.

So the real work is `resolveEntryChannels`, which mirrors lighting7's `PropertyChannelWriter`:
level → one channel; colour → r/g/b plus white/amber/uv **only where the descriptor has that
channel** (the client-side stand-in for the backend's `WithWhite` / `WithAmber` / `WithUv` trait
gate); position → coarse pan/tilt only, because the backend writes no fine channels for a position
assignment either. A shape mismatch resolves to nothing rather than a guess.

The descriptor index covers **element keys as well as fixture keys**: a multi-head fixture's entries
are keyed by element key, so an index built from `Fixture.properties` alone would drop every
per-head write.

**Sideband-last is a documented approximation.** The backend arbitrates a property entry against a
sideband slot on the same channel by write sequence (`ProgrammerStore.Slot.seq`), which never
reaches the client. A deliberate property write already absorbs the sideband beneath it, so a slot
surviving under one is not a state the backend normally holds; where the two could differ, favouring
the sideband keeps raw writes visible, which is what the sideband is for.

**`holds(key)` is not `getByKey(key) !== 0`.** The programmer legitimately holds a dimmer *at* 0, and
treating that as "no opinion" would let the wire value show through in exactly the case Blind most
needs to preview. The overlay source keys off `holds`.

That has a consequence for change notification: `notifyChanged` compares **presence as well as
value**, not value alone. A channel going from absent to held-at-0 is no change to the
programmer-only source (0 either way) but a real one to the overlay (wire value → 0). Comparing
values with absent-treated-as-0 would see nothing and wake nobody, so a cue holding a dimmer at full
would keep painting full after the operator zeroed it in a blind programmer.

Latency: `programmer.state` arrives on the backend's 100 ms provenance debounce, but
`programmerWsApi`'s `applyLocalEntry` echoes our *own* writes into `entries` immediately, so an
operator's fader drag previews at input rate.

## Fixture appearance

`components/fixtures/fixtureAppearance.tsx` answers "what colour is this fixture, and how lit", for
any of five colour sources: an RGB colour property, a colour wheel, a gel, a bare dimmer, or a
multi-element pixel bar (plus a placeholder for a patch with no fixture).

`FixtureAppearance` carries the hue at **full** brightness and the **linear** 0..1 level. Both raw,
because each medium curves them differently:

- the DOM marker folds `perceptualBrightness` into a box-shadow and an opacity;
- the 3D scene splits them — `level^0.6` on the lens (`lensColour`), **linear** on the cone and
  pool, because those strengths double as the `LIGHT_OFF_OPACITY` beam cull.

This is why `useColourAppearance` is not the shared piece: it returns only a pre-baked CSS string and
drops the level. It stays as it is for the swatch callers that only want the string.

### Why a render prop

Each colour source needs a *different* set of value hooks — `useColourValue` wants a
`ColourPropertyDescriptor`, `useGroupColourValues` subscribes to a variable-length channel list, a
gel fixture needs neither — so they cannot collapse behind one hook without breaking hook order.
`FixtureAppearanceSource` dispatches to a leaf component per source, each with a fixed hook set, and
hands the result to `children`. That constraint is why the SVG plot (retired in session 5) went
without live colour for so long, and the render prop is the way around it.

Consumers — **four mounting readers**: the Positions panel's `UnitChip` (DOM — one leaf per chip,
the swatch and the level off one subscription; it replaced `StageMarker` with the overview it
drew);
since the busk view's rig band, `RigTile`'s live bar and pips (`components/busking/RigTile.tsx`),
one leaf per fixture tile, which reads a cell's colour off `segments` by the element's position in
the patch's cell list; since the side sheet, `SideSheetFold`'s selection colour dot
(`components/busking/SideSheetFold.tsx`), one leaf for the first selected head; and the Colour
tab's **hidden leaf per selected head** (`components/busking/ColourSheet.tsx`), which renders
nothing and only reports into `lib/liveAppearance.ts` so *Pick* can answer in Pads focus with the
rig tiles folded away. The cues' `MiniStage` borrows only the dispatch's default colour and is not
a reader. `FixtureModel` keeps its own **imperative** mirror of the same dispatch on purpose — R3F is a separate reconciler root and
store-driven re-renders drop beat-rate changes, so the 3D path writes straight to the scene from the
channel callback. Three copies of the shape, two of the code; changing the dispatch means changing
`fixtureAppearance.tsx` and `FixtureModel`'s `ColourSync` together.

### Fitted media: where a beam's colour and gobo come from

Since the fixture optics plan's session 3 a beam's colour and gobo are the **unit's**, not the
type's. The type's descriptor carries the stock — each option's `colourPreview` or `gobo` — and a
loadable setting (`SettingPropertyDescriptor.media`, `GEL` · `GOBO` · `GOBO_OR_GEL`) can be loaded
differently per unit: `FixturePatch.media` and each `PatchPlacement.media`, `{slots: {<setting>:
{<option>: {gel?, gobo?}}}}`. Backend contract in lighting7 `docs/fixtures-engineering.md` §"Fitted
media".

**The overlay is the client's, per unit, and every surface that draws a unit goes through it.** `lib/fittedMedia.ts`'s
`fittedProperties(properties, media, gels)` lays a unit's slots over the type's options — a fitted
gel becomes its library colour (`GET /gels`, read through `useGelIndex`), a fitted gobo the pattern
and no colour, an empty slot open white and no pattern, a slot not fitted the stock — and every
finder below it (`findColourSource`, `findGoboProperties`, …) reads the result, so the dispatch
itself did not change. `FixtureModel` and `FixtureAppearanceSource` both overlay **before** they
dispatch, and for a placement `patchAtPlacement` has already laid its own over the patch's, option by
option (`mediaOver`): *the placement's slot, else the patch's, else the stock*. It is the client's
because a descriptor is per fixture and a fixture with extra placements is several units; only the
surface drawing a placement knows which one it is drawing. It returns the descriptor list itself
when nothing is fitted, so a memo keyed on it does not churn for the common rig.

**The controls still name the stock string.** The surfaces that *draw* a unit overlay it — the 3D
view, the 2D appearance leaf (the Positions chips, the busk tiles, the side sheet's fold, the colour
editor's Pick) and the patch sheet's Media box and the Focus tab's list. The surfaces that *set* a
value do not: the programmer's setting cell and its column, the property visualisers, the fixture
card and `EffectParameterForm` list a setting's options with the type's stock `colourPreview`,
because they write a DMX slot and a slot is the type's, whichever gel a unit has in it — and on a
fixture with extra placements one cell drives several units with different strings, so there is no
one fitted colour for its swatch to show. Teaching them the patch's own is
`FU-MEDIA-CONTROL-SWATCHES`.

**A gel in a filter slot multiplies the beam.** A unit's other gel-taking loadable settings — the
Revolution's media frame and its module wheel holding a dichroic — are **filters** (`mediaFilters`,
and since session 4 `colourFilters`, which adds a second colour wheel ahead of them — below):
each multiplies the beam's colour by its current slot's colour (`filterColour`, subtractive, gels
in series), and a slot with none — out of the beam, empty, a gobo — passes it unchanged. Both
dispatches apply them: the 3D syncs subscribe to the filters' channels and pass the hue through
`filteredHex`, and the 2D leaves through `Filtered` — one component per filter, the render prop's
fixed-hook-set rule. `colourDispatchParity.test.tsx` holds the two to one answer for a fitted
frame, an empty frame, a media frame in, a dichroic, and two units of one type drawing different
colours from the same scroller DMX. Only the colour is multiplied: a filter does not dim the beam.

**A second colour wheel is a filter too** (fixture-optics plan session 4). The Robe ColorSpot 575
has two wheels in series, the second of deep and corrective dichroics, and until session 4 the view
drew only the first. It **joins the filter path** rather than standing beside it: `colourFilters`
(`lib/fittedMedia.ts`) returns a wheel head's other COLOUR settings, in channel order, ahead of its
media filters, and both dispatches apply the list exactly as they applied `mediaFilters` — the 3D
syncs' `filteredHex`, the 2D leaves' `Filtered`. Physically it is the same thing as a media frame's
gel: glass in series whose current slot's colour multiplies the beam's, subtractively, and a slot
with none — open white, a scroll band — passes it. Joining keeps one multiply, one per-filter
subscription on each dispatch and one parity test; a separate path would have been a second copy of
all three on both sides. Only where the colour source is itself a **wheel**: beside an RGB colour
property a COLOUR setting is a preset or macro on the same emitters (the Hex's, the Orbit's), not
glass, and is left out. `colourDispatchParity.test.tsx` holds the 2D and 3D answers to one result for
a two-wheel head (yellow through cyan is green), a scroll band on wheel 2 (below), and an RGB head
with a preset setting. A **template** still cannot address the second wheel
(`FU-TMPL-SECOND-COLOUR-WHEEL`).

### Animated colour bands

A colour band with **no single colour** — a scroll, a random or rainbow program, an auto change —
**animates through the wheel's own previews** (fixture-optics plan D8), where until session 5 it drew
black (as a source) or passed the beam unchanged (as a filter). The desk marks such an option
`noColour` (lighting7 `docs/fixtures-engineering.md` §"Beam vocabulary"); every other COLOUR option
carries a preview, which `ColourPreviewTest` enforces, so a COLOUR band is never silent. The rules
are in one pure module, `lib/colourBands.ts`, which both dispatches call, so they cannot disagree:

- **`settingColourAt(options, level, timeS)`** is the band's colour: its preview; for an animated
  band its wheel's palette (`wheelPalette` — the options' previews in level order, each once, a
  blackout left out) at that time, each colour held for most of `BAND_STEP_S` (1.2 s) and eased into
  the next over the rest; `undefined` where the band says nothing.
- **A source draws `undefined` open white and lit** (`sourceBandColour` / `sourceBandLevel`) — never
  black for want of data, which is how every Varytec beam drew before its wheel carried previews —
  and a black preview (a blackout band) dark. A **filter** passes the beam through `undefined`.
- **Time is passed in, never read from a clock inside the decode**, so the parity test and the
  profile harness stay reproducible. The 3D path hands in the scene clock; the 2D path a shared clock.

**Both dispatches, and the second wheel.** The 2D leaves (`SettingColourAppearance`, `FilterStep`)
read the band through `useSettingBandColour`, whose time is `useColourBandTime(animated)` — a
`ColourBandClockContext` that ticks at 15 Hz and re-renders the leaf **only while its band animates**
(a colour change, not a frame-rate animation; the test supplies a fixed clock). The 3D syncs read
`settingColourAt` at the ticker's time in `SettingColourBeamSync` and in `filteredHex`, so a scroll
band on the Robe's second wheel passes the first wheel's colour through its own wheel's colours in
turn, on both dispatches. Cells (`CellColourSync`) take the same rule per cell.
`colourDispatchParity.test.tsx` holds 2D = 3D for an animated band at several times — mid-hold and
mid-move — on a single wheel and on the second wheel, and pins the Varytec drawing a colour, lit, at
every DMX value.

**The 3D path invalidates every frame while — and only while — a band is live.**
`stage3d/colourTicker.ts` is a plain object `FixtureModel` drives from a `useFrame` (registered before
the beam director, so the director reads this frame's colour) with the scene clock's elapsed time.
`useLiveColour`'s `apply` answers whether what it drew animates — the source's band or any filter's —
and the arm registers with the ticker while it does: each frame re-applies the colour at the new
time and asks for the next. The first apply that answers false — the channel moved to a fixed colour
— unregisters it, and a frame with no listener asks for nothing, so a wheel parked on a colour costs
the canvas no frames. Registration happens inside `apply` because whether the band animates is a
channel fact known only there. The ticker is passed as a prop, not a context, because `ColourSync`
is rendered outside a canvas by its tests and the capture root bridges only `ChannelSourceContext`.

**A loadable wheel is never index-guessed.** `resolveGoboSlot` falls back to an option's position on
a wholly unannotated wheel; a loadable wheel's options always carry `loadable`, so they count as
annotated — a slot whose content names no pattern (empty, or a dichroic) draws open, and a fitted
gobo draws its pattern. The Revolution's stock wheel, which ships empty, therefore draws open in
every slot until a unit is fitted.

### Strobe and closed shutters

A strobe channel's bands say what they do to the light (fixture-optics plan D12; lighting7
`docs/fixtures-engineering.md` §"Beam vocabulary"): closed, open, strobe, random or pulse, a flashing
band with its rate in Hz. Until session 6 the view read no strobe channel, so a closed band — dark
on the rig — drew lit: a MAC 250 at strobe 0 drew at full. The rules are one pure module,
`lib/strobeBands.ts`, which both colour dispatches call:

- **A strobe is a level factor**, multiplied into the dimmer's: `strobeFactor(props, read, timeS)` is
  the product over every strobe channel the fixture has (`findStrobeProperties` — a slider declaring
  `strobeBands`, or a setting whose options carry `strobeKind`; the LED Lightbar has two). Because it
  is the level, the lens, the beam, the pool and the 2D markers all follow it, and a closed band culls
  the beam exactly as a dimmer at 0 does (`LIGHT_OFF_OPACITY`).
- **Closed is 0, open is 1**, and a value no band covers — a reset band, or a channel from a desk
  that declares nothing — is 1: it draws as it always did.
- **The three-flash rule** (WCAG 2.3.1): the Stage view is on screens other people watch, so it never
  draws more than three flashes in any second. A **strobe** flashes at its rate up to `FLASH_HZ_MAX`
  (3 Hz), one `FLASH_S` (0.1 s) flash at the top of each cycle; a **random** band flashes once a cycle
  at a hashed point up to `RANDOM_HZ_MAX` (2 Hz — its flashes can fall back to back, and at 2 Hz any
  second still overlaps at most three cycles), seeded by the channel's address so two heads keep their
  own dice; a **pulse** swells and fades (a raised cosine) up to 3 Hz. Anything faster draws a
  **shimmer**: lit at `SHIMMER_LEVEL` (0.6, an estimate) and rippling by `SHIMMER_DEPTH` (8%), under
  WCAG's 10% change in luminance that makes a flash. `strobeBands.test.ts` samples every kind at rates
  from 0.5 to 30 Hz and holds every one-second window to three flashes.
- **Time is passed in, never read inside it** — the `colourBands.ts` rule — so the parity test and the
  profile harness stay reproducible.

**Both dispatches.** The 3D syncs subscribe to the strobe channels beside the dimmer and multiply
`liveStrobeFactor` into each arm's intensity at the ticker's time; `CellColourSync` multiplies it into
every cell's master, as the dimmer's. The 2D dispatch wraps whichever leaf answered in a `StrobeGate`
— its own component, so its hook set is fixed — which scales the leaf's level and every pixel's by the
factor at `useColourBandTime`'s time, ticking at 15 Hz only while a band flashes (15 Hz samples a
0.1 s flash at least once). `colourDispatchParity.test.tsx` holds 2D = 3D for a MAC 250 closed, open,
flashing, mid-flash and between flashes, shimmering, on a pulse and a random band past its clamp, on
the undeclared reset band, for an RGB head, and for a setting-backed shutter.

**The 3D path asks for frames only while a strobe flashes**, through the same `colourTicker.ts` an
animated colour band uses: `useLiveColour`'s `apply` answers `strobeAnimates` (a channel on a flashing
band, and none closed — a closed one holds the product at 0) beside the colour bands' answer, so the
arm registers while it flashes and lets go when the shutter closes or opens. A slow strobe keeps the
canvas drawing through its dark part of the cycle; a closed or open one costs nothing.

### Travel time

A head does not snap to a new pan, and a scroller does not jump a frame: they travel (fixture-optics
plan D14; lighting7 `docs/fixtures-engineering.md` §"Travel and timing channels"). Until session 8
the view drew every value the moment it arrived. Now it keeps a **displayed value per channel** and
moves it toward the DMX value at the type's speed (`FixtureTypeInfo.travel`), or over the fixture's
own timing channel's duration (`SliderPropertyDescriptor.timing`). **Drawn, never output**: nothing on
this path writes a channel, a programmer value or a gesture, and `travel.test.ts` reads the three
files that draw it and fails on any writer.

**One pure module, time passed in** — `lib/travel.ts`, the `colourBands.ts` / `strobeBands.ts` rule:

- **A move is planned when the target changes**, from where the axis is drawn *now*. Its duration is
  the timing channel's (`v × timingSecondsPerStep`, however far it goes — the Revolution's "duration
  of a movement"), else `distance / rate` at the type's speed, else nothing: a family the type
  declares no speed for snaps, as everything did before. 0 on a timing channel, and anything from its
  `timingFastFrom` (the Revolution's Focus Timing at 255, "more responsive manual control"), is the
  type's speed.
- **A stream keeps a timed move's arrival.** A change within `STREAM_GAP_S` (0.5 s) of the last is the
  same stream — a desk fade. While the stream's timed move is in flight a new target lands when the
  first plan would have; once that has passed the stream follows at the type's speed; a change after
  a pause is a new move with the whole duration. A fresh duration per change would draw a fade under a
  timing channel crawling in asymptotically (Chris's call, 2026-10-05).
- **Progress is linear.** A head accelerates, but a DMX fade streams a new target every frame and
  each re-plans from where the last left off; any ease-in would make a fade crawl, and a linear plan
  re-planned every frame is exactly a rate limiter.
- **Plans are made in frames only.** A channel callback can see a change between frames, and on the
  `demand` frameloop the scene clock may not have ticked for minutes: outside a frame (`stepTravel`'s
  `nowS = null`) a change is only noted — `isTravelling` answers true, so the caller asks for a frame
  — and the next frame plans it at its own time, or the idle gap would count as time travelled and the
  first frame would land the move.

**What eases.** Pan and tilt in degrees (after the DMX → degrees decode, before a movement macro's
offset); every beam channel the director reads, in DMX — focus, zoom (a stepped zoom passes through its
steps), iris, frost, both gobo wheels (a wheel passes through the slots between), the rotation channel
with its fine byte **while its wheel indexes** (an angle turns; a spin speed or a change of band is
drawn as sent — easing a speed through its stop band would draw a stop the wheel never made), and
the framing shutters' depth and turn; and the colour family — a colour wheel, the scroller and every
colour filter. The dimmer, the strobe, the prism and the macros never travel, and an RGB mix is
electronic: only its filters' glass does. Each family stretches only under its own
timing channel, and the colour timing channel only COLOUR-category channels — the Revolution's media
frame is a `setting` its manual leaves off Colour Timing, so it travels at the colour speed untimed
(`colourTimed`).

**What it eases from — it lands, never travels, whenever the picture was replaced rather than moved:**

| Event | Drawn as |
|---|---|
| **First paint** — a fixture's first frame on a canvas | The DMX value. An axis that has never drawn is landed (`TravelAxis.ready`). |
| **A vis-source switch** (Output → Next GO, a window's own source) | The new source's values, landed. Every axis remembers the source it was drawn from. |
| **The source's values replaced wholesale** | Landed. `ChannelSource.epoch` counts replacements: the wire's whole-buffer snapshot on each (re)connect and a resync's reply (`channelsApi.snapshotEpoch`, counting the frames the desk marks `snapshot: true` — never the first after an open, since a delta can reach a fresh socket first — so a canvas that mounted before the first snapshot does not swing every head in from 0), each Next GO preview (`setChannels`), a programmer map rebuilt over new descriptors (`refresh`); an overlay sums its two sides. |
| **A project switch or a repatch** | Landed: a new patch is a new `FixtureModel` (keyed by patch id), and new channel keys reset the director's and the colour arms' axes. |
| **A `render_view` capture** | The DMX, always. `Stage3D` passes `travel={capture == null}`: a one-shot render has no history. |
| **A move made in the dark** | Landed by the time the beam comes up: the director steps the beam axes before its dark and beamless returns, as it already did pan and tilt. |

**Where it lives — the 3D view only.** The beam director keeps a `BeamTravel` (`stage3d/beamTravel.ts`,
a plain object a test drives without a canvas) and steps it at the top of each frame, before any
read; the colour syncs keep one axis per channel (`beginColourApply` in `FixtureModel.tsx`) and read
the setting and filter levels through it, so a scroller in flight reads **the unit's fitted string**
at the drawn position — `settingProp` is the fitted one (§"Fitted media") — and the beam passes
through each frame's gel rather than cross-fading the two ends. **The 2D leaves and the Positions
chips do not ease**: they are the desk's readouts of what it is sending — the chips double as
selection controls, the busk tiles' bars as levels, the colour editor's *Pick* answers the set colour
— and a readout that lags would lie about the desk. So `colourDispatchParity.test.tsx` still holds
2D = 3D exactly as before: it mounts `ColourSync` with no `travel` (as a capture does), and travel is
held to its own tests (`travelColour.test.tsx`: the plan's check — Colour Timing at 5 scrolls frame 1
to frame 9 over 5 s, through every frame between, in order — and the untimed media frame, a source
switch and a replacement landing). Mid-flight the 3D colour is the same decode at the drawn level,
never a second dispatch. The Positions panel's Plan tab is a `Stage3D`, and eases.

**Frames.** The director treats a move in flight like a macro: `bt.moving` sets `animating`, which
asks for the next frame, and a settled head asks for none. A colour arm's `apply` answers whether a
channel is drawn short of its target beside the band and strobe answers, so it registers with
`colourTicker.ts` while — and only while — a move is in flight; `useLiveColour` now tells `apply`
whether the ticker ran it (`inFrame`), the only case in which a colour move's clock may start.
`travelColour.test.tsx` holds that a landed scroller unregisters and that later frames invalidate
nothing; `beamTravel.test.ts` that a settled head reports nothing moving.

## Paired lanterns (extra placements)

A paired dimmer is one patch drawn more than once: one circuit, an SL and an SR lantern on the same
bar. The patch carries its other lanterns as `FixturePatch.extraPlacements` (backend contract in
`lighting7/docs/fixtures-engineering.md` §"Extra placements (paired dimmers)"), and every stage
surface draws each one **lit from the patch's own channels** — a lantern is the fixture in a
second place, never a second fixture. Four rules keep that true:

- **`useProjectedPatches` returns the lanterns apart from the fixtures** — `points` (one per
  fixture, unchanged) and `extraPoints` (one per placed lantern, carrying `placement`). A surface
  that only draws the rig (the overview panel, `MiniStage`) draws both; the editors keep reading
  `points` for everything a gesture does. Folding the lanterns into `points` would have made every
  drag, snap, count and bulk operation meet a fixture twice, and a drag on a lantern would move the
  fixture's own placement to where that lantern hangs.
- **A lantern selects its fixture and is never dragged.** On a section while editing its press is
  the fixture's selection with no drag, a marquee over it selects the fixture once, and it is an
  alignment guide for the fixture it pairs with. In 3D it is a `FixtureModel` over the patch with the
  placement's seven geometry fields laid on (`patchAtPlacement` in `stage3d/lanterns.ts`), its own emitter slot after the
  fixtures', and **no `onEditFocus`**, so the translate gizmo can never bind to it. Lanterns are
  moved in the patch form's *Also hung at* section (`ExtraPlacementsFields`), which starts a new
  one mirrored across the centre line (`lib/extraPlacements.ts`).
- **Selection lights the pair.** Selection is by patch key, so selecting a fixture highlights every
  lantern it has, which is the answer to "which lanterns is this circuit?".
- **An unpositioned lantern is not drawn**, on either view: the 3D `worldPositionFor` reads a null
  coordinate as 0, and a lantern at the origin would be a claim about the rig.

What is left for later: dragging a lantern on a section (the bulk route already takes
`extraPlacements`, so it is a client change), and a lantern of a different type or gel from its
fixture (additive on the backend if a venue ever needs it).

## Variable-length fixtures

A lightstrip's length is a fact of the install, not of the model: the type says so with
`FixtureTypeInfo.acceptsLength`, and a patch then carries its own `lengthM` (backend contract in
`lighting7/docs/fixtures-engineering.md` §"Variable-length fixtures"). A run laid round several
sides — a ring round the stage edge on one controller — is one patch whose **extra placements are
the other sides**, each with a `lengthM` of its own (null takes the patch's). Everything above about
paired lanterns holds for a side: lit from the patch, selects the patch, never dragged.

- **`lib/fixtureLength.ts` is the one reading of it.** `drawnLengthM` answers side → patch → type
  default for a type that takes a length, and the type's own length for every other type — so a
  stored length that reached a fixed-length patch some other way (an import is written as stored)
  is never drawn. The editors ask `acceptsLength` whether to offer the field at all.
- **3D**: `FixtureModel` sizes its body from `drawnLengthM`, and a side's length reaches it because
  `patchAtPlacement` lays the placement's `lengthM` over the patch's.
- **On a section**: `useProjectedPatches` gives such a fixture (and each side) a `span` — its two
  ends, projected — and the section edit layer takes a press anywhere along it
  (`edit/sectionHits.ts`), since a long run is pressed at its ends as often as its middle. A span
  that projects to a point (a run seen end-on in an elevation) is its point. The long axis is `longAxisLighting`, which
  mirrors `FixtureModel`'s body rotation exactly — a YXZ Euler of `(basePitchDeg, baseYawDeg,
  baseRollDeg)` in world space, so the rigging's pose places the body but does not turn it, pitch
  never moves the long axis, and roll is the only turn that lifts it off level (90 stands a run on
  end). Change one, change both.
- **The form**: *Length* sits under the fixture's placement, and the extra-placements section reads
  *Other sides of this run*, each side with its own *Length*. An out-of-range value is kept and
  flagged, never clamped mid-keystroke, and the form will not save it.

## The 3D renderer

Session 0 of `../docs/plans/completed/stage-view-plan.md` fixed the Stage view's renderer before anything is
added to it. The findings are in the design record (`stage-view-design/INDEX.md` §"What is wrong
today"); what follows is what the code does now and why, since each piece is easy to undo by
"tidying".

### No post-processing, DPR at 1.5

The scene is drawn straight to the canvas by `StageRender` (priority `STAGE_RENDER_PRIORITY`, after
the bodies' and emitters' flushes — R3F stops drawing on its own once any frame subscriber has a
positive priority). There is no composer and no bloom, as in the prototype
(`../docs/plans/stage-view-design/prototype.html`): every fragment encodes itself for the canvas
(`linearToOutputTexel`), so the additive beams and the overlapping pools blend in display space and
two beams crossing visibly sum. The canvas clears **opaque** to `STAGE_BACKGROUND`: a beam adds at
full alpha, and over a transparent clear its faint edge would darken the CSS background behind it.
The canvas's own `antialias` reaches the bodies' edges again, and the composer's half-float targets —
which ran to hundreds of MB at a Retina full-window size with their default 8× MSAA, the likeliest
cause of Safari's "reloaded because it was using significant memory" — are gone with it.

The canvas's `dpr` is `[1, 1.5]`: at 2 every target behind the canvas holds four times the pixels of
1×. `StageEmitters` drops the volumetric march by a third above 1×, since 1.5 is still 2.25× the
shaded area.

### The frameloop renders on demand

`frameloop="demand"`: the canvas draws while something moves and not at all when the stage is still.
The rule for anything new in the scene is **a write that is not an R3F prop must ask for a frame**.
R3F already invalidates on an applied prop change, and drei's `OrbitControls` and
`TransformControls` on their own `change` events (the orbit's damping included). What else asks:

- **Channels.** `FixtureModel`'s beam director subscribes to the thirteen keys it reads per frame on
  the *active* source — so a programmer-only or Next GO preview moves the heads — and `useLiveColour`
  asks after every imperative colour write. Both go through `useStageInvalidate`
  (`stageInvalidate.tsx`), a context rather than `useThree`, because the colour syncs are rendered
  outside a canvas by their tests.
- **Time.** A movement or LED macro, a spinning gobo (the turned wheel's, while it shows a pattern —
  `stepGoboLayers`' `spinning`; a static wheel never asks) and a turning prism move with the clock, not with
  DMX, so while one runs the director asks for the next frame itself. An **animated colour band**
  (a scroll or random wheel band) asks through `colourTicker.ts` while it is live (§"Animated colour
  bands"), and so does a **flashing strobe** (§"Strobe and closed shutters"). That is the one case
  where the
  canvas keeps rendering with no channel moving. A **move in flight** (§"Travel time") asks the same
  way, through the director's `animating` and the colour arms' ticker registration, until it lands. Their `delta` is clamped to 0.1 s, which also covers
  the long gap after an idle spell.
- **Imperative buffer writes from effects** — `hideSlot` when a fixture loses its beam or
  unmounts, a body's `hide` and `setActive` — and the region uniforms (below). A body's parts are
  written from its own frame loop (§"Fixture bodies"), so they need no request of their own. The light table is packed in the emitters'
  flush, which runs inside a frame (`EMITTER_FLUSH_PRIORITY`, before `StageRender`), so it needs no
  request of its own; a budget change does ask.
- **The label layer** hands the store the canvas's `invalidate`.

A hidden browser pane or background tab gets no `requestAnimationFrame`, so it draws nothing until it
is shown; that was true of the old `always` loop as well.

### Context loss

Nothing handled `webglcontextlost`, so WebKit taking the context back left a blank canvas under a
layer of labels. `ContextLossWatcher` (in `Stage3D.tsx`) now cancels the event's default — without
which the browser never offers the context back — and the view draws **3D paused** with the reason
and **Restore 3D**. The label layer is hidden with it. A browser that restores on its own
(`webglcontextrestored`) clears the state; **Restore** does not wait for that and remounts the canvas
(`canvasKey`), a fresh renderer and a fresh context, because a context taken to save memory may
never come back. The camera comes back where it was: the pose is in `sessionStorage` (§"Cameras and
viewpoints" below). The View menu's **Test recovery** (3D only) drops the context through
`WEBGL_lose_context` so the path can be exercised on purpose.

### The emitters are sized by the rig

The shared emitter meshes (`StageEmitters`) used to give every slot `MAX_PRISM_LOBES` beam instances
and `MAX_WASH_PIXELS` wash instances, times the region count on the receivers — at 45 fixtures and 16
regions, 4,320 region cookies and 11,520 wash-region instances whether or not a prism or a pixel bar
was hung, every one through the vertex shader each frame. (Session 3 retired the receivers
altogether — §"Light lands through one surface shader" below.) Now `emitterNeedsForSpec` (`emitterNeeds.ts`)
says per slot what it can draw, from the fixture's **body** (§"Fixture bodies"): **no lobes** for a
body that does not emit, **one** for a single cell, **six** where it has a prism, or **one per cell**
for a body of several (a batten, a blinder, a bar of heads); and **lights**, a row of the light table
for each lobe of a single cell and at most four for a body of several, each averaging a run of cells
(`lightRuns`). `buildEmitterLayout` (`emitterLayout.ts`) turns that into per-slot offsets for the
two blocks. The per-pixel wash block a pixel strip had until session 6 is gone: a bar's cells are
beams now.

- **One statement of the rule.** `FixtureModel` draws from the same `bodySpecOf` the layout sizes
  by; two copies would drift, and a fixture that drew more than its slot was given would write into
  the next fixture's block.
- **The handle drops out-of-block writes.** Every writer bounds-checks `(slot, lobe)` and
  `(slot, light)` against the layout, and the directors draw at most `lobesFor(slot)` lobes and
  `lightsFor(slot)` lights. A frame where the patch and the layout disagree draws less, never into a
  neighbour.
- **The rebuild keys on `layout.signature`**, not the layout object, which is fresh every render;
  equal needs address identically, and rebuilding would throw away every slot's written state.

### Light lands through one surface shader

Until session 3 a beam drew its pool as instanced **cookies** — one per lobe per receiver: the stage
floor, the back wall, and every region (the region and wall and floor instances, beam and wash). A
receiver the cookies did not know about took no light, which is why the plan capped regions at 16,
and a venue made of rooms, flats and drapes would have needed cookies for each. They are gone, with
the pool shaders and `makePoolMaterial`. Light lands through **one receiver shader** instead
(`scene/surfaceShader.ts`), on every venue and set surface, every region, the stage floor, and —
without a room — the back wall and the catch floor:

- **The lights are a data texture, not a uniform array** (`scene/lightTable.ts`). The prototype's
  48-light ceiling was the uniform budget; a float `DataTexture` of six texels a light
  (`MAX_LIGHT_BUDGET` = 256 rows, read with `texelFetch` in a GLSL3 loop over `uLightCount`) has
  none. Every light has a slot in the `LightTable`, sized from the emitter layout; the directors
  write their slot each frame (`writeLight`, `clearLight`), and the emitters' flush packs the
  **budget**'s worth of the brightest lit slots into the texture, in slot order. Since session 6 a
  light carries its beam's **apex** (behind the aperture), its frame (the head's right axis and the
  tangent of the half-field; since fixture-optics session 4 the right axis is written as a direction
  in a basis built from the beam's axis, beside the gobo layers — §"Gobos on surfaces") and its
  aperture (the apex → aperture distance, the iris and a segment's aspect) — what the surface
  shader's `beamMask` shapes the pool with.
- **The light budget is the viewer's** — the View menu's *Light budget*, 32 · 64 · 128 · 256, default
  64, per browser in `localStorage` (`stage.lightBudget`, `scene/sceneView.ts`): the shader's cost is
  pixels × lights, and what a machine's GPU affords is the machine's fact, not a window's. A light
  the budget drops still draws its beam; it lands on nothing that frame. The container carries
  `data-lights="<packed>/<lit>"` for measurement.
- **Axial beam reach is where a beam ends in the air** (`scene/beamReach.ts`). The director casts
  each lobe's axis against the scene's **colliders** — oriented boxes in three.js space turned about
  y, the regions' OBB maths: a wall a 2 cm slab behind its face, a deck its whole box — out to
  `MAX_THROW_M` (40 m), and the first hit's plane is where the beam lands (with a second face's for a
  beam split across an edge, below). The haze draws nothing more than `REACH_EPS` (3 cm) behind it.
  The surfaces read those planes only as a fallback: they are shadowed by the colliders themselves
  (the box occlusion bullet below). The **cone**
  is cut at the same plane, however far away it is, so a follow spot on the Commemoration Hall's
  balcony reaches the stage 20 m away; the hull runs on past the axial hit to where the cone's far
  rim meets the plane (`coneLandingDepth`, capped at `MAX_THROW_M`), so a grazing beam is in the air
  over the whole of its pool rather than ending square to its axis. Only a beam that meets nothing
  keeps the desk's stylised `BEAM_LENGTH` (8 m). How much of a long throw shows in the air is the
  window's Haze setting (§"Haze degrades before frame rate"): Stage clips it at the proscenium,
  Everywhere and the Positions plan draw it whole.
- **A collider holds what it draws, and carries a skin** (stage-light plan D1): how far behind its
  face the drawn surface can lie. A drape's box is exactly as deep as its pleats (`scene/pleat.ts`'s
  one fold, which the mesh, the box and the fold shadow all read: the element's `depthM` crest to
  trough, 2–30 cm — a cyc's no more than 2 cm), and its skin is that depth plus `REACH_EPS`; a cylinder's side faces take its radius, since a box round a column has its
  tangent at the front face, and its flat top and bottom `REACH_EPS` (a lamp shade's top and bottom
  its height, which its sloped side faces); every other face's is `REACH_EPS`. A collider carries the
  two (`skin` for its level faces, `capSkin` for its top and bottom), the hit takes the one for the
  face it struck — a beam on a round rostrum's top leaves the deck round it dark — and carries it on
  (`BeamHit`, the director's `SurfaceHit`, both planes of `edgeLanding`), and
  `packLanding` moves each plane back by its face's skin beyond `REACH_EPS` — so a pool on a drape
  lights its troughs with its crests and a column is lit across its face. The landed **point** is
  untouched: the beam's length, `coneLandingDepth` and *Focus here* read it. Before, a drape's box was
  10 cm deep round 5 cm pleats, the plane sat 2.5 cm in front of the crests, and only each crest's cap
  was lit — the thin stripes a backcloth drew. The same skin is what lets a surface inside its own
  box through the box occlusion test below.
  `?profileHarness=drape` (`profileHarness.ts`'s drape scene) is the scene to sweep a pool across a
  backcloth, a tab's edge and a column in.
- **A beam split across an edge lands on both faces** (`scene/landing.ts`, `edgeLanding` in
  `FixtureModel.tsx`). One plane cannot stand for a convex edge: a follow spot aimed at the front of
  the stage has its axis on the riser, and cutting at the riser's plane took the half that clears
  the lip off the deck and out of the air. The director casts eight rays round the field's rim and
  keeps a second face when a rim lands behind the first face's plane and the first face lies behind
  the rim's in turn (the riser and the deck, from either side); a rim that lands on something beyond
  instead, such as the stalls floor past the lip, is bisected back to the edge and the face just past
  it tried. Of several, the landing nearest the first face's plane wins: the deck, not a rostrum
  further upstage. Nothing is hazed **behind both** planes, which is the inside of the stage, and
  the hull is drawn until the cone has crossed both. Where the rim passes a **vertical** edge of
  the box the axis hit — a flat's or a tab's end, a leg's side — the second plane is the one
  through that edge and the lamp (`edgeShadowPlane`): the edge's shadow line, found from the box
  rather than from whichever rim ray happens to clip its end face, so a pool sliding off a tab onto
  the backcloth behind it keeps its second plane at every pan. (It was the end face itself, found by
  luck: 5 cm of target, so the drape harness's pool lost and regained its far side between DMX 46
  and 46.5.) The hull then reaches where the rim past the edge landed. A box the rim passes on
  **both** sides, as the lamp sees it — a column narrower than the beam — has two shadow lines and
  room for one, so the beam passes it: the landing moves to the nearest surface the rim reached
  beyond it and the box stays lit in front of that plane. The haze passes it; on the surfaces the
  box casts its shadow (below). A level edge (the top of a flat, the bottom of a border) keeps the face past it,
  which must bound the solid as the deck does. Both planes ride the light's fourth texel and the `aBeamLand`
  attribute, one `vec4` as the single plane did: a collider turns about y, so a face's normal is up,
  down or level and one float codes it (`landNormalCode`). In the march, behind both is an interval
  of the view ray: at an end of the chord it trims the chord, and inside it (an edge seen side-on)
  the samples in it are skipped.
- **Boxes cast shadows on surfaces** (stage-light plan session 3, D8; `scene/occlusion.ts`). After
  the mask and the gobo, the surface shader tests the segment from the fragment to the lamp — to
  `MIN_REACH_M` short of the aperture's plane, the stretch beam reach ignores in front of the lens —
  against the colliders in that light's cone. So a flat shadows the wall behind it, a column the
  backcloth, a leg the leg upstage of it, and a beam wider than a box passes it on both sides with
  the box's shadow between. No shadow map and no extra pass: two float textures beside the light
  table, which stays at six texels. **The colliders**, two RGBA texels each — the centre and the
  yaw's cosine (a box turned by π is the same box, so the turn is folded to a sine of at least 0
  and the shader takes `√(1 − cos²)` rather than a `cos` and a `sin`), then the half-extents and
  the skin — packed from the same `sceneColliders` the director casts at, whenever they change.
  **Each packed light row's list**: the count, then one texel an entry — the direction from the
  apex to the collider's bounding sphere, octahedrally encoded; the cosine of the angle the sphere
  spans; and the collider's index and the distance to the sphere's near side, packed exactly in one
  float. `cullLightColliders` fills the lists in the emitters' flush, after `pack`, with
  `coneReachesSphere` as its test; a box whose sphere is wide for its distance (a room's 18 m wall)
  is halved along its longest axis and tried in pieces, or every wall would be in every list. A box
  the aperture sits inside is left out, as beam reach leaves it. Only the rows packed are uploaded,
  each as far as its list runs.
  - **A box the fragment is inside passes it** as long as the fragment lies within the box's skin of
    the face the segment leaves by: the larger of the collider's two skins, one number for all six
    faces. So a pleated cloth inside its own box keeps lighting its troughs (whether a crest stands
    in the way is `foldLight`'s question, above), a column's face inside its square box is lit, and
    the floor under a deck — a deck's height behind the top the segment would leave by — stays
    dark. A segment that ends inside the box it starts in is not blocked by it, and a box entered
    within a millimetre of the fragment counts as one it is on. The box is the shadow's shape: a
    column throws a square-sided one. Fixture housings are surfaces like any other and take the
    same shadows, so a head standing in a flat's shadow is darker; a housing sunk more than its skin
    into a deck or a wall's box is darkened by it too.
  - **The sphere is the shader's first question.** A fragment outside an entry's cone from the apex,
    or nearer the apex than the sphere's near side, skips the box test on that one fetch. Measured
    with the occlusion bench (`/occlusion-bench.html` on the dev server, `occlusionBench.ts`) on the
    desk Mac's GPU (Chromium, ANGLE Metal on an M3 Pro) at 1024 × 640, with twelve lights each over
    every pixel: 0.97 ms a frame on the landing-plane path #67 drew; +0.106 ms a frame for
    every list entry that skips and +0.27 ms for every one that runs the box test. A bare index and
    box test, before the skip, was +0.177 ms an entry, so the skip pays wherever fewer than about
    43 % of a light's entries stand behind a given fragment. The Commemoration Hall packs 61
    colliders; its two advance-bar spots carry 11 and 8, its 9.7° balcony spot 49.
  - **A light's list holds at most `MAX_LIGHT_COLLIDERS`** (64 — provisional, until
    `FU-MANUAL-STAGE-LIGHT-BUDGET`'s Safari and iPad pass); a light whose cone reaches more falls
    back to its landing planes (texel 3, `landing.ts`'s `behindLanding`), as does every light while
    the scene holds more colliders than the texture's 1024 rows.
  - **The haze keeps its planes** (`aBeamLand`, `edgeLanding`). It samples a beam pixel twelve times
    (eight above DPR 1), so a box test per sample would cost about twelve times what a surface pixel
    pays: +22 to +53 ms a frame at the bench's load for a 16-entry list, against the surfaces' +1.8
    to +4.4. So a beam in the air still stops at the planes and still passes a box it overhangs on
    both sides; its pool is shadowed properly.
  - `?profileHarness=shadow` (a flat 1.5 m in front of a wall under one profile from high front
    stage left) is where a shadow is seen landing clear of what casts it. In the drape harness the
    column throws its shadow on the backcloth, and the pool swept from pan 30 to 50 in quarter steps
    at tilt 204 changes by at most 4.4 % a step (49.75 → 50, part of a steady slope) where #67 lost
    7.8 % in one (43 → 43.25). The hall's *Balcony · desk* view keeps its balance against #67: of a
    16 × 10 grid of cell means, 156 are within 0.1 of a level; the four that moved are Legs 3 SL's
    face, which Legs 2 SL now shadows from the advance bar.
- **A focus channel focuses at a distance.** Where the type declares a focus range
  (`@FixtureProperty(focusNearM =, focusFarM =)`, `docs/fixtures-engineering.md` §"@FixtureProperty"),
  the channel sets one focal distance from the aperture wherever the head points, so a spot focused
  on a downstage mark goes soft on an upstage one, and a cue's focus means the same distance on every
  mark (`resolveDeclaredFocusDistance`). DMX runs **linearly in 1 / distance** between the ends: a
  focus motor moves the lens linearly, and the lens's travel from infinity is very nearly
  proportional to 1 / distance. So a 2 m-to-infinity spot is under 4 m at half travel, and a long
  throw is focused in the last few percent of the channel, as on the rig. A type that declares no
  range racks over the axial throw instead (`focusRangeM`): 15 % of it to all of it, quadratic, never
  less than `BEAM_LENGTH`, so full focus is sharp wherever it lands.
- **Defocus is the relative focus error, scaled by the type's depth of field** (`focusBlur` in
  `beamMask.ts`, shared by the surfaces and the haze; fixture-optics plan D9): `dof · |f − d| / f`
  field radii at `d` from the aperture **along the axis** (`beamFocusBlur`), and an unfrosted edge on
  the focal plane is as hard as the mask draws one. The blur spreads the edge **both ways**, so a soft
  pool keeps its size, and a focus head's light reaches past its field to let it. It replaced a
  lens-radius blur circle that made a 24 m wall look the same from DMX ~140 to 255. See §"Focus" under
  §"Fixture bodies" for the model, the constants and *Focus here*.
- **A pool falls off from the aperture**, as the haze above it thins along the throw
  (`washConfig.ts`): as the square of the distance out to `FALLOFF_KNEE_M` (6 m, the prototype's
  throws) and linearly past it — how an eye adapted to the stage sees a long throw, not how a meter
  reads it. A beam narrower than 20° lands brighter by the area it does not spread over (capped for
  a pinspot), so a 15° Revolution on the balcony still lands on the back cloth 24 m away; a wider
  beam lands as the prototype drew it. The aperture also sets the distance the **focus** is measured
  from (§"Fixture bodies").
- **A finish reflects its own albedo, and exposure makes the darks read** (stage-light plan D6): the
  finish × (the room's ambient + the material's own `fill` + every light), times the exposure
  `SURFACE_LIGHT_GAIN` (4.4), rolled off once by `1 − exp(−·)` **on the luminance** and encoded
  (`rollOff`, with its TypeScript twin). Overlapping pools add, a rig at full does not clip, and the
  colour keeps its chroma: a white pool on the default red drape is red, and a colour too bright to
  keep runs to white rather than to another hue (the per-channel curve ran orange to yellow). There
  is no reflectance floor: the 10 % one made `#101012` serge reflect 18× its finish, like 35 % grey
  paint, and per channel it turned a red drape's pool grey. The exposure is four times the
  prototype's 1.1 so black serge shows a spot, dark but plainly lit; the ambient (0.003) and the
  housings' fills (`bodies/palette.ts`) are a quarter of what they were, so what no beam reaches —
  the house, the rig — reads as before, and `litByFill` draws the billboard housing on the same
  curve. A catch surface reflects as a 25 % finish, which is what it drew at before. A light row
  carries colour × level × `POOL_SCALE` (40, the prototype's typical lamp power) and the haze its own
  `VOL_GAIN`; neither moved, measured on the Commemoration Hall's balcony view against session 1: the
  haze, the housings, the seats and the walls read the same, a floor pool a little darker (101 → 88
  of 255) and the black backcloth's pool half as bright (129 → 64). `?profileHarness=cyc` (a white cyc, black serge and the
  default red drape under one light each), `=floor` (three floor finishes from the house) and
  `=rake` (a drape lit square on, at 45° and at 75°) are where these constants are judged.
- **Pleats shadow each other** (stage-light plan D2, D7). A drape's fold is a sine whose depth is its
  `depthM` and whose fullness that depth sets, 1.5× at 5 cm to 2× at 20 cm — the 50–100 % a stage
  drape hangs at — with a phase that wanders ±30 % by a noise seeded from the element's uuid (and the
  half, for a pair of tabs), so no two pleats are alike while every crest stays at the fold's depth.
  A drawn half measures its folds from its outer edge (`anchor`, `pleatShift`), which stays put while
  it gathers, so its folds stay with the cloth; the shift is a uniform, so a draw rebuilds no
  material.
  The mesh is drawn at ten segments a pleat with the fold's own normals, and its material gets the
  fold as uniforms (`uPleat`, `uPleatWarp`) and its frame from the mesh's `modelMatrix`, declared in
  the fragment stage. The shader takes the normal from the fold, and asks `foldLight` whether a
  crest stands between the point and the lamp: in the fold's section the ray climbs at a slope, the
  cloth beats it by most at the first crest's shoulder — where the cloth's slope equals the ray's —
  so one closed form answers it, with a 4 mm soft edge and no shadow map. A lamp nearer the cloth's
  normal than its steepest flank casts no fold shadow; raking light bands it. A trough sees less of
  the room's ambient by how steep its walls are. `pleat.ts` holds the twin, pinned against a march
  along the ray.
- **Gobos land too** (fixture-optics plan session 4, D10): a light carrying gobo layers samples the
  gobo atlas in its own frame, blurred by the same focus term as its edge — §"Gobos on surfaces"
  under §"Fixture bodies".
- **The beam volumes still shadow on regions** — `regionShadowMask` (`beamLobes.ts`, which was
  `beamCookies.ts`) and the region uniforms below. Only the receivers went.

The region uniforms survive for that: a region drag writes the RTK cache per frame, so the volume
shaders read each region from `uRegionCenter` / `uRegionHalf` / `uRegionYawCs` rather than a baked
buffer, and a drag is `writeRegionUniforms` and an `invalidate`. The yaw pair is `(cos yaw, sin yaw)`,
the region's own turn — `rayObbT` rotates a ray in by −yaw, and `beamReach` makes the same turn. It
was `(cos −yaw, sin −yaw)` once, which turned every shadow box the wrong way; invisible at 0° and 90°.

**A modelled room replaces the stage's own shell** (`scene/stageSurfaces.ts`). While a `ROOM` is
drawn the stage box's back wall, the catch floor and the grid (outside edit mode, where it is a
measure) give way to it, and the beams in the air clip to the lowest room floor, the furthest
upstage wall and the outermost side walls (`beamClipFor`). The stage floor stays; a room's faces
sit 4 mm outside its box (`ROOM_FACE_INSET_M`) so a region's top at the room's floor does not
z-fight it.

### Haze degrades before frame rate

The raymarched beam volumes are the costliest thing drawn per pixel and the least essential — a gobo
through fewer march steps is grainier, not wrong — and since session 6 **every** beam marches
(§"Fixture bodies"), so `HazeGovernorProbe` (in `Stage3D`) feeds
`scene/hazeGovernor.ts` the time between frames of a **continuous run** (a frame counts only when the
frame before asked for it from inside the loop, R3F's `internal.frames > 1`; on demand, the gap is
otherwise how long nothing moved). A run averaging over 28 ms steps the march down a tier (step
scale 1 → 0.66 → 0.42 → 0.25); one under 19 ms for three seconds takes a tier back; 24 frames hold a
tier before it may move. Each run is judged on its own frames: when one ends the governor drops
what it measured (`endRun`), so the recovery clock never counts through idle time and a new run
never steps on the last one's average; the tier itself is kept. A gap over a second is a pause, not
a frame — deliberately far above any frame worth governing, because a software renderer at ~700 ms
a frame is exactly the case the rule is for. The light budget is the viewer's and never touched by
it. The container carries `data-haze-tier`.

The View menu's **Haze** is how far the air shows the beams, per window: **Off**, **Stage** (the
default) or **Everywhere**. Off does not draw the volume mesh at all, rather than marching it to
nothing. Stage keeps the haze upstage of the most downstage shown proscenium, along its own turn, or
of the stage's downstage edge where none is modelled (`hazeClipFor` in `scene/stageSurfaces.ts`): a
front-of-house beam is otherwise a cone through the whole house, and the house is where the
audience-side camera looks through to see the stage. The plane is read from the stored elements, so
hiding Venue does not move it, and reaches the shader as one more chord clamp (`uHazeClip`) beside
the floor and the wall, so every march step lands in the haze that is drawn. A canvas that draws no
scene (the Positions plan) is never clipped. A window that stored the old on/off reads `false` as
Off and anything else as Stage.

The haze *level* is still `washConfig.ts`'s constant. The plan wanted it to follow the hazer's DMX,
but nothing in a rig says which fixture is the hazer (the Commemoration Hall's is a generic dimmer),
so that waits for a typed identity (`FU-STAGE-HAZE-FOLLOWS-HAZER`).

### Regions hang down from `centerZ`

`centerZ` is a region's **top surface** — the deck — which is what the backend
(`models/stageRegions.kt`), the MCP schema and the aim tool always meant. The frontend drew the box
*up* from it, so project 15's "Main stage" (top at 0, 0.95 m thick) stood 0.95 m proud of the deck
and pools landed on the wrong surface. Every reader agrees now: `StageRegionMeshes`,
`computeRegionGeometry` (the emitters' OBBs), `worldCornersFor` (a section's outline and both
handle sets), and the height handles on the orbit camera (`RegionEditHandles`) and on the Front and
Side sections (`edit/SectionEditHandles.tsx`) — dragging the top moves `centerZ` and keeps the
floor, dragging the floor changes only `heightM`. The orbit camera's rotation handles sit on the
deck, where a deck at 0 keeps them above the stage floor. A region placed by a click
(`routes/Stage.tsx`) stands on the clicked height — `centerZ` is the click plus its height — and a
section's bounds reach down to the lowest region floor (`sceneBoundsLighting`). Regions authored while the frontend read `centerZ` as the floor now
draw one thickness lower; they are re-set on the desk, not migrated (the plan's P5).

### Lenses and housings

Every lens was a sphere at `0.5 + 0.5 × brightness` opacity, so a lamp at dimmer zero was half-lit in
its full hue and bloomed. `lensColour` (`bodies/palette.ts`, `paintLens` until session 6) makes a
lens dark glass at level 0 and the hue at level 1, never brighter than its level — every cell of
every body goes through it. Since session 6 a lens is a flat disc or segment on the barrel's face,
one instance per cell, and housings, yokes and hangers are matt near-black **lit by the surface
shader** like any surface, with a fill of their own (`HOUSING_FILL`) so the rig reads against a dark
room. A lens mixes from dark glass to its hue in display space on `level^0.6`, and nothing glows: there
is no bloom. **Selection is the housing alone**, as the prototype has it: a selected fixture's
housing turns the desk's blue (`HOUSING_ACTIVE_COLOR`, its brighter fill folded into the instance
tint) and its label the same blue chip; there is no ring, no outline and no change to any beam, which
always shows true output. Hover lights a body only while editing; in view mode it changes the cursor.

### The label layer

Labels were a drei `<Html>` each — a React root and a `backdrop-blur` per label, sixty-odd over the
canvas. `StageLabel` now renders an empty anchor group and registers it with a `StageLabelStore`
(`stageLabels.ts`), which owns one plain `<div>` per label in a single layer over the canvas.
`StageLabelDriver` lays them out once per rendered frame: project each anchor, place greedily by rank
— hovered or selected first, then positions (rigging and regions), then fixtures — and hide whatever
collides, with a couple of pixels' gap. Three rules:

- **The mode is the store's, not the call sites'.** *Positions* (the default) shows the rigging,
  the regions while editing (a region draws no label outside Edit), and a fixture only while hovered
  or selected; *All fixtures* every fixture that fits;
  *None* nothing at all — not even the selection, unlike the prototype. So `FixtureModel`,
  `RiggingMeshes` and `StageRegionMeshes` mount their label unconditionally and say only its `kind`
  and whether it is `emphasised`.
- **A label is registered once per store** and restyled in place on a rename or a hover; tearing the
  `<div>` down on every hover was the churn the layer exists to avoid.
- **The flag was a boolean.** `StageViewFlags.labels` is a `StageLabelMode` now, and a desk's stored
  `true` / `false` reads as *Positions* / *None* (`toStageLabelMode`).

### A quiet stage: no chrome, regions and bars as the real things

The stage box's wireframe, the XYZ arrows and the *FOH* / *upstage* floor text are gone; the grid
stays as Edit's measure. **Regions** are data — the playing surface, an aim target and a beam
receiver (D5) — so outside Edit a region is only a plain lit deck, drawn only where no platform links
to it; a linked platform draws its own deck and finish. In Edit a region gains a faint outline and its
name and takes a click. **Rigging is drawn by kind** (`riggingShape.ts`): a bar, pipe or boom is a
48 mm tube in `#50565e`, a truss four such chords, and a ledge, floor stand or other mount nothing
outside Edit (a faint guide inside it, so it can still be picked); a thin tube is picked through an
invisible box round it.

**Neither moves by dragging it** on the orbit or eye camera: a drag that starts on a region or a bar
always turns the camera, and a click selects it. They move by their handles — the region's centre
puck (`RegionEditHandles`) and the bar's middle cube (`RiggingEndpointHandles`), beside the corner,
height, turn and end handles. On a section a *selected* object still drags by its body, and a drag
that starts on an unselected one pans (`useSectionPress`'s `onDragInstead`), so a stage-wide region
never stands between the operator and the view.

## Cameras and viewpoints

Session 1 of the stage-view plan gave the one scene five cameras and put the plan in the overview
panel. `Stage.dc.html` and `Positions.dc.html` are the layout authority; the record's §4 "One camera,
which forgets" and §5 "Both 2D surfaces lose to label density" are the why.

### Five cameras, one scene

`StageCameraRig` (in `Stage3D`'s canvas) mounts one of three rigs for the window's viewpoint:

- **Orbit** — a perspective camera under drei's `OrbitControls`, as before.
- **Eye** — a person standing somewhere. Drag turns the head, grabbing the scene as a panorama
  does; scroll narrows or widens the lens rather than walking. It starts where the orbit camera
  stands, looking where it looks — so *orbit there, then look around* is the gesture until session
  2's saved points and seats. Pitch stops at ±85°, the lens at 15–90°.
- **Plan · Front · Side** — orthographic sections under `OrbitControls` with rotation off. Their
  screen axes are exactly `lib/stageProjection.ts`'s `h` and `v` (`sectionHits.test.ts` pins all
  three), which is what lets the section edit layer work in the projection's metres: Plan has
  upstage at the top, Front has +X to the right, Side looks from +X (audience right, stage left) with
  the house on the left.

Four rules:

- **A section's camera stands on its section plane.** `orthoSection` (`stageCameras.ts`) puts the
  camera just outside the scene's bounds on its own side, with a near plane of a centimetre. What
  lies between the section and the camera is nothing by construction, and anything beyond the
  plane on the camera's side is clipped. The bounds are the stage, every rigging's reach, every
  region (hanging **down** from `centerZ`) and every placed fixture, so today nothing of the rig is
  cut. Session 3's venue is what the planes will cut through — the ceiling in plan, the balcony in
  front, the stage-left wall in side.
- **A section refits itself until the operator moves it** — to the canvas as it resizes, and to the
  rig as its lists arrive — and then it is theirs. *Frame* slides it **within** the plane, so framing
  never changes what is cut.
- **Each camera is a plain three.js object made the default through the store**, not drei's
  `<PerspectiveCamera makeDefault>`. drei's orthographic camera allocates a render target for a
  feature this never uses, and R3F sizes a default camera only on the *next* resize, so
  `useDefaultCamera` fits the frustum itself when it swaps one in.

The volumetric beam shader built each pixel's ray from `cameraPosition`, which fans out like a
perspective camera's. Under an orthographic camera the rays are parallel: the shader reads
`isOrthographic` (three's built-in uniform) and casts along the camera's forward axis from the
camera plane, so an ortho view draws the same haze as orbit, and `t >= 0` is the section's cut. The
beam shell's rim and the pixel strip's glow take the same flag: under ortho their view vector is the
constant view axis. The label layer needed nothing — it projects every anchor through whatever the default camera is.

### The viewpoint is the window's, and the pose is kept

`lib/stageViewpoint.ts` holds the viewpoint — a camera, `orbit | eye | plan | front | side`, or
since session 2 a saved view's uuid (below) — in **`sessionStorage`** — per tab, `lib/immersive.ts`'s reason: the hall screen sits on Front all night
while the desk screen orbits. It replaced the `stageViewMode` key in `localStorage`, one value per
profile. It rides `windows.viewOptions` as `viewpoint` under the new **Stage** entry in
`lib/windowViews.ts` (Stage is a window view but not a live view — no immersive), so the Screens row
draws a *Viewpoint* picker over the cameras, saved views and seats (a segment over the five cameras
until session 2) and sets it on another window. A Screens row's
*Copy link* carries `viewpoint=`, which the Stage route applies on arrival and strips. The toggle in
the route's header is the camera segment, and the **viewpoint picker** beside it lists the built-ins
and *Frame the selection*; session 2 added saved views and seats (below).

`lib/stageCameraPoses.ts` keeps the orbit pose (and the eye's, while on Eye) in `sessionStorage`,
written as the camera moves (at most every 150 ms, and once more on unmount) and read on mount — so
a route change, a reload and the context-loss *Restore* all land where the camera was left. Moving
*into* Eye from another camera forgets the eye's pose, so it seeds afresh from the orbit camera; a
remount already on Eye keeps it. The seed reads the orbit pose **noted in memory on every move**
(`noteOrbitPose`), not storage: the eye's seed is read in the render that mounts it, before the
orbit rig's unmount has flushed its pending write, so storage can be a move behind. An embedded camera (the Positions panel's plan) passes
`persistCamera={false}` and never touches the Stage view's.

### Saved views and seats (session 2)

A viewpoint can also be a **saved view** — a `stage_viewpoints` row, by its uuid, in the same
`viewpoint` key and the same `sessionStorage` slot. `isStageViewpoint` accepts a camera or a uuid, so
`applyStageViewOptions`, `consumeLaunchStageOptions` and the stored value all take one whether or not
this window has fetched the row yet (a reload, or a view another window names). A name that is
neither is still ignored. `components/stage3d/savedViewpoints.ts` is the pure half:

- **An `ORBIT` row lands the orbit camera** at its eye, circling its target; **an `EYE` row lands the
  eye** where it stands, facing its target, through its lens; **a `SEAT` row lands the eye at the
  seat's seated eye** (`lib/stageSeats.ts`, the backend's seat maths — 1.15 m up, 5 cm back), facing
  its target or the stage's centre line when it has none. So the three-way camera split of session 1
  holds: a saved view draws through the orbit or the eye rig, never a camera of its own.
- **Landing is once per pick, not per mount.** The rig is keyed by *camera*, and it lands a saved
  view when the window has not already landed on it — `lib/stageViewpoint.ts`'s **landed marker**
  (`stage.landedViewpoint`, per tab), which the rig writes once it has landed and a pick clears. So a
  remount (a route change, *Restore*, a reload) keeps wherever the operator has looked since, and
  picking the view again — even the one already current — lands it afresh. Two saved orbit views in
  a row move the mounted rig rather than remount it. Every landing moves the camera imperatively and
  invalidates.
- **Moving into Eye from a saved eye or seat view keeps the pose** — you are already standing there;
  from a saved orbit view, or any camera but the eye, it forgets it and seeds from the orbit camera,
  as session 1 did. Which camera a uuid draws through is noted from the rows
  (`noteSavedViewpointCameras`), falling back to the landed marker until they arrive.
- **A view that cannot be landed** — its seating deleted or reshaped so the seat is gone — is listed
  disabled (*seat gone*) in both pickers, and a window sitting on one keeps its camera where it was.

**The picker** (`StageViewpointPicker`, `Stage.dc.html` §4) lists the built-ins, then *Saved views*,
then *Seats*, *Frame the selection* and ***Save this view…***. Saving reads the live pose
(`readOrbitPose`, and `readEyePose`, which now prefers a pose noted in memory as the eye moves, for
the orbit's reason) and builds the row with `viewpointFromCamera`: the orbit saves an `ORBIT` view, the
eye an `EYE` view — or, sitting in a seat view, a `SEAT` view of the same seat with the head turned
where it now is, so it keeps following the seat. A section cannot be saved. The window moves onto the
new view, which is what announces it.

**Sit in a seat…** (session 3; the picker's item, or S — Escape cancels) arms a pick on the seating:
the seating mesh takes the pointer while armed, the seat under it is tinted and named (*Sit in row F,
seat 6*), and a click lands the Eye rig at that seat's seated eye facing the stage. Arming turns the
window's Seating layer on, since a pick needs seats to click. **An unsaved seat is a viewpoint of its
own, in the same key**: `seat:<seating uuid>:<seat id>` (`seatViewpointRef`, `parseSeatViewpointRef`),
in the same `sessionStorage` slot and announced as `viewpoint` like any other — no new top-level
announce key, which the desk's bare Json would drop. It lands through the same landed marker as a
saved view, the trigger reads *Row F, seat 6* with an *unsaved* caption, and the Screens row's picker
lists it as unsaved. *Save this view…* from there saves a `SEAT` row (`viewpointFromCamera` already
built one from a seat), and the window moves onto the new row. A seat whose seating has gone is
dropped once the scene has loaded, as a deleted saved view is.

**The Screens row's Viewpoint is a picker now** (`Screens.dc.html` §1): `lib/windowViews.ts`'s
`STAGE_VIEWPOINT_OPTION` is a `picker` kind, and the control is `StageViewpointRowPicker` — the Stage
view's own, handed to the sheet by `Layout` through `ScreensSheet`'s `controls` and loaded lazily, so
the sheet never imports the Stage view (it never learns the word) and nothing loads the scene queries
until a Stage row is on screen. It lists the cameras, the target project's saved views and its seats,
and writes the value as `windows.viewOptions {viewpoint}`.

### The scene, built by kind (session 3)

**Each element kind has a builder** in `components/stage3d/scene/builders/`, one file each, replacing
`StageSceneBoxes`. A builder is pure — an element in, **parts** out (`scene/sceneParts.ts`: boxes,
quads, cylinders, discs and pleated cloth in the element's own lighting frame, each with a finish and
whether it stops a beam) — so `builders.test.ts` pins each kind without a canvas, and one renderer
(`StageSceneElements.tsx`) turns parts into meshes on the surface shader. The group is placed at the
element's origin — its base, a platform's top, a flown piece's trim — and turned by its yaw.

- **Room**: inward-facing quads — floor, ceiling and four walls, leaving out the sides `omit` names —
  so a camera outside still sees in. The floor and ceiling take their own finishes.
- **Proscenium**: the opening centred across the wall above its sill — two piers, a sill and a head
  that stop beams — and a surround that does not.
- **Flat**: a wall with its openings cut from its stage-right end, a pier before each and a sill and
  head around it (a door has no sill); an arch is drawn square-topped.
- **Drape**: one pleated cloth, or for a `DRAW` operation two halves gathered to their sides by the
  `open` state (closed when unstated), each hanging from its own edge. A cyc defaults pale.
- **Platform**: the deck hangs **below** its Z, which is its top; a rail on the edge it names. A
  platform linked to a region draws its own deck all the same; the region draws no surface under it.
- **Seating**: no parts, only seats — `seatList` in `lib/stageSeats.ts`, the same seat maths the
  backend's `SeatingParams.seat` is mirrored from, so the seats drawn are exactly the seats a `SEAT`
  view can name. One `InstancedMesh` for the block; it raycasts only while a seat pick is armed.
- **Object**: a box, cylinder, shade or disc by its `shape`. **Flown** pieces stand at `trimM`.
  **Emissive** finishes glow at their colour.
- **`hidden` and `states.visible: false`** build nothing, for every kind.

**The View menu's Venue · Set · Seating layers** choose which elements a window draws — per window in
`sessionStorage` (`stage.sceneLayers`, `scene/sceneView.ts`), not announced; a seating element
follows **Seating** whatever its layer. Only the Stage route's canvas reads the scene (`showScene`);
the Positions plan does not, so the collapsed panel stays as cheap as it was.

**The ortho sections cut on purpose now.** The section plane stays **rig-derived** — Front cuts at
the rig's downstage edge, so the house and its seats in front of the plane are not drawn over the
stage — but the far plane reaches the drawn venue's bounds (`orthoSection`'s `beyond`,
`sceneElementBounds`), so a room's back wall, a balcony and the upstage flats stay in the picture
behind it. The plan section cuts the ceiling away by the same rule. Rooms face inward, so a section
camera outside the hall sees in through the side it cuts.

### Frame the selection (F)

F — bare, never from a field; ⇧F is full screen — and the picker's item bring the selection into
view in whatever way the current camera moves: the orbit pulls in along the direction it had until
the selection's sphere fills the lens (grandMA3's *Auto*), the eye turns its head, a section slides
within its plane and zooms to fit. What it frames is the Stage view's own selection
(`framingPoints.ts`: every lantern of a fixture, a region's middle, a rigging's position), and **the
desk selection when that is empty** — so a chip pressed in Positions, the busk band or the
programmer is framed by the Stage window that pressed nothing. A group target frames its members; a
cell's key is opaque on this side and frames nothing, and so does a patch the view does not draw
(`stageHidden`, infrastructure). The orbit's pull-in takes the narrower half of the lens — on a
portrait canvas the horizontal field is the tighter. O goes back to Orbit.

### Positions

`components/positions/PositionsPanel.tsx` replaced `StageOverviewPanel` and `StageMarker` behind the
header's stage toggle, on every route. The old panel placed every name by percentage in a 420 px box
with no decluttering; no labelling scheme fixes a true-scale plan of three bars a metre apart.

- **Rows are positions, upstage first**, the stage edge marked at Y = 0. `positionRows.ts` derives
  them on every render — a unit's row is its placement's rigging, the row's depth is where the
  rigging hangs, the unit's place is its world X — and stores nothing (`FU-BUSK-RIG-PLOT`'s rule).
  A paired dimmer is one chip per row with its count; a unit on no rigging goes in one
  *Free-standing* row at its units' mean depth; a rigging with nothing on it has no row.
- **A chip is the desk selection** (`setDeskSelection`): a tap selects that unit on every window,
  ⇧ or ⌘ toggles it, and a tap on the sole selected unit clears. The swatch is live colour at
  level; a unit with neither a dimmer nor a colour (a hazer, the Twin Shot) shows no level.
- **A group chip dims** the units outside it; every row keeps its place. The filter remembers the
  project it was set in, since group ids are the desk's and would match nothing in another.
- **The Plan tab is `Stage3D` on the plan section**, lazily imported (`PositionsPlan.tsx`) so
  three.js stays the Stage route's weight. A fixture clicked there sets the desk selection, and a
  click on the one fixture selected clears it, as a chip does; *Open in Stage* from the Plan tab
  opens the Stage view on Plan.
- **Closed costs nothing.** The tab and the group filter live above `CollapsiblePanel`; every query,
  channel subscription and the canvas unmount with the body.

## Rendering for `render_view`

Session 4 of the stage-view plan gave Claude eyes (D4): the MCP tool `render_view` answers a PNG of
a stage viewpoint, and since the desk cannot draw WebGL, **a desk window draws it**. The desk half —
which window, the one-shot request, the bound upload, the named errors — is lighting7
`docs/mcp-engineering.md` §"`render_view`"; this is the window's half.

**Any window, on any route.** `StageRenderHost` (`components/stageRender/`) is mounted once in
`Layout`, so a window on Busk or the Programmer is asked as readily as one on the Stage view. It
subscribes to `stageRender.request` (`api/stageRenderApi.ts`) — a frame the desk sends to the one
socket it chose, so a frame that arrives is this window's — and draws nothing of its own. While a
request is in hand it mounts `stage3d/render/StageRenderJob.tsx`, **lazily**: the job imports
`Stage3D` and with it three.js, which therefore still loads only when the Stage view, the Positions
plan or a render first needs it, never with the app shell. The outcome goes back over REST
(`store/stageRenders.ts`) with the request's token; both endpoints are silent in
`errorToastMiddleware`, because a refused answer is the model's to hear and the operator at this
window asked for nothing. One render at a time: a request arriving mid-render is answered *busy*.

**It is the Stage view's own scene, not a copy.** The job mounts `Stage3D` itself, with a `capture`
prop, and every other prop means what it does on screen:

- **The camera** comes from `resolveViewpoint` (`savedViewpoints.ts`): a camera draws through
  itself; a saved view and an unsaved seat land through `resolveSavedViewpoint` /
  `resolveSeatViewpoint`, the maths the picker lands with. `orbit` and `eye` are the poses a fresh
  window opens on (`defaultOrbitPose`, and the eye seeded from it), not any window's current pose.
- **The scene** is `showScene` with the default layers (Venue, Set and Seating on, Haze on the
  Stage), the
  default view flags **minus labels** — the label layer is DOM over the canvas, so a frame read off
  the canvas never had them — and the machine's light budget, read, not written.
- **The light** comes through the same `StageChannelSourceProvider` as the Stage view, now with a
  `source` it can be handed (the request's, not this window's) and an `onSettled` that says when a
  derived source holds what it will hold: the programmer source once it is built over the fixture
  list, Next GO once the cue on deck is known and its preview has answered
  (`useNextGoSourceState`). The Stage view draws the wire for the frame it takes to build one; a
  one-frame render waits it out.

**The capture canvas** (`CaptureCanvas.tsx`) is what `Stage3D` renders into instead of R3F's
`<Canvas>`: an R3F root (`createRoot`) on a **detached** `<canvas>` — never put on the page, so the
window's own canvas, layout and pointer are untouched — configured with its size, `dpr: 1` (the size
asked for is the picture's), `frameloop: 'never'` and a preserved drawing buffer, and drawn with
`advance` for that root alone. Two reasons it is not `<Canvas>`: `<Canvas>` sizes itself from a
`ResizeObserver` and draws on `requestAnimationFrame`, and a background tab delivers neither — this
one renders in a hidden tab, yielding between frames by `MessageChannel` rather than timers, which a
hidden tab clamps. And an on-demand frameloop would keep drawing while a live show's channels move;
this one draws exactly the frames it is told to. `flat`, no tone mapping and `antialias` are the
view's; the clear colour is the view's CSS background, which a PNG has to have drawn. Two things
`<Canvas>` does that a bare root does not, both found by rendering from a window that had never
shown the Stage view: it registers the THREE catalogue (`extend(THREE)`) as it mounts, without which
`<ambientLight>` throws in a window that has drawn no canvas yet, so the capture canvas calls it too;
and it connects an event system, so on this root `events.connected` is `false` — anything reading
it must fall back on falsy, not on `??` (the Eye rig did). A throw inside the root is caught there
(`SceneBoundary`) and ends the render with its reason at once, since nothing above a detached
canvas would hear it. **Contexts do not cross into an R3F root by themselves**: `<Canvas>` bridges every one, and the capture canvas
bridges the one the scene reads from outside it — the vis source. A scene component that starts
reading another outside context must be bridged there too, or a render silently draws its default.

**Read-only in every sense.** No DMX and no programmer write — the view in view mode writes neither.
**No travel** (§"Travel time"): `Stage3D` hands every `FixtureModel` `travel={false}` under a capture,
so a head mid-swing on the desk's own screens is drawn where the DMX says it is going.
None of this window's facts move: `StageCameraRig`'s `oneShot` lands the render's viewpoint
**unconditionally** and records nothing — no pose in `sessionStorage`, no landed marker (it was
`persist` that marked, and a render's rig is never persisted); the layers and the source are the
render's own, not read from or written to this window's stores. The job's container sits far
offscreen, `aria-hidden` and `inert`, holding only the scene's DOM overlays, all empty.

**When it draws.** The job fetches afresh every read the scene draws from — the project, saved
views, scene elements, patches, regions, riggings, fixtures and types — with `forceRefetch`, so a
`set_scene` a moment before is in the picture and an entry that errored earlier cannot fail this
render before its own fetch has run. Then it resolves the viewpoint once (a refetch mid-render moves
the cache `Stage3D` draws from, never the camera, and never unmounts the canvas), mounts `Stage3D`,
and waits for the capture canvas to report the scene mounted — a `Suspense` boundary around it — and
for the source to settle. Then four frames a task apart (the
emitters lay out and pack the light table; the next draws through the camera the viewpoint swapped
in), and `toBlob`. It gives up three seconds before the desk would, naming what never
arrived, so Claude hears *waiting for the scene to mount* rather than a bare timeout.

**Disposal.** The host unmounts the job on its outcome, before the upload goes; R3F's `unmount`
disposes the renderer and forces the context's loss — session 0's memory rule, and a second
context on an iPad is the expensive case. A context lost mid-render (`ContextLossWatcher`) ends the
render with that reason: a render has no *Restore* to offer.

Tests: `api/stageRenderApi.test.ts` (the frame), `savedViewpoints.test.ts` (`resolveViewpoint`),
`stageRender/StageRenderHost.test.tsx` (a request mounts one render, its outcome goes back bound to
its id and token, busy, a throwing render, and nothing toasted) and
`stage3d/render/StageRenderJob.test.tsx` (the props `Stage3D` is handed, drawing once the scene
reports in, giving up with a reason, and this window's viewpoint and landed marker untouched).

## Editing on the sections

Session 5 of the stage-view plan (D1) moved editing on Plan, Front and Side onto the 3D scene and
deleted the SVG plot (`components/stage2d/`, about 3,100 lines). There is one renderer now: the
scene on a section is drawn by `Stage3D`, and editing there is **`edit/SectionEditLayer.tsx`**, DOM
over the canvas as the label layer is. `svgPlotRetired.test.ts` keeps the plot gone.

**The layer owns the pointer while the view edits on a section** (`sectionEditing` in `Stage3D`:
Edit on, a section camera, not a render, no seat pick armed). It hit-tests, drags, marquees, pans and
zooms in the section's own metres — `lib/stageProjection.ts`'s `{h, v}`, v screen-down — and the
scene's own gizmo, 3D handles, placement plane and grid stand down, so R3F and `OrbitControls` never
see a press there. Three pieces make that possible:

- **The section camera reports where it looks** (`StageCameraRig`'s `onSectionView`): after every
  frame drawn, its centre in the section's metres, its zoom (pixels a metre) and the canvas's size,
  into a small store (`edit/sectionView.ts`). A section's screen axes are the projection's `h` and
  `v` exactly, so nothing more is needed; `sectionHits.test.ts` pins that for all three cameras.
  The canvas draws on demand, so **the rig asks for a frame when a listener arrives** — Edit turned
  on over a still stage draws nothing new, and without it the layer would wait, blank, for a fader —
  and `Stage3D` clears the store when editing stops or the section changes, so a layer never
  hit-tests against another camera's view.
- **A pointer is resolved from the canvas's live centre** (`offsetToSection`), never its top-left
  and the reported size. The camera keeps its centre and zoom as the canvas resizes, but the report
  lags a frame or two — and arming `+ Scenery` folds the side panel away, widening the canvas at the
  very moment of the click that places. Resolving against the reported width put that click 180 px
  off; the SVG's viewBox is `xMidYMid meet` for the same reason, so the chrome stays on the scene
  through the lag.
- **The camera takes pan, zoom and fit from the layer** (`StageCameraHandle.section`): a pan slides
  the camera and its target within the section plane, a zoom keeps the point under the pointer
  still, and both mark the section moved so it stops refitting — the rule a section's own controls
  already kept. Neither moves along the view axis, so editing never changes what a section cuts.

**What a press lands on** (`edit/sectionHits.ts`, pure and node-tested): a fixture within 12 px (a
lantern pressing as its fixture; a variable-length run along its whole span), over a bar within 7 px
(or half its thickness), over the **areas** — regions and scenery — where the **smallest outline
under the pointer wins**, so a prop on a deck is the prop and the deck beside it is the deck; a tie
goes to the region. A `ROOM` is never pressed on the canvas (it is the whole hall, and a press
anywhere would take it); it is picked from the editor's list. The plot used paint order for the
areas, which let a region hide everything standing on it.

**A drag settles exactly once, however it ends** (`useSectionDrag`). Its moves write the RTK cache
and only the settle writes the desk or rolls back, so the layer unmounting mid-drag (a section
left, Edit turned off, the context lost) settles it where it had got to, and so does a second
pointer starting a drag before the first lifts. *Sit in a seat…* is not offered while editing on a
section (`canSit`), for the same reason the SVG plot withheld it: the pick needs the pointer the
layer holds.

**Scenery on a section** is selected and dragged like a region — body drag once selected, guides and
grid snap, out-of-plane preserved — and written per frame to the RTK cache (`writeElementPlacement`)
so the builders redraw it as it moves — `sceneBuilds` keeps each element's build while the list
holds the same object (`SceneBuildCache`), so only the dragged piece is rebuilt — then `PUT` once on
release and rolled back if refused. A flown
piece with a trim slides across only in an elevation, since its Z is not where it is drawn. The
layer draws the selected element's outline, which the scene has no highlight for. Resize and yaw
handles for scenery are not built; the element form takes the numbers.

**Edit chrome never reaches a render.** The layer is `Stage3D`'s DOM outside the canvas, mounted only
while `capture` is null; the section camera's report is only wired while editing on screen; a
render's root has no event system (`events.connected` is `false` there), and nothing edit-only reads
it. `render_view` of a section drawn from a window that is editing on one draws the scene and no
grid, handles or guides.

### Parity with the SVG plot

The SVG view's gestures as they stood at the start of session 5 (plan §10), and where each lives now.
Route-level gestures were always renderer-blind; they are listed because the claim is the whole
list.

| SVG plot (`Stage2DView` and friends) | On the sections now | Plan | Front / Side |
|---|---|---|---|
| Background drag pans (4 px threshold), middle button too | the layer, through `section.panBy` | ✓ | ✓ |
| Wheel zooms about the pointer | a native non-passive listener, `section.zoomAt` | ✓ | ✓ |
| HUD: axis legend, cursor read-out, snap step, zoom − / + / fit, edge-on notice | `edit/SectionHud.tsx` | ✓ | ✓ |
| Snap-step grid (minor lines dropped under 6 px, major every 5, datum axes) | `edit/SectionGrid.tsx` over the canvas | ✓ | ✓ |
| Click selects; ⇧ adds, ⌘ toggles; click on nothing clears | the layer, `selectionIntentFor` | ✓ | ✓ |
| ⇧ / ⌘ + drag on empty space marquees fixtures (lanterns select their fixture; ⌘ adds) | `marqueeHits`, `sel.selectMany` | ✓ | ✓ |
| Drag a selected free fixture: in-plane, out-of-plane axis kept, guides then grid | `fixtureDrag` | ✓ | ✓ |
| Drop a free fixture on a bar within 12 px: hung on it, the bar lit green | `riggingUnderPoint`, `dropOntoRigging` | ✓ | ✓ |
| Drag a hung fixture: slides along its bar, snapped in bar metres, clamped to its ends; refused edge-on | `localXAlongBar` | ✓ | ✓ |
| A lantern is never dragged | `dragFor` | ✓ | ✓ |
| Drag a selected region / bar (rigid; pitch kept); bar refused edge-on | `regionDrag`, `riggingDrag` | ✓ | ✓ |
| Region corner resize (opposite corner pinned) and yaw handles, 15° snap | `RegionSectionHandles` | ✓ | — |
| Region top / floor height handles (top keeps the floor), vertical only | `RegionSectionHandles` | — | ✓ |
| Bar endpoint handles (other end pinned); hidden edge-on | `RiggingSectionHandles` | ✓ | ✓ |
| Alignment guides drawn while snapped | `AlignmentGuides` | ✓ | ✓ |
| Armed `+ Region` / `+ Rigging` / tray click places, snapped, the unseen axis from `placementDefault` | the layer's click | ✓ | ✓ |
| Unplaced tray: arm (⇧/⌘ extends), Select all, Hang all on truss…, fanned along X by the step | `edit/UnplacedTray.tsx`, on the sections | ✓ | ✓ |
| Arrow nudge by the step (⇧ ×10), coalesced, along a bar for a hung fixture | `edit/useStageNudge.ts`, the section's axes | ✓ | ✓ |
| ⌫ removes from stage, ⌘D duplicates, Esc cancels placing | the route | ✓ | ✓ |
| Bulk panel for a multi-selection (align, distribute, hang, set depth, mirror) | `edit/StageBulkPanel.tsx` | ✓ | ✓ |
| ⇧ held suspends snapping; the Snap toggle and step | `edit/useSnapGrid.ts` | ✓ | ✓ |
| Shortcut list | `edit/StageShortcutsPopover.tsx` | ✓ | ✓ |
| View flags hide regions, rigging, fixtures (and their presses) | the scene, and `SectionScene` | ✓ | ✓ |

Not carried over, on purpose: the plot's own fixture shapes, colours and label declutter (the scene
and the label layer draw those), and its stage-envelope rectangle (the stage's floor and walls are it).
Session 5 also made **a click on nothing clear the selection in view mode** too, on every camera
(`handlePointerMissed`), which the record listed as noticed on the way.

### `+ Scenery` and the element form

The Edit header's `+ Scenery` (`Edit.dc.html` §1) lists the seven kinds plus tabs and a flown piece
(`edit/sceneryKinds.ts`): each is armed and placed with a click like a region, with sizes to start
from, and opens **`EditSceneElementForm`** (`components/stage/`) in the `StageEditorPanel`, beside
the region's and the rigging's forms. The editor's list gains a *Scenery* section, the only way to
pick a room. The form draws the kind's `params` fields and saves one partial `PUT`, `params` whole
(`elementDraft.ts`); it checks nothing but a name. The desk checks it, through the
`validateStageElement` `set_scene` uses: a 400 lists every problem, and `elementProblems.ts` files
each beside the field it leads with (`params.openings[1]` under that opening), or the first it
names, or at the top — none is dropped. A reshaped seating that seat views sit in is a 409 the form
asks about (*Save anyway* forces). *Moves with* is read-only and empty until session 8. The three
element endpoints are in `SILENT_ENDPOINTS`, so the route's placement and drag toast their own
failures. The design record's two builders (*Proscenium hall from measurements…*, *Ask Claude*) are
not built: the template is `set_scene`'s over MCP.

## Fixture bodies

Session 6 of the stage-view plan replaced the eight kind bodies (`stage3d/fixtureBodies/`, deleted)
with parametric **archetypes** built from parts, gave every coloured element its own lens and beam,
started every beam at its **aperture**, and shaped pool and haze with one **beam mask**. The design
is the record's §"Fixture bodies and the lantern library", items 1–3, 8, 9, 11 and 12; the
prototype proved the maths and is not the code.

### The archetype: the lantern, the type's body, then the words

`bodies/archetype.ts` is pure and node-tested: a patch, its fixture and its type in, a `BodySpec`
out — the archetype, its size, its **cells**, whether it emits, its field angle and its edge
softness. `emitterNeeds.ts`'s `bodySpecOf` is the one call, read by `Stage3D` (to size the emitters
and the body instances) and by `FixtureModel` (to draw), so the two cannot disagree about a cell.

**The archetype is chosen in three steps, first answer wins** (session 7 moved the first two in
front of what used to be the whole rule):

1. **The lantern** — for a type that takes one (`acceptsLantern`, the generic dimmer), the lantern
   the patch names, else its kind's default, from the library (`GET /lanterns`, read through
   `useLanternIndex` and handed to `bodySpecOf` as an index). The lantern gives the archetype, its
   dimensions, its lens, its field (zoomed where the focus sets one) and its oval, and the patch
   takes its family's **kind** (`LANTERN_FAMILY_KIND`: a PC is a `FRESNEL`, a flood and a cyc a
   `WASH`, a downlight `GENERIC`) — the desk derives `kindOverride` from it at the write boundary,
   and reads the lantern first in `RigBriefing.isMovingHead`, so the two sides agree. A lantern is
   never a mover. See `docs/fixtures-engineering.md` §"Lanterns and focus".
2. **The type's declared `body`** (`FixtureTypeInfo.body`, from `@FixtureType(body = …)`): an
   archetype, a mover's head and a lens diameter. A type that declares none answers `null`.
3. **The words, as they were** — the fallback for every type that says nothing:

- **A mover** is anything with a tilt axis, or of kind `MOVING_HEAD` / `SCANNER` — so a Source Four
  Revolution, a `PROFILE` that tilts, is a mover with a profile head. The head is a spot, a wash or a
  profile by the words in the type key and model (`spot`, `beam`, `mac-250`, `wash`), else by the
  type's beam edge; a mover with several coloured elements is a bar of heads (the Slender Beam Bar
  Quad — its heads tilt together until `FU-STAGE-INDEPENDENT-HEADS`).
- **A Twin Shot** is the cannon.
- **Otherwise the kind** — the patch's `kindOverride` over the type's: `PROFILE` a profile barrel (a
  box profile where the type names a Cantata, Prelude or Harmony — the library's are box profiles by
  their own archetype), `FRESNEL` a fresnel (a PC is the same archetype without the barn doors,
  which only a PC lantern from the library tells apart),
  `PAR` a can, `WASH` a flood, `STRIP` a batten — a strip of **tape** where the type takes a
  per-install length (`acceptsLength`) — `BLINDER` a blinder, `LASER` and `EFFECT` an effect box,
  `GENERIC` a house downlight (the prototype's default for a generic dimmer).

The field angle is one precedence for every fixture (fixture-optics plan D3, `resolveBeamDeg`): a
**zoom channel** that declares its angles, then the patch's `beamAngleDeg`, then the **lantern's**
(at the focus's zoom where it has one) or the **type's fixed lens** (`@FixtureType.fieldDeg` — the
Fusion 100's 10°, the Scantastic's 11°), then the **family's** (`FIELD_DEG`: a profile 26°, a fresnel
45°, a PAR 32°, a spot 16°, a wash 25°, a batten 30°, a blinder 60°…) — it was 30° for everything.
`resolveZoomDeg` reads a zoom in either form: a ZOOM slider's `degMin`/`degMax`, or a **stepped
zoom** — a ZOOM setting whose band carries `zoomDeg` (the Robe ColorSpot 575's 15°, 18° and 22°,
each again with focus correction), every DMX value in a band drawing that band's angle and never one
between two steps (D2). A zoom that declares neither answers nothing and falls through.
`findZoomProperty` finds either form. Which bodies emit: a batten and a blinder always (their
types have no beam angle to set, so `acceptsBeamAngle` could not say), tape and the cannon never,
everything else as `acceptsBeamAngle` said before.

### Parts, instanced; three levels of detail

`bodies/bodyGeometry.ts` builds each archetype from parts — the **base** (a mover's), the **yoke**
that pans, the **head** that tilts with its accessories (shutter handles, barn doors, a colour
frame), a **lens** per cell and a **hanger** — in two levels, `full` and `simple`, shared by spec,
mount and level and **reference-counted by the canvases that draw them**: a key no canvas holds is
disposed, so every length a strip of tape is dragged through does not stay behind (a new layout
takes its hold before the old one lets go, or a key both share would be disposed mid-swap). The
numbers a body is placed by — its pivot, a standing lantern's lift, where a hanger attaches, the hit
box — are `bodyFrames`, which builds no geometry. `bodies/StageBodies.tsx` instances them **per part across the
rig**: one `InstancedMesh` per (geometry, level, part), one for every disc lens and one for every
segment lens in the rig, one for the hangers and one for the billboards. Sized by a `BodyLayout`
(each slot's spec key and mount) built beside the emitter layout in the same slot order, and rebuilt
only when its signature changes, as the emitters are — a drag, a pan or a tilt rebuilds nothing.

- **The fixture is still a node rig.** `FixtureModel` keeps its placement group (which
  TransformControls drags), a mount, a yoke and a head as empty groups, and its body director copies
  their world matrices onto the instanced parts every frame it renders, at the level its size on
  screen calls for (`bodyShownFor`: under 44 px the simple mesh, under 9 px a billboard glyph — a
  dark dot in its lens colour). It runs after the beam director, so a head's tilt is this frame's.
  A write that stores what the instance buffer already holds is dropped, so a still rig flags
  nothing for upload however often the canvas draws.
- **The pointer presses an invisible hit proxy** — a box round the body at rest, `visible: false`
  on its material so it still raycasts — so the click, the hover and the gizmo keep their plumbing.
  The instanced meshes take no pointer (`NO_RAYCAST`), and the section edit layer's hit radii never
  touched meshes.
- **Housings are lit by the surface shader**, tinted per instance (the selection's blue), so a
  beam that crosses a lantern lights it. That surfaced a bug from session 3: three defines
  `USE_INSTANCING_COLOR` in the vertex stage only (the fragment stage gets `USE_COLOR`), so the
  receiver never tinted any instance — the seat pick's hover tint included. It now tests both.

**A static lantern keeps the mover's rig** (item 3). Its yoke turns by its yaw about the vertical and
its head by its pitch, then roll, inside it, then a fixed quarter-turn that lays the barrel level
(`staticHeadQuaternion`) — so the yoke hangs or stands plumb whatever the lantern is focused at.

**Pitch 0 is level, and +pitch aims down**, as `docs/fixtures-engineering.md` defines
`base_pitch_deg` and the MCP schema states it (yaw 0 facing the audience, +yaw towards audience
right). Until session 6 the view drew a static lantern 90° of pitch off: pitch 0 straight down, and
+pitch tilting it *upstage* at yaw 0 — while the rigs authored through the schema, the Commemoration
Hall's among them, were entered level-is-zero (its ADV2 fronts at pitch 10–14, its house lights at
90, its Liteobars at 60). The quarter-turn is about the body's own X, the long axis, so
`longAxisLighting` (`lib/fixtureLength.ts`) is unchanged and roll still stands a strip on end; roll now
turns a static lantern about its own beam. Movers are untouched — their mount is their base
orientation, 0 standing and 180 hung, which `FixtureAim` solves from. `FixtureModel.test.tsx` pins the
documented directions, the long axis and the roll.

### Mounts: hung or standing

`bodies/mount.ts` (item 11): a fixture on a `LEDGE` or `FLOOR_STAND` **stands**, on anything else it
hangs, and on no rigging it hangs from nothing. The kinds are the desk's `STANDING_RIGGING_KINDS`,
which `describe_rig` reads to say a unit stands on its rigging and to flag a moving head whose base
orientation disagrees with it; `src/test/resources/stage/standingRiggingKinds.json` pins the two
lists (`mount.test.ts`, `StandingRiggingKindsTest`).

**The kind decides how a body is carried, never which way it points.** A moving head's mount is
already its `basePitchDeg` — 0 stands, 180 hangs — and `FixtureAim` solves from that, so turning a
mover over by its rigging's kind would draw a beam `aim_fixtures` does not aim. So: a standing static
lantern's yoke runs down to a plate, its pivot lifted by the yoke's height so the plate rests on the
ledge; a hung one's runs up to a clamp and a **hanger** up to the bar, drawn when the bar is more
than 2 cm above it. A mover hangs from its base only while its base is up (`basePitchDeg` about 180);
one at 0 on a bar stands on top of it. The Commemoration Hall's balcony Revolutions draw and aim
hung under a pipe until P5 makes the balcony a ledge and sets them to 0 — which `describe_rig` now
says.

### Cells

A body's **cells** (item 9) are its apertures, in the head's frame: one lens for a lantern or a
mover, and for a batten, a blinder or a bar of heads one per element that has a colour or a level of
its own — a colour property, a colour-wheel setting, a white or amber slider, or a dimmer (the
2-cell blinder's cells are warm and cold white sliders). The Kam Liteobar 252 is three segments, as
its type declares (`KamLiteobar252Fixture.kt:117`), not twelve. Cells without elements share the
fixture's colour. At most `MAX_CELLS` (16).

- **Every cell is its own lens, level, beam and light.** `CellColourSync` (in `FixtureModel`) reads
  each cell's element — its colour, its own dimmer, and the fixture's master — paints its lens and
  writes its beam's colour and opacity; a body of one cell keeps `ColourSync`, the fixture's own
  dispatch. Cells are 3D only: the 2D dispatch answers one colour a fixture, folding a pixel bar's
  elements into one in its multi-element arm. `ColourSync` lost that arm (and `MultiPixelColourSync`)
  with session 6, since a bar is drawn as cells; its single-colour arms still mirror the 2D ones,
  which `colourDispatchParity.test.tsx` pins.
- **Lights on the surfaces are capped at four a fixture** (`MAX_LIGHTS_PER_FIXTURE`), each averaging
  a contiguous run of cells (`lightRuns`) — its aperture the run's span, its colour × level their
  mean — so a 12-pixel bar takes four of the surface shader's lights, not twelve. The air keeps a
  beam per cell.

### A beam leaves its aperture

Item 8: a beam leaves the **aperture** — the lens, or a cell's face — at the aperture's own size and
shape, and spreads at the field angle, so its **apex** sits `aperture radius / tan(half-field)`
behind it (`apexDistanceM`). For a 19° Source Four's 170 mm lens that is half a metre back — the
lamp — and the cone passes the lens at the lens's width; for a wide LED face it is far behind, so a
batten segment or a wash leaves as a column. It replaced the lens-point origin (`lensRef ?? head ??
group`).

- **The volume is a frustum**: the hull is laid from the apex (`composeBeamHull`, built from the
  head's own axes so a segment's rectangle lines up with the head), and the march runs from the
  aperture (`near`) to where the beam lands. A disc throws a cone frustum; a **segment** a
  rectangular one, whose chord is four half-spaces through the apex, each linear in the ray.
- **The light on the surfaces uses the same apex** for its cone test and measures its focus from
  the aperture; its reach is cast from the aperture.
- **Edge softness is data**: the family's (`SOFTNESS` — profiles and spots hard, everything else
  soft), moved towards soft by a **frost** channel (`resolveSoftness`); a focus channel lifts the
  family's cap, sharpening the edge fully at its focal distance and softening it by the blur away
  from it (`resolveEdgeHardness`, §"Focus"). `FixtureTypeInfo.beamEdge` still only picks a mover's
  head.
- **A DMX iris is drawn** (`resolveIris`: the channel runs open → closed, down to 12 % of the field).
- **Iris and frost are read over their proportional band** (`proportionalBand`): a slider declaring
  `activeMin` / `activeMax` — the Robe's iris and frost, 1–179, with closed, pulse and ramp bands
  above — is proportional only across that band, and **holds its end value** outside it, so an effect
  band draws as the band's last value (the iris at its smallest, full frost) rather than as more of
  the same; a slider declaring none runs over its own min..max.

### One beam mask, and every beam marched

`beamMask.ts` is the cross-section both the surfaces and the haze shape a beam by — the field circle
or a segment's rectangle, the iris, the softness, the focus blur — in the head's frame, with the field edge at 1;
the shutter blades are arguments in the same frame (§"The lantern's focus: the cut, the gate and
the oval"). The GLSL and a TypeScript twin are written from
one set of constants and the twin is pinned by `beamMask.test.ts`.

**Every beam in the air is raymarched** now, round or rectangular: the hollow cone shell an open beam
used to be could not show a soft edge, an iris or a shutter cut, so it went, with `makeConeMaterial`
and the shell's buffers. Three things keep the march honest:

- **The air is the prototype's.** Each sample's density is `(1 − VOL_AXIAL_FADE · t) /
  (VOL_SPREAD_NEAR + VOL_SPREAD · r)` — densest by the lamp, thinning along the throw `t` and as the
  beam's radius `r` grows — and the integral, times the beam's colour × level and the haze, is rolled
  off by `1 − exp(−VOL_GAIN · ·)` and encoded, added at full alpha. The chord is measured as the
  prototype measured it, in the cone's frame scaled by its end radius: metres side-on, shortened by
  `tan(half field)` down the axis, so a beam seen end-on stays bounded. No beam has a ceiling of its
  own any more (`CONE_SCALE` is 1); two crossing add.
- **The hull is depth-tested again**, face by face: its front face while the view ray starts outside
  the beam, so the stalls, a pros wall or a flat in front of a beam hide it as they hid the shell,
  and its back face while the ray starts inside (the camera in a beam, or a section's plane cutting
  one) — the prototype's rule. With `depthTest` off, as the gobo volume had it, every beam drew over
  the seats in front of it.

**Budget.** The march is the costliest thing per pixel and the haze governor is its only brake: 12
steps (8 above DPR 1), stepped down to a quarter before the frame rate gives. Measured once, on
2026-09-30, in the desktop app's Chromium pane on the operator's Mac — **not Safari, and not an
iPad**, which §10 of the plan still asks for — on the Commemoration Hall at a full look with haze
on (every dimmer up but the house lights, 58 of 58 lights packed, a 1606 × 2236 px canvas at the 1.5
DPR cap): an orbit drag ran at a median of 17–21 ms a frame and the governor held tier 0. So the
sizes stay as they were — 12 steps, a 64-light default budget — until the Safari and iPad passes.
`data-lights` and `data-haze-tier` on the container are what to read.

### Focus

Fixture-optics plan session 1 ("focus that reads"; D9, D11). The symptom was a Source Four
Revolution on the Commemoration Hall's balcony whose focus reached the back wall, 24 m away, but
could not be seen to: every focal distance past about 4 m drew the same edge on the wall.

- **The focal distance.** A FOCUS slider that declares a range (`@FixtureProperty(focusNearM =,
  focusFarM =)`) focuses at one distance from the aperture wherever the head points, DMX linear in
  1 / distance between the ends (`resolveDeclaredFocusDistance`; the derivation is in §"Light lands
  through one surface shader"). On 2–40 m, 10–40 m is the top 16 % of the fader and one DMX step is
  about a metre at 24 m. A slider with no range racks over the throw instead.
- **The blur is a relative error times a depth of field** (`focusBlur` in `beamMask.ts`, the GLSL
  and its twin together): `dof · |f − d| / f` field radii at `d` from the aperture. The same relative
  error is the same blur at 4 m and at 24 m.
- **`d` is measured along the beam's axis** (stage-light plan D3; `beamFocusBlur`, which the surfaces
  and the haze both call): a lens's focal plane is a plane square to its axis, and *Focus here*
  solves for the axial distance to where the beam lands. On the slant, a profile focused exactly on a
  square wall still blurred at its rim by `dof · (1/cos θ − 1)`: 0.31 field radii on a 50° profile,
  whose edge was then eleven times its focused width. `beamMask.test.ts` pins the rim sharp. `dof` is the type's `depthOfField`
  (`@FixtureType(depthOfField =)`, `FixtureTypeInfo.depthOfField`, `BodySpec.depthOfField`), else its
  family's (`DEPTH_OF_FIELD` in `bodies/archetype.ts`: 3 for a profile, 2.5 for a spot). It used to
  be a lens's blur circle, `2a·|1 − d/f|` over the field radius `a·(near + d)/near`, which scaled
  with a lens radius the Revolution never declared and on a long throw stayed under the edge's hard
  limit across the whole far end of the range.
- **The cap lifts at the focal plane** (`resolveEdgeHardness` in `beamOptics.ts`). Without a focus
  channel the edge is the family's softness with frost folded in, as before. With one, the family's
  softness — which stood in for a defocus nobody modelled — is dropped, and only frost caps the edge.
  So on the plane an unfrosted beam is as hard as the mask draws (`mover:profile` was held at 0.88),
  and a frosted one stays soft even there.
- **The blur spreads an edge both ways** (stage-light plan D4). `beamMask` takes it as its own
  argument beside the softness: a penumbra as wide as the blur, **centred** on the field edge, the
  iris and the blades — all three sit in the gate the lens images — so the half-brightness point stays
  on the edge at every blur and a defocused pool keeps its size and feathers outward. `soft` keeps the
  inward roll-off for frost and the family's softness, which is what they are. Until this the blur was
  folded into the softness (`beamHardness`, gone), which rolled the edge off inward only: a soft pool
  shrank by up to 28 %, and in haze a beam looked fattest at its focal plane. Each half of the
  penumbra is capped at `FOCUS_SPREAD_MAX` (0.3 field radii, an estimate for the focus scene to judge),
  and a head with a focus channel reaches that much past its field: its light's cone bound
  (`writeLightRow`'s `cosBound`, texel 1), its haze hull and the march's cone (`composeBeamHull`, the
  volume shader's `tanBound`), and its region cull (`regionShadowMask`). A head without one keeps its
  field's bounds exactly. In numbers — the edge's width from 90 % to 10 %, in field radii — against
  the old inward roll-off, a Revolution on a 24 m wall: the same 0.018 focused on it, about 0.10 one
  DMX step off where it was 0.06, and 0.22–0.28 at ±3 m as before.
- **The depth of field rides texel 0** of the light table, packed with the focal distance
  (`packFocus` in `scene/lightTable.ts`: centimetres in the low 15 bits, twentieths of `dof` above, 24
  bits exact in a float32; `unpackFocus` in the surface shader), since all six texels were full. The
  haze carries it unpacked, in `aBeamShape.w`, its one free slot.
- **The constants are judged, not measured** (D15), and each says so with an `// Estimate:` comment.
  The profile family's 3 was tuned by eye in Chromium against a Revolution throwing 24 m at a wall:
  sharp with focus on the wall, a little soft one DMX step either side, clearly soft 3 m either side
  (`archetype.test.ts` pins those three in numbers). `?profileHarness=focus` (`profileHarness.ts`'s
  focus scene) builds that wall: three Revolutions on separate focus channels to write 243 / 246 / 249
  to, viewed on Front. The rig check is `FU-MANUAL-S4REV-OPTICS` step 8.
- **Locate parks a declared range at its middle distance** (lighting7's `LocateValueResolver`): 21 m
  on the Revolution's 2–40 m, solved back to DMX, where mid-DMX was 3.8 m. An undeclared focus keeps
  mid-DMX.
- **Focus here** (D11). A DMX fixture whose focus declares a range gets a *Focus here* button on the
  Focus tab (`StageFocusPanel`), on the live project only. It sends where the beam lands in the view
  to `POST /programmer/focus` (lighting7 `docs/fixtures-engineering.md` §"Focusing a head on a
  point"), which solves the focus channel for the distance from the head's lens to that point and
  writes it into the programmer; this side never solves, the `templateIntent.ts` rule, and draws the
  answer's skip by name.
  - The landing is the beam director's own cast along the beam's axis from its first aperture
    (`landedPoints.ts`), lit or dark — a head is focused before it is brought up as often as after.
    Only the selected fixture on an on-screen canvas records one (`FixtureModel`'s `reportLanding`,
    never a `render_view` capture), kept per reporting canvas and forgotten when it stops
    reporting, so a deselected head never answers with a point from before it was re-aimed. A
    module map the panel reads at the press. A beam that lands on nothing sends nothing and says so.
  - The desk measures from the **lens**, so it carries the view's mover proportions (`MoverLens`):
    the pivot `0.6 × height` up the body (`bodyGeometry.ts`'s `moverSize`) and the lens half the
    head's length beyond it (the mover cell in `bodies/archetype.ts`). Change them together: the
    shared vector `src/test/resources/stage/focusInverse.fixture.json` pins both the proportions
    (`heads`, read by `archetype.test.ts`) and the inverse (`cases`, read by `beamOptics.test.ts`),
    and lighting7's `FixtureFocusTest` reads the same file.
- **The Focus tab's note** names only the optics channels a DMX type has; a type with none is told
  its optics are fixed, never that its channels set them.

### Gobos on surfaces

Fixture-optics plan session 4 (D10). Until then a gobo was drawn in the air, in the haze's march,
and never where the beam landed — so a gobo, which is how anyone judges focus and placement on a
real rig, could not be placed or focused from the Stage view. Now the surface shader draws it on the
wall and the deck.

- **One sampler, both programs** (`goboLayers.ts`'s `GOBO_LAYERS_GLSL`). The surface shader, per light
  and fragment, after `beamMask` has shaped the pool: in the beam's own frame (`g`, the field edge at
  1 along the head's right axis and the one at right angles — the frame the mask cuts in, before an
  oval's division, as the haze reads it), each layer turned by its angle, read from the gobo atlas,
  and multiplied over the mask. So the blades, the iris and an oval cut the gobo exactly as they cut
  the pool (session 2's framing shutters included), and a gate rotation turns it, because it turns
  the frame. A segment (a rectangular aperture) carries none, as in the air.
- **Blurred by the edge's own blur.** The mip level is `goboLod(blur, footprint)`: the relative
  focus error times the type's depth of field (`focusBlur`, §"Focus") — the number the pool's edge
  spreads by — or the pixel's footprint on the surface in field radii, whichever is wider, both at
  `GOBO_BLUR_TEXELS` (64) texels to a field radius. The haze reads the same level from the same blur,
  so a gobo sharp in the air at a distance is sharp on a wall at that distance. The footprint is
  `fwidth(vWorldPos)`, taken before the light loop: derivatives after its `continue`s are undefined.
- **Two layers, multiplied** — stacked wheels. The Robe ColorSpot 575's static wheel and its rotating
  one are both drawn, in the air and on every surface; until session 4 the view drew whichever wheel
  selected a pattern and the other only while the first was open. The rule is one, in
  `store/fixtures.ts`: `findGoboProperties` orders the wheels by **DMX channel** (layer A, then B;
  descriptor order is Kotlin reflection's and guarantees nothing), and `goboRotationWheel` gives the
  rotation channel to the wheel it **follows** — the nearest at or below its channel, which is how
  every type in the library lays them out (the Patch List's gobo column reads the same first wheel,
  so on the Robe it is now the static one). Only that wheel turns (`stepGoboLayers` in
  `beamOptics.ts`, which indexes it or spins it); the other holds still in the frame. Session 3's
  rules carry over unchanged: a loadable wheel reads the unit's fitted gobos, and is never
  index-guessed, so the Revolution's stock wheel lands an open pool.
- **Packed into one float** (`packGobos`): layer A's and layer B's pattern (5 bits each), which one
  turns (1 bit) and its angle (13 bits, 0.044° a step) — 24 bits, exact in a float32, every decode a
  division by a power of two. One angle suffices because a type has one gobo rotation channel. It
  rides the haze's `aBeamFx.y` (`aBeamFx.z` is now spare) as a flat varying, and the light table's
  texel 4 `.z`.
- **Where texel 4 found the room** (`scene/lightTable.ts`'s header has the long form). Every packed
  float was full but texel 2's alpha, which has four bits spare. Texel 4 spent three floats on the
  frame's `u`, of which the shader only ever used the direction at right angles to the beam — a
  direction in a plane. It now holds that as `(cos, sin)` in an orthonormal basis built from the axis
  alone (`BEAM_FRAME_GLSL`'s `beamBasis`, Duff et al. 2017), and the third float holds the gobos.
  `frameInBasis` reads the axis as the GPU will (float32, `>= 0` for the basis's sign), so the two
  agree on which side of the basis's seam a beam pointing straight across the stage lies; the round
  trip is pinned from every side of it (`scene.test.ts`). The first cut stored one *angle*, which
  freed two floats but put a `cos` and a `sin` on every light of every lit fragment — about 5 % of a
  SwiftShader frame with no gobo in it, so the pair replaced it.
- **Asking for frames.** A spinning gobo moves the picture with no channel moving, so the director
  invalidates each frame while — and only while — the turned wheel shows a pattern and spins
  (`stepGoboLayers`' `spinning`, `goboLayers.test.ts`); an indexed, stopped or static wheel asks for
  nothing. The *Gobos on surfaces* switch invalidates when it flips.
- **The budget's fallback: *Gobos on surfaces*** (the View menu, per browser in `localStorage` as
  `stage.goboSurfaces`, beside the light budget: it is the machine's GPU's fact). *Every gobo light*
  (the default) or *Selected heads only*, which keeps every other head's pool plain — its gobo still
  shows in the air (`goboLandsOnSurfaces` in `scene/sceneView.ts`; the director zeroes the light
  row's gobos, never the haze's). A `render_view` capture keeps the default: it draws one frame, so
  there is no frame rate to guard, and no selection to narrow to.
- **Measured on SwiftShader only** (Chromium's software renderer, which prices every pixel on the CPU,
  so these are relative costs, never a frame rate): three gobo heads (a MAC 250, a Robe with both
  wheels, a Revolution with a fitted gobo) throwing 24 m onto a back wall, pools over most of a 2200 ×
  1500 canvas, haze off, one gobo spinning to keep frames coming, the mean rAF interval over 25 s per
  run, runs interleaved. Surface gobos on: **+8 %** against the same rig with them off (704 vs
  650 ms). The frame change under them, gobos off, against `main`: **+2–3 %** (643 vs 627 ms, four
  pairs). Neither gave a reason to default to the selection, so the switch defaults to every gobo
  light. The Safari and iPad pass is step 5 of `FU-MANUAL-STAGE-LIGHT-BUDGET`.
- **Checked in Chromium**, Front, haze off, three heads hung at 2.8 m, 16 m downstage, aimed at the
  back wall 24 m away: a MAC 250's TRIPLE sharp at focus DMX 9 (the distance *Focus here* solves for
  the wall), mush at DMX 13 and 5 (about 4 m and 6 m either side); the Robe's static dots and rotating
  swirl multiplying to the dots on the swirl's arms, and its yellow and cyan wheels to green; the
  Revolution's stock wheel an open pool, a stars gobo fitted through the patch sheet's Media box
  landing, and its top and left framing shutters cutting a fitted breakup as they cut the pool.

### The lantern's focus: the cut, the gate and the oval

Session 7 gave a conventional its focus (`docs/fixtures-engineering.md` §"Lanterns and focus"), and
`BodySpec` carries what the beam needs of it: `blades` (null while every blade is out, or the lantern
has neither shutters nor barn doors), `frameTurnDeg` (the gate's turn where it has blades, plus a
PAR lamp's where it has an oval), `iris` (where it has one; a DMX iris closes it further, the
smaller winning) and `ovalRatio` (narrow over wide, as tangents of the half-angles). The focus knob
sets the edge softness within the family's range (`softnessForFocus` in `lib/lanterns.ts`: a
profile 0.04–0.9, a PC 0.3–0.85, a fresnel 0.6–1), so a fresnel at its sharpest is still softer
than a profile at its softest. Focus applies to a single-cell body only; a lantern is always one.

**The blades are arguments to `beamMask`**, in the same head frame the field circle is drawn in —
`u` along the head's right axis, `v` = beam × `u`, up for a level lantern — so the pool and the haze
cut by one rule. A blade is a straight line `depth` in from the field's edge (0.5 reaches the centre)
turned by its angle about the middle of its edge; everything past it is dark, softened by the same
edge as the field. **They are packed, not given a texel**: each blade is 12 bits — 62 depth steps
× 64 angle codes (±45° in 1.5° steps, 61 values, `packBlade`) — two to a float, so the four fit
texel 5's `.y` and `.w` (`packBlades`), and the iris moved into texel 2's alpha beside the edge
hardness (`packEdgeIris`, both to 1/1023). The haze takes them as one more instanced attribute
(`aBeamBlades`, flat varying). The GLSL and the TypeScript twin changed together and
`beamMask.test.ts` pins them — a quarter-in top blade cuts a straight edge, an angled one a sloped
one, a gate turn turns the lot, and every step from −45° to +45° packs and unpacks exactly.

**The angle's step is 1.5°, not the degree** (fixture-optics plan D5, session 2): a DMX framing
shutter turns ±45° (the Source Four Revolution's), and a seventh bit of angle would push two blades
past a float's 24-bit mantissa, so the six bits cover ±45° at 1.5° instead of ±30° at 1°. A
lantern's blades are still **stored** to ±30° (`LanternFocus`'s validation, the Focus card's slider,
`MAX_BLADE_ANGLE_DEG` in `lib/lanterns.ts`); only what the pool draws of them is rounded to the
nearest 1.5°, and the Focus card's preview (`bladeLine`, which reads the packing's
`MAX_PACKED_BLADE_ANGLE_DEG`) rounds the same way so the picture and the pool agree.

**A DMX head's framing shutters take the same path** (session 2; `docs/fixtures-engineering.md`
§"Framing shutters"). `findShutterProperties` files a fixture's SHUTTER and SHUTTER_ROTATION
sliders by blade, in the wire's order, and the beam director reads their eight channels every
frame — depth over `0..depthMax`, angle over `degMin..degMax`, each honouring `inverted`
(`resolveDmxBlades` in `beamOptics.ts`) — and packs them with `packBlades` into its own scratch, in
place of the per-spec memo a lantern's are packed once into. A fixture with DMX blades never draws a
lantern's (`lanternBladesFor`, `beamBlades`): one source or the other, never both. The blades sit in
the head's frame, so they turn with pan and tilt. A **mover's** are packed into the frame's opposite
slots (`MOVER_BLADE_SLOTS`, a half-turn, which keeps each angle): the frame's `v` is the head's −Z,
which points away from a mover's base when it tilts out positive — down on a hung head — and a
profile mover hangs, so its blades are named as a hung head tilted out shows them (the balcony
Revolutions as aim points them: top blade, top of the pool). A head swung over the top, or a standing
one tilted out positive, cuts its top blade at the bottom, as the metal would. `FixtureModel.test.tsx`
pins each case, and a rotated blade turning about its own edge clockwise. Checked in Chromium on three
hung Revolutions aimed at a back wall 24 m away (Front camera): each frame at 255 cuts its named side
to the centre; a half-in frame 1 turns ±45° about the same point at rotation 0 and 255; a Source Four
19° lantern's blades draw as before.

**A gate or lamp turn rotates the frame, not the mask**: the director turns the head's right axis
about the beam by `frameTurnDeg` before it writes the light row and the hull, so the mask never
learns an angle it would have to spend a texel on. That is also why a lantern's own roll turns its
cut — the right axis is the head's.

**An oval is a negative aspect.** A disc is aspect 0 and a segment a positive depth-over-width; an
oval PAR writes −`ovalRatio`, and `v` is divided by its magnitude before the mask (in the surface
shader and the haze alike), so the unit circle becomes the lamp's ellipse — its wide axis along the
turned right axis. The hull is widened along the wide axis only. `FixtureModel.test.tsx` pins the
three §9 checks in unit form: a Source Four 19°'s apex puts the cone at the lens's width at the lens
(`archetype.test.ts`), a quarter-in top shutter draws a straight edge on the floor, and an oval
PAR's long axis turns with `lampRotationDeg`.

**The Focus tab** (`StageFocusPanel`, a tab of `StageFixtureControlPanel`) and the patch editor's
**Lantern** box both mount `components/lanterns/FocusCard.tsx`: the lantern and a live cross-section
of its gate (`GatePreview`), then only what the lantern can take — depth and angle per blade (or per
barn door), the gate's turn, the iris, sharp ↔ soft, zoom within its range, a PAR's lamp — and a
switch between the lanterns of a pair, which are focused separately. The tab writes each change
into the patch list's cache at once, so the Stage redraws the cut as a blade moves — **by value**
(`paintDraft`): RTK lands a cache update by applying Immer patches, which *clones* the shutters
array into the cache, so a paint that assigned the draft's own array would differ every time, and
the effect that repaints on each new `patch` looped until React gave up (found on a desk: any blade
key crashed the Stage view). It saves one `PUT` after a 350 ms pause (a placement's through the whole `extraPlacements` list, which
`toPlacementInput` carries the focus in); a refused write re-reads the list. A DMX fixture's tab
names the optics its channels drive instead — zoom, focus, iris, frost and framing shutters, those it
has — and offers *Focus here* where its focus declares a range (§"Focus"). The patch list's **Lantern** column picks the lantern
from the library, or reads out a DMX type's declared body, and its **Mount** column says *standing*
for a unit on a ledge or a floor stand.


## Scenery that moves with the show (session 8)

**The desk resolves, the canvas draws.** Cues, stacks and Looks can move scene elements — a drawn
drape's `open`, a flown piece's `trimM`, any element's `visible` — and the desk resolves what the
stage shows (lighting7 `show/SceneryResolver.kt`, `docs/cue-stacks-engineering.md` §"Scenery") and
streams it as `scenery.state` (`api/sceneryApi.ts`, `store/scenery.ts`'s form-3 `liveScenery`
entry). Each entry is a move: the state an element is going to, the one it is leaving, and how long.
This side never resolves precedence; it only draws a move.

**The source picks the scenery too.** `StageChannelSourceProvider` provides `StageSceneryContext`
beside the channel source: the live scenery for Output and the two programmer sources (the desk
already counts the programmer's live Looks, blind excepted), and for **Next GO** the preview's
`scenery` (the whole stage as it would resolve with the cue on deck, each element with `from` and the
GO's duration), its moves started when the answer lands — so selecting Next GO draws Q2's tabs
opening as the GO would open them. `Stage3D` reads the context itself, outside its canvas, so the
capture root's bridge carries nothing new; a `render_view` draws every move landed.

**Drawn by laying the state over the element.** `lib/scenery.ts`'s `sceneryElements` writes each
element's interpolated state into its `params.states` before `sceneBuilds` — the builders already
read `open`, `trimM` and `visible` from there (a drawn drape's halves, a flown piece's `elementBaseZ`,
`hidden` building nothing) — so nothing in the builders changed, and **beam reach follows for free**:
the colliders come from the builds, so closed tabs stop a beam and the surface shader lights nothing
behind them. An element left at its base comes back as the same object, and one whose drawn state
has not moved since the last call too (a `WeakMap` per element), so the build cache rebuilds only
what is moving.

**The clock ticks only while something moves.** `useSceneryClock` advances `performance.now()` on
`requestAnimationFrame` and `invalidate()`s the canvas every frame **until the last move lands**,
then stops (§"The frameloop renders on demand"): a tab drawing over four seconds asks for its
frames, an idle stage asks for none. Moves are eased sine in-out, the desk's own curve
(`SceneryService.ease` / `easeSceneryT`); a piece appearing shows at once, one disappearing goes at
the end. A frame is anchored at receipt from its `elapsedMs`, never its wall-clock `startedAt`, so a
skewed tablet does not replay a finished move.

**Authoring** is one shared editor, `components/scenery/SceneryEditor.tsx` — element · state ·
time · remove, the state a per-kind choice (`sceneryChoices`: Closed / Half open / Drawn, Trim · in /
out, Shown / Hidden; a state no choice writes stays selectable as itself) — mounted as *Scenery* in
Cue properties (with a time: blank moves with the cue's fade), *Set for this stack* in
`CueStackForm` and *Scenery while live* in `LookDetailSheet`. Each gesture saves the owner's
**whole list**, as the desk's `PUT …/scenery` takes it, and the rows are a draft the editor holds, so
a refetch landing mid-save (or while a time is typed) never puts an older list back — the
`StageFocusPanel` rule. The cue card (`CueDetailContent`) reads the cue's own changes and, hatched,
what it shows by tracking (`CueDetails.trackedScenery`).

## Confetti (session 9)

**Every fire throws confetti, real or rehearsed.** `stage3d/StageConfetti.tsx` subscribes to
`effects.fired` (`api/effectsApi.ts`) and throws `FLAKES_PER_TUBE` (380) flakes from the fired tube's
muzzle: the patch's world position through `worldPositionLighting`, the tube's axis from its base
yaw and pitch (`muzzleDirection` — up for a standing cannon, down for a hung one, the Twin Shot's two
tubes splayed ±12°, A left and B right). The frame is broadcast, so every window throws the same
burst; a rehearsed fire (Blind, or a window whose vis source is the programmer — lighting7
`docs/fixtures-engineering.md` §"@FixtureTrigger") sends nothing to the wire and is drawn exactly the
same way. A fire for a fixture this canvas has not placed draws nothing.

**The model is pure** (`confetti.ts`): a fixed pool of typed arrays — position, velocity, the floor it
settles on, a state (idle · flying · settled) and a seed per flake — stepped by `stepConfetti` with
drag, gravity capped at a flutter terminal of 0.85 m/s, and a sway, until a flake reaches its floor
and **settles**: it never moves again until its slot is thrown anew. The floor is the deck inside
the stage's footprint and the house floor (`houseFloorZ`, a modelled room's base) outside it. The
pool is `MAX_FLAKES` = five tubes plus a margin (2000), and it is a **ring**: a sixth tube in the air
reuses the oldest flakes rather than growing anything. `confetti.test.ts` pins the count, the
settle, the terminal and the ring.

**One instanced mesh, one draw call, unlit.** The pool is one `InstancedMesh` of a 3 × 2 cm plane,
allocated once per canvas, with a `MeshBasicMaterial`: it reads no light, so it touches neither the
surface shader's light table (§"Light lands through one surface shader") nor the haze program, and
the four-lights-per-fixture cap is not involved. Its vertex attributes are the plane's three
(position, normal, uv), the instance matrix's four and the instance colour — **eight**. Colours are
written once per throw, matrices every frame a flake flies; a settled flake's matrix is written flat
on the floor at a fixed turn and then left alone. The flakes are never raycast, so a click on the
stage reaches what is under them.

**It asks for frames only while a flake flies** (§"The frameloop renders on demand"). A fire
invalidates once; each frame that steps a flying flake asks for the next; when the last one settles
nothing asks again, and the settled flakes are a static draw like the rest of the stage. A hidden
pane or tab gets no `requestAnimationFrame`, so its burst waits and resumes when shown — `dt` is
clamped to 50 ms, so it does not jump.

**Measured (2026-10-01).** In the browser (Chromium, a 1280 × 800 Stage view on a bare stage with
one floor-standing Twin Shot), one rehearsed tube drew **305 frames over 5.1 s** and then **no draw
calls at all** across the next two seconds; the confetti is one `drawElementsInstanced` of 2000
instances per frame beside the rest of the scene's ~41 calls. The CPU side — `stepConfetti` plus the
matrix write, measured in Node on the same code — is a mean of **0.04 ms** a frame for one tube,
**0.07 ms** for two and **0.18 ms** for five (1900 flakes) once V8 has warmed up; a flight lasts
~5.2 s (≈315 frames at 60 Hz) whatever the count. The buffers are 128 KB of matrices and 24 KB of
colours, allocated once. Not measured: Safari on the operator's Mac, and an iPad.

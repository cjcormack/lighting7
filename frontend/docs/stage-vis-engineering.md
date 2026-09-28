# Stage visualisation: sources and appearance

How the stage surfaces decide **what a fixture looks like**. Two independent seams:

- a **channel source** — which layer of the lighting cascade the numbers come from;
- a **fixture appearance** — how a fixture's colour source turns those numbers into a colour and a
  level.

## The vis source

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
read it — the Stage route's View menu and the globally-mounted `StageOverviewPanel` — and
`usePersistentState` reads its key once in a `useState` initialiser and never listens for changes,
so two components sharing a key drift apart the moment one writes.

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
the `StageOverviewPanel` Layout hangs under the header — and RTK Query collapses their identical
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

Channel level rather than property level, because `FixtureModel`'s per-frame beam director reads 13
channels by key (pan, tilt, their fine axes, zoom, focus, two gobo wheels, gobo rotation, prism,
prism rotation, and two macros). Substituting at property level would mean touching each of those
reads and knowing which descriptor backs each; substituting at channel level means they all work
unchanged.

`ChannelSource` deliberately has **no `getAll()`**. A composing source would have to allocate a
merged map per fixture per frame to answer it. `getByKey` keeps every source O(1) and
allocation-free, which is what that frame loop requires.

**The provider wraps only the canvases** — the `Stage3D` / `Stage2DView` element in `routes/Stage.tsx`
and the `StageBackdrop` in `StageOverviewPanel.tsx`. Not `<main>`: the docked
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
- the 3D scene splits them — perceptual on the lens, **linear** on the cone and pool, because those
  opacities double as the `LIGHT_OFF_OPACITY` beam cull;
- the SVG plot bakes brightness into the fill with `dimCssColour`.

This is why `useColourAppearance` is not the shared piece: it returns only a pre-baked CSS string and
drops the level. It stays as it is for the swatch callers that only want the string.

### Why a render prop

Each colour source needs a *different* set of value hooks — `useColourValue` wants a
`ColourPropertyDescriptor`, `useGroupColourValues` subscribes to a variable-length channel list, a
gel fixture needs neither — so they cannot collapse behind one hook without breaking hook order.
`FixtureAppearanceSource` dispatches to a leaf component per source, each with a fixed hook set, and
hands the result to `children`. That constraint is why the 2D plot went without live colour for so
long, and the render prop is the way around it.

Consumers — **five mounting readers**: `StageMarker` (DOM); `Stage2DShapes`' `FixtureShape` (SVG);
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

### The 2D plot

`FixtureShapes` draws fixtures in a loop, so live colour needed a per-fixture component
(`FixtureShape`) to hang hooks on. Keeping the values *inside* that child also matters for
performance: `FixtureShapes` is `memo`'d and runs an O(n²) label declutter, and the old
`colourFor(patch)` callback prop would have re-run both on every DMX frame had its identity started
changing with the values.

Two traps in the SVG:

- **Do not set `stroke` on an unselected fixture.** The outline comes from a Tailwind class
  (`stroke-foreground/40`) so a pale tungsten dot stays visible on a light background, and CSS beats
  the presentation attribute — setting the attribute would kill the theme-aware outline. The
  attribute is for the selection highlight only.
- **Brightness goes into the fill, not `fill-opacity`.** The fixture already sits inside a `<g>` whose
  opacity carries group-filter dimming; a second opacity would fight it.
- **The fill has a brightness floor** (`BODY_FLOOR`, `SEGMENT_FLOOR`). A dark rig is the normal state
  while patching, which is most of what this plot is for, and an unfloored fill draws every dot pure
  black — leaving only a 40%-opacity outline to find it by. The DOM marker has the same floors,
  expressed as `0.3 + lit * 0.7` on its opacity; these are the same numbers folded into the fill.

A pixel bar draws as a segmented strip (one rect per element, each at its own brightness) with a
single outline rect over the top, rather than as one dot. Its geometry comes from `stripGeometry`,
which reads the **element-group descriptor**, not a live appearance — so the pointer hit target can
be sized to the strip without waiting on DMX. A bar is several times wider than a dot, and a
dot-sized hit circle leaves the ends of a long bar unclickable. `stripGeometry`'s `count > 1` gate
must stay in step with `FixtureAppearanceSource`'s, which decides whether an appearance carries
`segments` at all; `Stage2DShapes.test.ts` pins them together.

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
- **A lantern selects its fixture and is never dragged.** On the 2D plot its press calls the
  fixture's selection with no `buildDrag`, a marquee over it selects the fixture once, and it is an
  alignment guide for the fixture it pairs with. In 3D it is a `FixtureModel` over the patch with the
  placement's seven geometry fields laid on (`patchAtPlacement` in `stage3d/lanterns.ts`), its own emitter slot after the
  fixtures', and **no `onEditFocus`**, so the translate gizmo can never bind to it. Lanterns are
  moved in the patch form's *Also hung at* section (`ExtraPlacementsFields`), which starts a new
  one mirrored across the centre line (`lib/extraPlacements.ts`).
- **Selection lights the pair.** Selection is by patch key, so selecting a fixture highlights every
  lantern it has, which is the answer to "which lanterns is this circuit?".
- **An unpositioned lantern is not drawn**, on either view: the 3D `worldPositionFor` reads a null
  coordinate as 0, and a lantern at the origin would be a claim about the rig.

What is left for later: dragging a lantern on the plot (the bulk route already takes
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
- **2D**: `useProjectedPatches` gives such a fixture (and each side) a `span` — its two ends,
  projected — and `FixtureShape` draws a bar between them, to scale, with a stroke-shaped hit
  target along it. A span that projects to a point (a run seen end-on in an elevation) falls back to
  the dot, and a pixel bar keeps its segmented strip. The long axis is `longAxisLighting`, which
  mirrors `FixtureModel`'s body rotation exactly — a YXZ Euler of `(basePitchDeg, baseYawDeg,
  baseRollDeg)` in world space, so the rigging's pose places the body but does not turn it, pitch
  never moves the long axis, and roll is the only turn that lifts it off level (90 stands a run on
  end). Change one, change both.
- **The form**: *Length* sits under the fixture's placement, and the extra-placements section reads
  *Other sides of this run*, each side with its own *Length*. An out-of-range value is kept and
  flagged, never clamped mid-keystroke, and the form will not save it.

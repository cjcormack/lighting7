# OSC

QLab and other show-control senders drive the desk over OSC: a Network cue sends one message, an
address and optional arguments, and the desk does what the address names. The plan is
[`plans/osc-plan.md`](plans/osc-plan.md); this document is what is built. Session 1 is the listener,
stacks, Looks, loose cues and busk pads. Masters and tempo, and finding addresses, are session 2's;
learnable addresses, feedback and TCP session 3's.

## The address set

Every address starts `/lighting7/` and acts on the **current project** (D7). The table is
`osc/OscAddresses.kt`'s `entries`, the one copy the resolver, the tests and (session 2) the address
listing read (P2):

| Address | Args | Does | Target |
|---|---|---|---|
| `/stack/{stack}/cue/{n}/go` | – | GO TO cue *n*, starting the stack if stopped; nothing if *n* is already live | `CueStackGoTo` |
| `/stack/{stack}/cue/{n}/standby` | – | Put cue *n* on standby | `CueStackStandby` |
| `/stack/{stack}/go` | – | GO | `CueStackGo` |
| `/stack/{stack}/back` | – | GO BACK | `CueStackBack` |
| `/stack/{stack}/pause` | – | Cancel the live entry's follow timer | `CueStackPause` |
| `/stack/{stack}/stop` | – | Stop the stack, which drops the arm as any stop does | `CueStackStop` |
| `/stack/{stack}/fx-amount` | number 0–1 | The stack's FX amount, taken whole | `StackFxAmount` |
| `/cue/{cue}/on`, `/off` | – | Take or let go of a loose hold; nothing if already so | `LooseCueSet` |
| `/cue/{cue}/press` | – | Toggle the loose hold | `FireCue` |
| `/look/{look}/on`, `/off` | – | The Look on whole, or every layer of it off; nothing if already so | `LookSet` |
| `/look/{look}/press` | – | Toggle the Look | `ApplyLook` |
| `/busk/{page}/{bank}/{pad}/press` | – | Press a pad, on the desk selection | `PressPad` |
| `/busk/{page}/show` | – | Show that busk page | `BuskPageSet` |

**Names are slugs** (D3): `slugOf` in `models/slug.kt`, shared with the MCP setup tools — lower
case, every run of anything but ASCII letters and digits one `-`, trimmed, at most 100 characters.
An incoming segment is lower-cased before it is matched, so `/LIGHTING7/stack/Act-1/GO` works. A slug
two records share is **ambiguous** and refused, naming both; a separator is never a stack; a name that
slugs to nothing has no address. Cue names are not unique, so `/cue/{cue}` works only for one whose
name is; a cue in a stack is addressed by number, matched by value (`cueNumberValue`: `12.5` and
`12.50` are one cue). `{bank}` counts banks down each column, columns left to right, rows top to
bottom; `{pad}` counts a bank's pads, a malformed pad taking no place.

The resolver reads the database for every message, in one transaction, as a REST request does
(`DbOscLookup`). A rename is answered by the very next message, so nothing is cached and nothing
needs invalidating; at desk scale the scans are trivial.

**The explicit forms land the same state however many times they arrive** (D4). GO TO onto the entry
already live, `on` for a cue already loose-held or a Look already applied, and `off` for one that is
not, are no-ops answered *done · already live* (or *already on*, *already off*): a QLab cue fired
twice changes nothing — no re-fire, no second *Tempo on GO*, no second cue events. The toggles and
bare GO keep today's behaviour, re-fire included, for buttons pressed by hand. A GO TO is otherwise
an ordinary GO TO, so it fires the events of the cue it lands on, armed, and none of a cue it skips
(D11). OSC never arms the desk (D6); `/stop` drops the arm, as every stack stop does, whether the
stack was running or not.

**A Look is a programmer layer** whichever door puts it on: Clear takes it off, Record captures it,
and in Blind it is staged, which its log row says (*staged: Blind*). A busk pad presses on whatever
the desk has selected, so from QLab only Look and cue pads are predictable; a loose cue is a hold
above every stack that the programmer does not touch. Show control is best written as GO TO and
loose cues, with Looks where the operator may take them over.

## Dispatch

Every address resolves to a `BindingTarget` and runs through `midi/SurfaceDispatch.kt`, the dispatch
a MIDI surface's router uses (D2): `press` for a button target, `level` for a value taken whole. So a
new action is added as a target, and a MIDI surface gains it too — the five targets above that are
new are in the binding picker. The actions answer an `ActionOutcome` (`Done(detail)`,
`Refused(code, message)`, `NotFound(message)`, `Failed(message)`), which becomes the log row.

## The receive path

`osc/OscReceiver.kt` owns the `DatagramSocket` and its thread, **`osc-1`** (D8). It reads a datagram,
drops it unread if its source address is not on the allowlist, parses the rest with
`osc/OscPacket.kt` and hands each message to the service. It never touches the database or the
show. The parser is hand-rolled OSC 1.0 (D9): `i`, `f`, `s`, `b`, `h`, `d`, `T`, `F`; any other tag
drops its message with a reason; bundles to a depth of 8 and at most 64 messages a packet, past which
the packet is dropped; timetags ignored, so every message runs on arrival. Where an address wants a
number it takes any numeric tag, since QLab sends a whole number as `i`.

The hand-off is a `Channel(64, DROP_OLDEST)` whose `onUndeliveredElement` counts each drop — the
receive thread never waits on the show. One collector on the service's single-threaded dispatcher
takes each message in turn through `osc/OscHandler.kt`: resolve, then dispatch under the sender's
`WriteOrigin`, inside `SurfaceDispatch.guarded`, so any throw — a project switch in flight among the
causes — is a *failed* row and the collector carries on. The collector yields between messages and
the state is published from another dispatcher, so a flood never freezes `osc.state`.
`OscFloodOutputTimingTest` holds a fade's output timing while an allowed and a refused sender flood
the listener (P3).

## Settings and security

Off by default (D5). The service starts with the desk, before the show, so its settings apply on a
desk whose show failed to start; a message that arrives before the show is up is a *failed* row. An
admin on the desk's own listener turns it on in Install Settings → OSC:
`GET/PUT /api/rest/install/osc` (`routes/installOsc.kt`), `{enabled, bindAddress, port, senders:
[{id, name, address}]}`, the `PUT` the whole document — a sender with no `id` is new, one left out is
removed — refused 400 `OSC_SETTINGS_INVALID` naming every problem at once (a port outside 1024–65535,
an address that is not an IP literal, a sender twice). Both are 403 `OSC_SETTINGS_LOCAL_ONLY` on the
public listener. The default port is UDP **8415** (D13); `bindAddress` null listens on every
interface, and is how a desk keeps OSC off a network it does not trust. A hostname is never looked
up: the allowlist compares addresses, and DNS has no place on the settings route.

**Only listed senders are heard.** A packet from anywhere else is dropped before parsing and counted,
one row per address (at most 20, the stalest going first) with *Allow this sender* for an admin. A
source address can be forged by anyone on the LAN, so the allowlist keeps out the wrong machine, not
an attacker. A port in use is `osc.state`'s `error`, never a failed startup.

Storage is `osc_settings` (one row) and `osc_senders`, both `MachineLocal` in `SyncDispositions`:
they never sync, and need no migration.

## The log

`osc.state` (`plugins/MachineSocket.kt`) goes to every socket on the desk's own listener, operators'
included (D12), and none on the public one: `{enabled, listening, port, error?, received, dropped,
refused, refusedSenders, recent}`, at most four times a second. Each of the last 50 messages is a
row — time, sender (the allowlist's name), address, arguments, outcome and a one-line detail naming
what it reached (*GO TO 31 Storm builds in Act 2*) or why it did nothing (*No stack `act-3` in this
show*). Outcomes: **done**, **not found**, **ambiguous**, **refused**, **failed**, **bad message**.
A dropped message never reaches the collector, so drops are a count, not rows. Every address resolved
since the desk started is kept, lower-cased (at most 1,000), for session 2's rename warning.
`osc.messages` (`result`: `received`, `dropped`, `refused`) goes into the 30-second metrics.

Each message is also an `osc.message` Sentry transaction, named by the pattern it matched rather than
the address it carried, tagged with its outcome, with spans for the resolve and the dispatch and the
time it waited in the channel — at the sample rate and at most five a second, so a flood runs
untraced past the budget. See `docs/observability-engineering.md` §"Where transactions start".

Numbers and positions move where names fail loudly: renumbering a cue, an insert that relabels auto
numbers, or moving a bank or a pad can point an address at a different record. That is why every
*done* row names the record it reached.

## History

Each message runs under `WriteOrigin(HistorySource.osc(senderId, name))`, so History names the sender
(*QLab*) — the fifth door in `docs/undo-engineering.md`. Most OSC traffic takes no step (a GO writes
nothing stored); a Look on or off takes one, as a busk press does. There is no undo address.

## The client

`components/osc/OscPanel.tsx` on the OSC tab of Install Settings, a row both roles see
(`navigation.ts`). Everyone sees the status, the refused senders and the log, from `osc.state` alone
(`api/oscWsApi.ts` keeps the latest frame, since the snapshot arrives on connect); an admin also sees
*Enable*, the port, the interface and the senders, from `GET /install/osc` (`store/osc.ts`).

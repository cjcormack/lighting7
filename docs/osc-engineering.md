# OSC

QLab and other show-control senders drive the desk over OSC: a Network cue sends one message, an
address and optional arguments, and the desk does what the address names. The plan is
[`plans/completed/osc-plan.md`](plans/completed/osc-plan.md); this document is what is built.
Session 1 is the listener, stacks, Looks, loose cues and busk pads; session 2 masters and tempo, and
finding addresses: the listing, *Copy OSC address*, the address list and the moved-address warning;
session 3 TCP and replies. Sender ranges and the on-screen address list are the venue-rig-fixes plan's
session 1 ([`plans/venue-rig-fixes-plan.md`](plans/venue-rig-fixes-plan.md), D1–D5). Learnable addresses, and the fader and LED feedback a learned sender
would want, wait for one to be in use (`FU-OSC-LEARN` in `plans/followups.md`).

## The address set

Every address starts `/lighting7/` and acts on the **current project** (D7). The table is
`osc/OscAddresses.kt`'s `entries`, the one copy the resolver, the tests and the address listing read
(P2):

| Address | Args | Does | Binding |
|---|---|---|---|
| `/stack/{stack}/cue/{n}/go` | – | GO TO cue *n*, starting the stack if stopped; nothing if *n* is already live | `goTo` on the entry |
| `/stack/{stack}/cue/{n}/standby` | – | Put cue *n* on standby | `standby` on the entry |
| `/stack/{stack}/go` | – | GO | `go` on the stack |
| `/stack/{stack}/back` | – | GO BACK | `back` on the stack |
| `/stack/{stack}/pause` | – | Cancel the live entry's follow timer | `pause` on the stack |
| `/stack/{stack}/stop` | – | Stop the stack over its release time, which drops the arm as any stop does | `stop` on the stack |
| `/stack/{stack}/fx-amount` | number 0–1 | The stack's FX amount, taken whole | `fxAmount` on the stack, the value as `Op.Set` |
| `/cue/{cue}/on`, `/off` | – | Take or let go of a loose hold; nothing if already so | `cue`, How On / Off |
| `/cue/{cue}/press` | – | Toggle the loose hold | `cue`, How Toggle |
| `/look/{look}/on`, `/off` | – | The Look on whole, or every layer of it off; nothing if already so | `look`, How On / Off |
| `/look/{look}/press` | – | Toggle the Look | `look`, How Toggle |
| `/busk/{page}/{bank}/{pad}/press` | – | Press a pad, on the desk selection | `pad` on the pad |
| `/busk/{page}/show` | – | Show that busk page | `buskPage` on the page |
| `/blackout` | number, optional | Set blackout, non-zero on; toggle with no argument | `blackout`, How On / Off / Toggle |
| `/grandmaster` | number 0–1 | The grand master's level, taken whole and clamped | `grandMaster`, the percentage as `Op.Set` |
| `/tempo/{master}` | number, BPM | Set a speed master's tempo, 20–300 | `tempo` on the master, the BPM as `Op.Set` |
| `/tempo/{master}/tap` | – | One tap | `tap` on the master |

**Names are slugs** (D3): `slugOf` in `models/slug.kt`, shared with the MCP setup tools — lower
case, every run of anything but ASCII letters and digits one `-`, trimmed, at most 100 characters.
An incoming segment is lower-cased before it is matched, so `/LIGHTING7/stack/Act-1/GO` works. A slug
two records share is **ambiguous** and refused, naming both; a separator is never a stack; a name that
slugs to nothing has no address. Cue names are not unique, so `/cue/{cue}` works only for one whose
name is; a cue in a stack is addressed by number, matched by value (`cueNumberValue`: `12.5` and
`12.50` are one cue). `{bank}` counts banks down each column, columns left to right, rows top to
bottom; `{pad}` counts a bank's pads, a malformed pad taking no place. `{master}` is a speed master's
display index — `/tempo/2` is M2 — and an index the show has no master at is not found.

**Values are taken whole** (D10): the grand master's 0–1 becomes 0–100 % and a tempo is a BPM, rather
than through the 128 steps a MIDI fader has. A number outside 0–1 for the grand master is clamped; a
tempo outside 20–300, or a non-finite number anywhere, is a *bad message* and moves nothing. A tempo
is a typed tempo's write, so on a master the programmer holds it moves the hold, and a master that
follows another refuses it (`SPEED_MASTER_FOLLOWER`). `/blackout 1` sent twice is *already on*.

The resolver reads the database for every message, in one transaction, as a REST request does
(`DbOscLookup`). A rename is answered by the very next message, so nothing is cached and nothing
needs invalidating; at desk scale the scans are trivial.

**The explicit forms land the same state however many times they arrive** (D4). GO TO onto the entry
already live, `on` for a cue already loose-held or a Look already applied, and `off` for one that is
not, are no-ops answered *done · already live* (or *already on*, *already off*): a QLab cue fired
twice changes nothing — no re-fire, no second *Tempo on GO*, no second cue events. The toggles and
bare GO keep today's behaviour, re-fire included, for buttons pressed by hand. A GO TO is otherwise
an ordinary GO TO, so it fires the events of the cue it lands on, armed, and none of a cue it skips
(D11). OSC never arms the desk (D6); `/stop` is an operator's stop, fading the stack's cues over its
release time (busk-stack plan D6), and drops the arm whether the stack was running or stopped, except on
a stack whose release is still running, which it leaves as it is.

**A Look is a programmer layer** whichever door puts it on: Clear takes it off, Record captures it,
and in Blind it is staged, which its log row says (*staged: Blind*). A busk pad presses on whatever
the desk has selected, so from QLab only Look, cue and stack pads are predictable (a stack pad names no
fixtures, and its press is the busk stack's GO, busk-stack plan D9); a loose cue is a hold
above every stack that the programmer does not touch. Show control is best written as GO TO and
loose cues, with Looks where the operator may take them over.

## Dispatch

Every address resolves to a `Binding` (`docs/midi-control-surface-engineering.md` §"Binding model")
built in memory — none is stored — and runs through `midi/SurfaceDispatch.kt`, the dispatch a MIDI
surface's router uses (D2): `Input.Press` for an action, `Input.Set` for a value taken whole, which the
function reads in its own unit (a percentage, an amount, a tempo). The explicit forms are a How of On
or Off on the function's toggle (D4), so a MIDI surface binds exactly what OSC sends, and a new action
is added as a function both reach. The addresses and their arguments are what they were before the
surface binding grammar (surface-setup plan D19); `OscAddressesTest` holds every address to the call
it makes. The actions answer an `ActionOutcome` (`Done(detail)`,
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
the listener, over UDP and over TCP (P3).

**TCP** is OSC 1.1's: on the same port as UDP, each packet framed by double-ended SLIP (RFC 1055,
`osc/OscSlip.kt`), which is how QLab sends OSC over TCP. `osc/OscTcpListener.kt` accepts on
`osc-tcp`, closes a connection from an address not on the allowlist unread (counted and listed as a
refused sender, as a UDP packet is), and reads each connection on its own `osc-tcp-N` thread: frames
split across reads are joined, one longer than a datagram (65,507 bytes) is a *bad message* row and
the connection reads on, and each message goes into the same channel as UDP's. At most eight are
open; a ninth closes the one idle longest rather than being turned away, so a QLab machine that lost
power mid-show, its connection never closed, cannot lock its own reconnect out (keepalive closes such
a connection in time too). The sender is looked up again for every frame, and a settings save closes
the connections of senders no entry admits any more. UDP and TCP bind separately and are reconciled separately, so
a port another program holds on one still leaves the other listening, a save that moves neither
leaves a running one alone, and `error` names the one that failed. Nothing on these threads touches
the database or the show (D8).

## Settings and security

Off by default (D5). The service starts with the desk, before the show, so its settings apply on a
desk whose show failed to start; a message that arrives before the show is up is a *failed* row. An
admin on the desk's own listener turns it on in Install Settings → OSC:
`GET/PUT /api/rest/install/osc` (`routes/installOsc.kt`), `{enabled, bindAddress, port, senders:
[{id, name, address, replies}]}`, the `PUT` the whole document — a sender with no `id` is new, one left out is
removed — refused 400 `OSC_SETTINGS_INVALID` naming every problem at once (a port outside 1024–65535,
an address that is neither an IP literal nor a range, a sender twice). Both are 403 `OSC_SETTINGS_LOCAL_ONLY` on the
public listener. The default port is **8415**, UDP and TCP (D13); `bindAddress` null listens on every
interface, and is how a desk keeps OSC off a network it does not trust. A hostname is never looked
up: the allowlist compares addresses, and DNS has no place on the settings route.

**A sender is an address or a range** (D1): `a.b.c.d/n` (0–32) or an IPv6 `addr/n` (0–128), stored
as its network in canonical form — `192.168.0.1/24` saves as `192.168.0.0/24`, and an address and its
`/32` (or `/128`) are one entry, saved as the bare address. Two entries naming the same address or
network are refused as *listed twice*, whatever their spelling; overlapping ranges are allowed. An
IPv4-mapped IPv6 entry is the IPv4 one. `osc/OscSenderMatcher.kt` parses an entry (`canonical`) and
is the one lookup every receive path asks (D2), built when the settings load: an exact address wins,
then the longest matching prefix, IPv4 and IPv6 apart, an IPv4-mapped source matching as IPv4. The
entry it answers names the sender in History and the log; the log row also carries the packet's
`source`, so a message through a range says which machine sent it.

**Only listed senders are heard.** A packet from anywhere else is dropped before parsing and counted,
one row per address (at most 20, the stalest going first) with *Allow this sender* for an admin, which
adds the exact address. A save drops every refused address the new settings admit, a range's included. A
source address can be forged by anyone on the LAN, so the allowlist keeps out the wrong machine, not
an attacker. A port in use is `osc.state`'s `error`, never a failed startup.

Storage is `osc_settings` (one row) and `osc_senders`, both `MachineLocal` in `SyncDispositions`:
they never sync, and need no migration — `osc_senders.replies`, session 3's, is a column
`createMissingTablesAndColumns` adds, off, and `osc_senders.address`'s 64 characters hold an IPv6
`/128`.

## Replies

A sender whose allowlist entry has **Replies** on (`replies`, off by default, D14) is answered for
each `/lighting7/` address it sends, once the message is logged: `/lighting7/reply` with three
strings — the address it sent, its outcome in lower case (`done`, `not_found`, `ambiguous`,
`refused`, `failed`, `bad_message`) and the log row's detail. Over UDP it goes to the address and
port the message came from, sent from the listening socket (on a desk with several addresses, set
the interface so the reply comes from the one the sender sent to); over TCP down the same
connection, SLIP-framed, and a peer that half-closes after sending keeps its connection two seconds
for them. An address outside `/lighting7/`, a packet that could not be read and a
`/lighting7/reply` itself are logged but never answered: a peer that acknowledges what it is sent in
its own namespace, or another desk with replies on, would otherwise answer the desk forever. Nor is a
message the full channel dropped. The collector only offers the reply to a queue
(`osc/OscReplies.kt`, 64 deep, its own thread: `osc-reply` for UDP, `osc-tcp-reply-N` for each
connection), and a reply that finds it full is dropped, never the message — so a TCP peer that stops
reading holds up nobody but its own replies. QLab acts on nothing it reads back; turn Replies on for
it to watch the answers in its OSC monitor.

## The log

`osc.state` (`plugins/MachineSocket.kt`) goes to every socket on the desk's own listener, operators'
included (D12), and none on the public one: `{enabled, listening, tcpListening, port, error?,
received, dropped, refused, refusedSenders, recent, warnings}` (`listening` is UDP's), at most four times a second. Each of the last 50 messages is a
row — time, sender (the allowlist's name), source (the address it came from), address, arguments,
outcome and a one-line detail naming
what it reached (*GO TO 31 Storm builds in Act 2*) or why it did nothing (*No stack `act-3` in this
show*). Outcomes: **done**, **not found**, **ambiguous**, **refused**, **failed**, **bad message**.
A dropped message never reaches the collector, so drops are a count, not rows. Every address resolved
since the desk started is kept, lower-cased and by project (at most 1,000), with what it reached.
`osc.messages` (`result`: `received`, `dropped`, `refused`) goes into the 30-second metrics.

Each message is also an `osc.message` Sentry transaction, named by the pattern it matched rather than
the address it carried, tagged with its outcome, with spans for the resolve and the dispatch and the
time it waited in the channel — at the sample rate and at most five a second, so a flood runs
untraced past the budget. See `docs/observability-engineering.md` §"Where transactions start".

## Finding addresses

`GET /api/rest/projects/{id}/osc/addresses` (`routes/projectOsc.kt`) is every address the project
answers to: `{prefix, patterns, addresses: [{address, pattern, args, does, reaches, target, record?}],
ambiguous: [{message, records, addresses}], unaddressed: [{kind, id, uuid, name, reason}]}`. It is made
from the table and answered by the resolver (P2): each placeholder is filled with every value the
project has — every stack's slug, each stack's cue numbers, every page's banks and pads, every master's
index — and each address so made is run through `resolve`, so a row says exactly what a message would
do. A slug two records share is one `ambiguous` row naming them and the addresses it makes; a name with
no ASCII letter or digit, or a cue entry with no number, is `unaddressed`. `record` (`{kind, id, uuid}`,
`STACK | ENTRY | CUE | LOOK | PAGE | PAD | MASTER`) is what the client finds a record's addresses by. Any
project, both roles, and never on the public listener (403 `OSC_LOCAL_ONLY`, D5), so the client draws no
*Copy OSC address* in remote mode; the reads, and each record's slug, are worked out once a request.

**Copy OSC address** is a submenu on a stack's ⋯ (the Show view), a cue's ⋯ (`CueActions`: the entry's
GO TO and standby, then the cue's loose hold), a busk pad's hold menu, and an *OSC address* button on
a Look's sheet (`components/osc/CopyOscAddressMenu.tsx`). It reads the listing as it opens, so it
follows a rename, and says why where a record has none.

**The address list** (`components/osc/OscAddressList.tsx`, D4) is on the OSC tab under the listener
settings, for the current project and either role, and absent in remote mode, where the desk refuses
the listing (403 `OSC_LOCAL_ONLY`): *Addresses · `<project>`* with what the desk listens on (*port N ·
UDP and TCP*, *· UDP* or *· TCP*, *OSC is off*), a filter over the address, what it reaches and what it does, then the addresses by kind (Cue
stacks, Loose cues, Looks, Busk pads and pages, Masters and tempo, Other — `oscAddressSections` in
`lib/oscAddresses.ts`), each a row of Address, Args, Reaches and a copy button, then *Ambiguous* and
*No address*. It reads the listing as it mounts and on *Refresh*, as the menus do, since no frame says
an address moved; a read that fails says so, and that a list still drawn may be out of date. A copy
goes through `copyOscAddress`: the Clipboard API, else the browser's own `execCommand('copy')`, which
a plain-http LAN origin still allows inside a gesture, the address set on its `copy` event so a
menu's focus trap cannot take the selection away (counted only when that event ran), else the address
selected in its cell with *Press ⌘C to copy* (a menu, which has no cell, says it could not copy). It
replaced the printable cheat sheet (D5).

## Addresses that move

Names fail loudly: a renamed stack's address answers *not found*. Numbers and positions do not:
renumbering a cue, an insert or delete that relabels auto numbers, moving a bank or a pad, or deleting
a speed master (a new one takes the next free index, which can be the deleted one's) can point an
address at a different record. So every *done* row names the record it reached, and the desk warns.

`OscService.attach(fixtures)` listens to the live show's `cueStackListChanged`, `cueListChanged`,
`lookListChanged`, `speedMasterListChanged` and `buskLayoutChanged`. Each only signals a conflated
channel, and so does attaching a show — a reload or a switch brings its changes with it, a sync
pull's rename among them; 300 ms later the collector (`checkReceived`) re-reads what every address
received in the current project reaches (`OscAddresses.reachAll`, the resolver with a nominal argument,
every list read once) and compares it with what it reached when last read, by the record it names
(`identityOf`: the kind and uuid, never the value a tempo carries; for an address naming no record,
`/blackout` with or without its flag, its row, which nothing moves). One that now reaches another record, or none, is a line of a warning —
`osc.state`'s `warnings: [{id, at, changes: [{address, was, now?, reason?}]}]`, the last 10, newest
first — and the new answer becomes what it reached, so one change warns once. A GO fires
`cueStackListChanged` too, which costs a re-read of the received addresses and warns nothing. The
warning is said after the change, with Undo to take it back, rather than asked before it: the eight
changes are made in a dozen places, and only the desk knows what a renumber relabels.
`components/osc/OscAddressAnnouncer.tsx`, mounted by `Layout`, toasts each warning newer than those
this window has seen — never one from the snapshot a window connects to — and the OSC tab lists them.
Warning ids start from the desk's boot clock, so a window that outlives a restart still reads the next
one as new.

## History

Each message runs under `WriteOrigin(HistorySource.osc(senderId, name))`, so History names the sender
(*QLab*) — the fifth door in `docs/undo-engineering.md`. Most OSC traffic takes no step (a GO writes
nothing stored); a Look on or off takes one, as a busk press does. There is no undo address.

## The client

`components/osc/OscPanel.tsx` on the OSC tab of Install Settings, a row both roles see
(`navigation.ts`). Everyone sees the status, the refused senders and the log, from `osc.state` alone
(`api/oscWsApi.ts` keeps the latest frame, since the snapshot arrives on connect); an admin also sees
*Enable*, the port, the interface and the senders with their *Replies* box, from `GET /install/osc`
(`store/osc.ts`). The status names each transport listening: *Listening on UDP and TCP 8415*.
*Enable* and *Disable* save the stored document with only the switch flipped, so a half-typed draft
never goes live; the draft is kept through them, each field following the stored document only where
it has not been edited, and *Save* sends it.

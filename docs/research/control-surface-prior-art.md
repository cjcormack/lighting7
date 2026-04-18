# MIDI Control Surface Support — Technical Analysis

**Status:** Research / pre-design
**Purpose:** Inform future design and architecture sessions for adding hardware control surface support to the lighting application (Kotlin/Ktor backend + React frontend). This document is deliberately scoped to research into the problem space, tech stack options, and prior art. Concrete architecture and API decisions are out of scope.

---

## 1. Context and goals

The application currently offers programming and operation through a web UI. A hardware control surface is a natural complement for show-running: physical faders, encoders, and lit buttons allow an operator to work without looking at the screen, free up screen real estate for programming-oriented views, and map cleanly onto the split-view "program vs. run" concept already being explored.

Design principles for this feature:

- **Device-agnostic core.** The application should not be tied to a specific make or model. Cheap 8-fader USB controllers (nanoKONTROL) and more capable surfaces with motorised faders (X-Touch Compact, BCF2000) should both be usable.
- **Feedback is a first-class concern.** A surface that can only send messages is interesting; one that also receives messages (to drive motorised faders, encoder LED rings, and button LEDs) is transformative for solo operation. The architecture must assume bidirectional communication from day one.
- **Configuration belongs in the application, not the device.** Users shouldn't need vendor-specific editor software to make a surface useful. The application owns the mapping between physical control and logical function.
- **LAN / tablet access is already on the roadmap.** Any surface abstraction should accommodate virtual surfaces (TouchOSC-style apps) over the network, not just USB MIDI hardware.

---

## 2. Protocol options

### 2.1 MIDI

The default choice for this class of hardware. Virtually every affordable control surface speaks MIDI over USB, it's USB-class-compliant (no driver installation), it's well-documented, and there is mature library support across every platform the JVM runs on. The protocol is simple: Note On/Off for buttons and pads, Control Change (CC) for faders and encoders, Pitch Bend for high-resolution (14-bit) faders, and SysEx for vendor-specific extensions.

Downsides: 7-bit resolution on most messages (0–127) means fader precision is limited unless the device and library support 14-bit CC or pitch bend. MIDI over USB has no inherent network story — for that you need RTP-MIDI, OSC, or a bespoke transport.

### 2.2 OSC (Open Sound Control)

Higher-level, network-native, address-based (`/master/intensity 0.75`), arbitrary data types. Dominant in the theatrical and installation world; ETC Eos, Figure 53 QLab, Chamsys MagicQ, and most high-end lighting desks expose OSC APIs. Virtual surface apps (TouchOSC, Lemur) natively speak OSC.

OSC is complementary to MIDI, not a replacement. Physical surfaces are almost all MIDI; virtual ones are mostly OSC. A well-designed abstraction should handle both as transports feeding the same core event model.

### 2.3 MIDI Show Control (MSC)

A standardised MIDI SysEx-based protocol specifically for theatrical show control — `GO`, `STOP`, `RESUME`, `LOAD`, `SET`, with cue numbers and list references. Supported by most pro lighting desks and show-control platforms (QLab, SCS, Medialon).

Worth supporting separately from generic MIDI input. MSC's semantics map directly onto cue-list operations, so a receiver that understands MSC can be driven by any MSC-compliant sender with no per-device configuration. It's a narrower surface than raw MIDI but deeply useful in theatrical contexts.

### 2.4 USB HID / vendor-specific protocols

Some surfaces (Stream Deck, Loupedeck, Monogram Creative Console) are USB HID devices, not MIDI. Others (Behringer X-Touch full-size, full-fat grandMA consoles) have vendor protocols over Ethernet (X-CTL, MA-Net). Lower priority — explicitly out of scope for the first iteration but worth not precluding in the abstraction.

### 2.5 Recommendation for the doc's scope

MIDI first, with the abstraction designed so OSC can slot in as a second transport and MSC as a specialised input. HID/vendor protocols remain extensible but unimplemented.

---

## 3. The device landscape

A useful mental model is to sort devices along two axes: **input richness** (how many and what kinds of controls) and **feedback capability** (what the device can display back to the operator).

**Input-only / minimal feedback.** Korg nanoKONTROL2, Akai LPD8, Worlde EasyControl. Cheap, compact, no motorised faders, button LEDs at best. Good for secondary panels or budget rigs.

**Pad-based with RGB feedback.** Akai APC Mini / APC40, Novation Launchpad, Ableton Push. Rich bi-colour or RGB grid feedback but typically few or no faders. Great for cue triggering; weak for intensity control.

**Motorised surfaces.** Behringer X-Touch Compact and full X-Touch, Behringer BCF2000 (discontinued but common used), Mackie Control Universal, Presonus Faderport. Touch-sensitive motorised faders plus encoders with LED rings. The sweet spot for a solo programmer/operator.

**High-end theatrical.** ETC Gio / Ion, MA Lighting grandMA3 command wings, Avolites Titan consoles. Vendor-specific protocols, not typically reachable as MIDI control surfaces — these are full consoles, not peripherals.

**Virtual surfaces.** TouchOSC, Lemur, Open Stage Control, custom web UIs. Transport over OSC or network MIDI. User-configurable layouts. A natural companion to the existing web frontend's LAN access story.

What varies across devices, and therefore what the abstraction must accommodate:

- Number and arrangement of physical controls
- Message types (CC vs. Note vs. pitch bend vs. SysEx)
- Channel usage (some devices use multiple channels to extend capacity; some use channels for banking)
- Fader resolution (7-bit vs. 14-bit)
- Encoder type (absolute vs. relative; several incompatible relative encodings)
- Feedback capability per control (none / LED on-off / LED colour / LED ring / motorised / LCD text)
- Mode/layer/bank semantics (device-side layer switching, e.g. X-Touch Compact's A/B button, vs. application-side banking)
- Touch sensitivity on faders (important for motor suppression)

---

## 4. MIDI fundamentals as they apply here

For reference, the message types that matter for control surfaces:

- **Note On / Note Off** — buttons, pads, and encoder push. Velocity 0–127 on Note On typically sets LED brightness or colour for feedback; Note Off (or Note On velocity 0) turns LEDs off.
- **Control Change (CC)** — faders, knobs, encoders. 7-bit value 0–127. A 14-bit variant using paired CCs (MSB/LSB) exists but is rarely used in hardware surfaces.
- **Pitch Bend** — sometimes used for high-resolution motorised faders (Mackie Control protocol does this). True 14-bit, one fader per channel.
- **Program Change** — occasionally used for bank/scene recall but rarely for continuous control.
- **SysEx** — vendor-specific. Used for LCD display text on Mackie-protocol devices, for device configuration, and for firmware updates.

Crucial detail for feedback: the same message that a device sends for a fader movement is typically the message it expects back to drive the motor. Echo suppression is essential — naive implementations create feedback loops where a user's fader movement is echoed back, fights the motor, and produces oscillation or unresponsiveness. The usual pattern is to track touch state (where supported) and/or to suppress outgoing feedback for a short window after receiving input on the same control.

---

## 5. Kotlin / JVM MIDI library options

### 5.1 `javax.sound.midi` (built-in)

Pros: zero dependencies, part of the JDK, well-documented, universally available. Enumerates devices, opens transmitters/receivers, sends and receives short messages and SysEx.

Cons: **macOS support is poor.** Hot-plugged devices are often not detected after JVM startup; the API caches device lists at first access. On Linux, it's a thin wrapper over ALSA rawmidi, which is functional but lacks virtual-port support. On Windows it's a reasonable wrapper over WinMM.

Suitable for a quick proof-of-concept; not suitable as the long-term backend on a multi-platform product.

### 5.2 CoreMIDI4J

A drop-in replacement for `javax.sound.midi` on macOS that uses CoreMIDI directly. Much more reliable device detection, supports hot-plug properly. Actively maintained, MIT license, single JAR.

The pragmatic path is often "`javax.sound.midi` on Windows and Linux, CoreMIDI4J on macOS." Code is identical because CoreMIDI4J implements the same API.

### 5.3 ktmidi

Kotlin Multiplatform MIDI library by atsushieno. Provides a MidiAccess abstraction modelled on the W3C Web MIDI API, covers MIDI 1.0 and MIDI 2.0, supports multiple backends including platform-native implementations and cross-platform RtMidi/libremidi wrappers. The `ktmidi-jvm-desktop` module offers AlsaMidiAccess (Linux ALSA sequencer, supports virtual ports), RtMidiAccess (cross-platform), and LibreMidiAccess (intended as the default cross-platform desktop implementation covering Linux, Mac, and Windows).

This is the most interesting option architecturally: if the backend moves to a ktmidi-based abstraction now, a future Compose Multiplatform frontend or Android companion app could share the same MIDI layer. Also provides a cleaner API than raw `javax.sound.midi`. Worth evaluating against what we actually need.

### 5.4 RtMidi via JNA/JNI wrappers

The C++ RtMidi library is the reference cross-platform MIDI library. JVM wrappers exist but are less common and less polished than the options above. ktmidi can sit on top of RtMidi, which is probably the right way to access it rather than a direct wrapper.

### 5.5 libremidi

The modern successor to RtMidi. Supports MIDI 2.0 UMP, supports network MIDI backends, actively maintained. ktmidi's `LibreMidiAccess` backend wraps it for the JVM. Potentially the best long-term foundation.

### 5.6 Recommendation for the doc's scope

The practical short-list is: a minimal spike on `javax.sound.midi` + CoreMIDI4J to validate the end-to-end pipeline quickly, then a probable migration to ktmidi (likely on the libremidi backend) once the abstraction stabilises. Decision deferred to design phase.

---

## 6. Bidirectional feedback — what has to be right

Feedback is what makes a motorised/LED surface worth the extra money. Getting it wrong is the difference between a controller that feels alive and one that feels broken. The key concerns the architecture needs to address:

**State ownership.** Application state is the source of truth. Physical surface positions are a view of that state. When state changes — whether from the surface, the web UI, a timeline playback, or an incoming MSC message — the surface is updated to match.

**Motor / touch coordination.** On surfaces with touch-sensitive motorised faders (X-Touch, Mackie Control), the touch message must suppress outgoing motor writes for that fader while touched. When touch releases, the motor can resume tracking. Without this, a user holding a fader fights the motor physically.

**Echo / feedback loop suppression.** Even without motorised faders, sending LED state back in response to a button press the user just made can create confusing behaviour if the device also interprets incoming messages as triggering the button again. Most surfaces don't do this, but the architecture should assume some will. Idempotent updates and short suppression windows are the usual solutions.

**Rate limiting.** Motorised surfaces can be overwhelmed by high-frequency updates during rapid state changes (e.g. a crossfade animating 8 submasters at 60 Hz). Update coalescing and throttling per control are worth designing in early.

**Initial sync.** When a surface first connects, or when the user switches banks/layers/pages, the surface must be fully updated to reflect current state. This is a noticeable "flurry of MIDI" moment that needs to be handled cleanly without blocking the event loop.

**Graceful degradation.** The same show should be runnable on a surface without motors or LEDs; missing feedback capabilities should not break functionality, only reduce polish.

---

## 7. Prior art — how others solve the device-agnostic problem

Several existing projects have grappled with the "support many surfaces cleanly" problem. Their solutions are worth studying.

### 7.1 QLC+ input profiles

QLC+ is the most directly relevant prior art: open-source, cross-platform lighting software with deep MIDI control surface support. Its MIDI plugin supports feedback to surfaces with a return channel (the BCF2000 is the documented example), and custom feedback values can be set to drive multi-colour LEDs on APC-family controllers.

Their abstraction layer is **input profiles** — XML files, one per device, that map channel+message-type to a logical name. Users can create profiles via a MIDI-learn process. Profiles are community-contributed; QLC+ ships with a large library covering most common surfaces. The application then binds logical names to functions (widgets, scenes, faders) separately. This two-level mapping (physical → logical, logical → function) is the right architectural pattern and worth adopting.

QLC+ also includes a "catch up with external controller value" option to handle physical/logical state mismatch after page changes — when a surface fader is at a different position than the application state expects, the application ignores incoming values until the physical fader crosses the application's current value. This is a well-known UX pattern (sometimes called "soft takeover" or "pickup mode") that we should plan for.

### 7.2 Bitwig controller scripts

Bitwig Studio takes a different approach: each supported controller has a JavaScript file that defines its behaviour programmatically. The script exposes a declarative API describing what controls the device has and how to translate incoming messages into Bitwig's internal events, and also how to send feedback. Users can modify scripts or write their own.

More flexible than static XML profiles because the mapping can encode stateful behaviour (modes, banks, complex encoders). Worth considering as a model if we want user-extensible device support without requiring application recompilation.

### 7.3 Ableton Live / Max for Live

Classic MIDI Learn: click a control in the UI, move a control on the surface, association is made. No device knowledge required. Works well for simple surfaces but doesn't scale to complex ones with LEDs and banks.

The lesson: MIDI Learn should exist as a quick-mapping affordance for simple cases, alongside richer profile-based configuration for devices the application knows about.

### 7.4 ReaLearn (for Reaper)

Arguably the most sophisticated MIDI-to-application mapping engine in any open-source music/media tool. Handles virtually every encoder encoding, implements soft takeover, supports complex conditional mappings, and is extensively documented. Worth reading even if we don't use its data model directly.

### 7.5 Ardour / Mixbus control surface support

Ardour has C++ plugins per surface family (Mackie, Faderport, Push 2, etc.) with a shared `ControlProtocol` base class. Heavier than QLC+'s XML profiles, lighter than Bitwig's JS scripts. A middle path worth considering if we end up supporting a small number of surfaces very deeply rather than many surfaces shallowly.

### 7.6 Pattern summary

Across all of these, the common architectural elements are:

- A **transport layer** (MIDI I/O, OSC I/O) that knows nothing about application semantics.
- A **device model** describing a specific piece of hardware — its controls, their message types, and their feedback capabilities.
- A **mapping layer** binding device controls to application functions, editable at runtime.
- **State reconciliation** logic that keeps physical and logical state in sync, including soft takeover.

A good first-pass abstraction probably has these four layers as distinct concerns.

---

## 8. The X-Touch Compact as a concrete example

Useful as a worked example because it exercises nearly every feature of the problem space.

- **Transport:** USB MIDI, single port, class-compliant (no drivers).
- **Two operating modes:** Standard (user-definable Note/CC per control) and MC (Mackie Control emulation). For our purposes, Standard mode is the right choice — we own both ends of the wire, so we don't benefit from emulating a DAW protocol, and Standard mode exposes the A/B layer switch which is useful.
- **Controls:** 9 touch-sensitive motorised 100 mm faders, 16 rotary push-encoders, 39 illuminated buttons, two pedal jacks.
- **Feedback:** motorised faders (pitch bend in MC mode, CC in Standard), encoder LED rings with multiple display styles, button LEDs, single-colour only.
- **Layers:** device-side A/B layer with a dedicated button. Maps cleanly onto "program" vs. "run" or two banks of functions.
- **Configuration:** Windows-only editor. However, **because we own the software on both ends, we can accept the factory defaults and do all mapping in our own code.** The editor becomes irrelevant for our purposes. This is an important principle for the abstraction: we should be able to support any MIDI-class-compliant surface out of the box, with no user-visible dependency on vendor editor software.

Exercises for the architecture from this one device:

- 14-bit vs 7-bit fader resolution handling (MC mode vs Standard mode).
- Touch-sensitive motor suppression.
- Encoder LED ring modes (more than just on/off).
- Device-side layer switching (application must track which layer is active on the device side).
- Persistence-free operation (configuration lives in our data, not on the device).

---

## 9. Platform and packaging considerations

The application already targets Windows distribution via jpackage, with planned macOS and Linux support. MIDI access has platform quirks worth noting now:

**Windows:** WinMM is the traditional API; exclusive device access per process (opening a MIDI port locks out other applications). No permission prompts, no signing requirements for MIDI itself.

**macOS:** CoreMIDI is the platform API. Shared-access by default (multiple apps can open the same device). No permission prompts for MIDI specifically, but the application bundle may need hardened runtime entitlements for USB device access when notarised. Network MIDI requires additional configuration. `javax.sound.midi` is known-flaky here; CoreMIDI4J or ktmidi is required for production.

**Linux:** ALSA is the platform API. ALSA sequencer provides rich functionality including virtual ports; ALSA rawmidi is simpler but more limited. `javax.sound.midi` on Linux uses rawmidi and is limited. No permission issues for MIDI, but users may need to be in the `audio` group.

**jpackage implications:** native libraries for CoreMIDI4J, libremidi, or RtMidi need to be bundled for each platform target. Straightforward but requires planning for the installer build matrix.

---

## 10. Network MIDI and the LAN / tablet opportunity

The existing roadmap already includes LAN access for iPad/iPhone via mDNS. Network MIDI (RTP-MIDI, also known as AppleMIDI) is a natural extension that deserves specific consideration:

- **Native support on macOS and iOS/iPadOS.** Apple's `Audio MIDI Setup` on Mac and `Network MIDI` on iOS are first-class OS features. iPad MIDI controller apps (TouchOSC, Loopy Pro, LK, many others) can talk to the application directly over Wi-Fi with no intermediate software.
- **Windows requires a helper.** rtpMIDI by Tobias Erichsen is the de facto standard, free, and widely deployed.
- **Libraries:** libremidi supports network MIDI backends; ktmidi inherits this when using the libremidi backend. pure Kotlin/Java RTP-MIDI implementations exist but are less mature.
- **mDNS integration:** the application is already planning mDNS for HTTP access; advertising a `_apple-midi._udp` service alongside the existing HTTP service is a small additional step that makes the MIDI surface discoverable without manual configuration.

This converges the "hardware control surface" and "tablet companion" stories onto the same event pipeline, which is architecturally pleasing and user-facing simple.

---

## 11. Open questions to resolve in design sessions

- **Profile format.** Static (XML/JSON/YAML) like QLC+? Scripted (Kotlin DSL, JS, or similar) like Bitwig? Hybrid?
- **Mapping UI.** Dedicated configuration screen? MIDI-Learn from within operating views? Both?
- **Scope of built-in device support.** Ship with profiles for N common devices, or ship with zero and make profile creation the primary workflow?
- **Bank/page/mode strategy.** Application-side only, device-side only, or both with coordination?
- **Concurrency model.** How does the MIDI event loop interact with the existing Ktor request flow and whatever event bus the backend uses? Coroutines or dedicated thread? Backpressure strategy for high-frequency feedback?
- **Persistence.** Where do surface mappings live? Per-show, per-user, or global? Exported with the show file?
- **Soft takeover policy.** Which controls need it, when is it enabled, how is state communicated to the operator?
- **OSC roadmap.** When does OSC become a first-class transport, and does that drive the abstraction shape now or later?
- **MSC support.** In-scope for v1 or deferred?

---

## 12. Summary

MIDI is the correct primary protocol. The JVM has adequate library support, with ktmidi (probably on a libremidi backend) the most interesting long-term foundation and `javax.sound.midi`+CoreMIDI4J an acceptable short-term path. The architecture must treat bidirectional feedback as a first-class concern rather than an afterthought, and needs a clean separation between transport, device model, and mapping. QLC+'s input profile pattern is the most directly relevant prior art and worth studying carefully. Network MIDI offers significant convergence with the already-planned LAN/tablet story. The X-Touch Compact is a representative device that exercises nearly every feature of the problem space and is a reasonable reference hardware for initial development.

The next step is architecture sessions focused on: the device abstraction layer, the mapping data model, and the event pipeline that bridges surface events to the existing application state.

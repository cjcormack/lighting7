package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe

/**
 * One **fixture command** (fixture optics plan session 7, D13): a level the fixture acts on when it
 * is held on a channel for a while — a reset, a lamp strike, a lamp off. Declared on a fixture with
 * [@FixtureCommand][uk.me.cormack.lighting7.fixture.FixtureCommand], which carries how long to hold
 * it.
 *
 * Deliberately **not** a [Slider][uk.me.cormack.lighting7.fixture.property.Slider] or a
 * [DmxFixtureSetting], and it carries no transaction: nothing writes a command through a fixture.
 * The desk holds it — `state/CommandOutput.kt`, above composition and under park — for its declared
 * time, then gives the channel back. Because it is not a property type, every path that resolves a
 * property by name (the programmer, a cue, a Look, an effect, Record) finds nothing here; the named
 * refusal at each write boundary (`fixture/CommandGuard.kt`) and the band guard at the output are
 * the other halves of "a reset is not something a Look can hold".
 *
 * A command's channel is either **dedicated** — no property of the fixture covers it (the Revolution's
 * reset channel, the Robe's control channel), so the desk owns it outright and holds it at
 * [idleLevel] between commands — or **shared** with a property (the MAC 250's shutter, the Orbit's
 * program channel), where composition drives it between commands and the output replaces any value
 * inside [bandMin]..[bandMax] with [idleLevel]. Which one is read from the fixture, never declared.
 *
 * @param level What the hold puts on the channel — inside the band.
 * @param bandMin The lowest level the fixture reads as this command (the manual's band; the level alone
 *   where the manual states none).
 * @param bandMax The highest.
 * @param idleLevel What a dedicated channel sits at between commands, and what a band value on a shared
 *   channel is sent as instead. Every command on one channel declares the same, which `FixtureCommandsTest`
 *   enforces.
 * @param alongside Channels the fixture needs at a level of their own while the command is held — a
 *   precondition its manual states (the MAC 250's reset wants the CTC filter, a static prism and the
 *   open gobo). The desk sets them for the hold and gives them back with the command channel.
 */
class DmxCommand(
    val universe: Universe,
    val channelNo: Int,
    val level: UByte,
    val bandMin: UByte = level,
    val bandMax: UByte = level,
    val idleLevel: UByte = 0u,
    val alongside: List<DmxChannelHold> = emptyList(),
) {
    init {
        require(level in bandMin..bandMax) { "A command's level $level must sit in its band $bandMin..$bandMax" }
        require(idleLevel !in bandMin..bandMax) { "A command's idle level $idleLevel must sit outside its band $bandMin..$bandMax" }
    }
}

/** One precondition channel a [DmxCommand] holds for its duration, and why the manual wants it. */
data class DmxChannelHold(
    val channelNo: Int,
    val level: UByte,
    /** For the panel and `describe_rig`: "CTC filter in". */
    val why: String,
)

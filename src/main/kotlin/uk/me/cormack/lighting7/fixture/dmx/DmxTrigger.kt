package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe

/**
 * The channels of one **one-shot trigger** (stage-view plan session 9, D15): the channel that fires
 * it and the channel that arms it. Declared on a fixture with
 * [@FixtureTrigger][uk.me.cormack.lighting7.fixture.FixtureTrigger].
 *
 * Deliberately **not** a [Slider][uk.me.cormack.lighting7.fixture.property.Slider] and carries no
 * transaction: nothing writes a trigger through a fixture. Its two channels are owned by
 * [TriggerOutput][uk.me.cormack.lighting7.state.TriggerOutput], which holds the fire channel idle
 * except for one backend-timed pulse and the arm channel at the desk's arm — above composition,
 * under park. Because it is not a property type, every path that resolves a property by name (the
 * programmer, a cue, a Look, an effect, Record) finds nothing here, which is the structural half of
 * "a value that spends a cartridge must not be something a look can hold". The named refusal at
 * each write boundary is the other half (`fixture/TriggerGuard.kt`).
 *
 * Several triggers may share one [armChannelNo] — the Twin Shot's two tubes have one master.
 */
class DmxTrigger(
    val universe: Universe,
    val channelNo: Int,
    val armChannelNo: Int,
)

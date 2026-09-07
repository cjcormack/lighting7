package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * What a [BindingTarget.SelectionProperty] move writes: one programmer write per selected head.
 * A pure function of the patch and the selection, so the fan-out is testable without a show.
 *
 * A fixture in the selection is one write; a group fans to its members with `sourceGroup` set,
 * exactly as `DefaultSurfaceActions.writeGroupProperty` fans a fixed group binding, so a later
 * Record can collapse the entries back to a group row. A head that lacks the property, or a
 * target that no longer resolves, is skipped — as is a head on which the property is not a colour
 * when a colour axis is named — the write reaches the heads it can. An empty
 * selection yields no writes; the caller drops the move rather than widening it to
 * "everything" (`docs/plans/completed/midi-surface-plan.md` D3).
 */
object SelectionWrites {
    fun forTargets(
        fixtures: Fixtures,
        targets: List<CueTargetDto>,
        propertyName: String,
        midiValue7Bit: UByte,
        colourAxis: ColourAxis? = null,
    ): List<ProgrammerWriter.PropertyWrite> {
        val writes = ArrayList<ProgrammerWriter.PropertyWrite>()
        val seen = HashSet<String>()
        // A colour axis is written onto each head's own current colour, so the write reads the show.
        val read = PropertyChannelResolver.channelReader(fixtures)
        for (target in targets) {
            when (TargetRef.ofOrNull(target.type, target.key)) {
                is TargetRef.Fixture -> {
                    val fixture = runCatching { fixtures.untypedFixture(target.key) }.getOrNull() ?: continue
                    if (!seen.add(fixture.key)) continue
                    write(fixture, propertyName, midiValue7Bit, colourAxis, read, sourceGroup = null)?.let(writes::add)
                }
                is TargetRef.Group -> {
                    val group = runCatching { fixtures.untypedGroup(target.key) }.getOrNull() ?: continue
                    for (member in group.fixtures.filterIsInstance<Fixture>()) {
                        if (!seen.add(member.key)) continue
                        write(member, propertyName, midiValue7Bit, colourAxis, read, sourceGroup = target.key)?.let(writes::add)
                    }
                }
                null -> continue
            }
        }
        return writes
    }

    private fun write(
        fixture: Fixture,
        propertyName: String,
        midiValue7Bit: UByte,
        colourAxis: ColourAxis?,
        read: ChannelReader,
        sourceGroup: String?,
    ): ProgrammerWriter.PropertyWrite? =
        PropertyChannelResolver.toPropertyValue(fixture, propertyName, midiValue7Bit, read, colourAxis)?.let {
            ProgrammerWriter.PropertyWrite(fixture, propertyName, it, sourceGroup = sourceGroup)
        }
}

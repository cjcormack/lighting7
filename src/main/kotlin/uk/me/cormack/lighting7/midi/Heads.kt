package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.PaletteFanOut
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * The heads a surface write on one attribute reaches (surface-setup plan §3 "Heads"): the one
 * expansion every fixture function, the flash, the bind-time colour judgement and the feedback read
 * share, so what a control writes and what it reads back cannot disagree.
 *
 * A fixture is itself, an element key its element, a group its members (elements included) tagged
 * with the group as `sourceGroup`, and the selection each of its targets so. A cell named beside its
 * parent is dropped, the parent covering it (`/programmer/fan`'s HEADS rule); then a multi-head
 * fixture whose [attribute] lives only on its cells — the Shehds' colour — stands for every cell that
 * carries it ([PaletteFanOut.headsFor]). Distinct by key, the first spelling winning. A target that
 * does not resolve reaches nothing.
 */
object Heads {
    /** One head and the group a write to it is recorded under, or null. */
    data class Head(val fixture: GroupableFixture, val sourceGroup: String? = null)

    fun of(
        fixtures: Fixtures,
        subject: Subject,
        attribute: String,
        selection: () -> List<CueTargetDto> = { emptyList() },
    ): List<Head> {
        val targets: List<CueTargetDto> = when (subject) {
            is Subject.Fixture, is Subject.Group -> listOfNotNull(subject.asTarget())
            Subject.Selection -> selection()
            Subject.None, Subject.Slot, is Subject.Record -> return emptyList()
        }
        val units = LinkedHashMap<String, Head>()
        for (target in targets) {
            when (TargetRef.ofOrNull(target.type, target.key)) {
                is TargetRef.Group -> {
                    val group = runCatching { fixtures.untypedGroup(target.key) }.getOrNull() ?: continue
                    for (member in group.fixtures) units.putIfAbsent(member.targetKey, Head(member, target.key))
                }
                is TargetRef.Fixture -> {
                    val head = runCatching { fixtures.untypedGroupableFixture(target.key) }.getOrNull() ?: continue
                    units.putIfAbsent(head.targetKey, Head(head))
                }
                null -> continue
            }
        }
        units.values.removeIf { val f = it.fixture; f is FixtureElement<*> && f.parentFixture.key in units }
        val heads = LinkedHashMap<String, Head>()
        for (unit in units.values) {
            for (head in PaletteFanOut.headsFor(unit.fixture, attribute, fixtures::fittedMediaFor)) {
                heads.putIfAbsent(head.targetKey, Head(head, unit.sourceGroup))
            }
        }
        return heads.values.toList()
    }
}

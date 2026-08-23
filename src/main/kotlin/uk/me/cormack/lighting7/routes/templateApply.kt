package uk.me.cormack.lighting7.routes

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.TemplateSnapshot
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("templateApply")

/**
 * The plain-click gesture: resolve a template against a selection and write the results into the
 * programmer as **literals**.
 *
 * Three things about it are the design rather than the implementation:
 *
 *  - **Literals, not a dependency.** Retuning the template afterwards must not move what was
 *    applied. That is the whole difference between clicking a template chip and ⌥clicking it: click
 *    is "this colour, now", ⌥click adds a layer that tracks it. `Templates.dc.html` draws both.
 *  - **Resolution happens here, server-side**, through the same [TemplateResolver] the cook uses.
 *    The client could have resolved a colour itself and written channels — it already has a batch
 *    write path — but then the white/amber policy and the wheel snap would exist in TypeScript as
 *    well as Kotlin, and the editor's promise about what the rig will do would be a coincidence
 *    rather than a guarantee.
 *  - **`ProgrammerOwner.WEB`**, not `INCLUDE`. An applied template is the operator's own busked
 *    value — Record must take it and Clear must release it, exactly as if they had dragged the
 *    cell — whereas `INCLUDE` marks the edit buffer a Look or cue was loaded into. Using INCLUDE
 *    here would also overwrite `lastIncludedTarget`, so applying a colour would silently change
 *    what Update writes back to.
 */
internal fun applyTemplateToProgrammer(
    state: State,
    template: TemplateSnapshot,
    targets: List<CueTargetDto>,
    fadeMs: Long,
): ApplyTemplateResponse {
    val fixtureKeys = expandTargetsToFixtureKeys(state, targets)
    if (fixtureKeys.isEmpty()) return ApplyTemplateResponse(0, emptyList())

    // The group each member arrived through, so a slot keeps the operator's group shape and a later
    // Record can collapse back to a group row — the same hint Include stamps.
    val groupHints = groupHintsForTargets(state.show.fixtures, targets.map { it.target })

    val writes = ArrayList<FxEngine.ProgrammerPropertyWrite>()
    val skips = ArrayList<TemplateSkipDto>()

    for (row in template.rows) {
        val intent = parseTemplateIntent(row.value)
        if (intent == null) {
            logger.warn("template '{}': row '{}' is not an intent — skipping", template.name, row.value)
            continue
        }
        // A per-fixture row applies only to the head it names, *and* only if that head is in the
        // selection: applying a focus position to two of its eight heads must move those two and
        // leave the rest alone.
        val applyTo: List<String> = when (val target = row.target) {
            is TargetRef.Fixture -> listOf(target.key).filter { it in fixtureKeys }
            else -> fixtureKeys.toList()
        }
        for (fixtureKey in applyTo) {
            val fixture: GroupableFixture? = runCatching {
                state.show.fixtures.untypedGroupableFixture(fixtureKey)
            }.getOrNull()
            if (fixture == null) {
                skips += TemplateSkipDto(fixtureKey, row.propertyName, "fixture not patched")
                continue
            }
            val resolution = TemplateResolver.resolve(fixture, row.propertyName, intent)
            val value = resolution.value
            if (value == null) {
                skips += TemplateSkipDto(
                    fixtureKey,
                    row.propertyName,
                    (resolution.note as? TemplateResolver.Note.Unsupported)?.reason ?: "unsupported",
                )
                continue
            }
            writes += FxEngine.ProgrammerPropertyWrite(
                fixture,
                // The resolved name, not the row's: on a colour wheel the value lands on `colour`
                // rather than `rgbColour`, and writing the row's name would address nothing.
                resolution.propertyName,
                value,
                sourceGroup = groupHints[fixtureKey],
            )
        }
    }

    if (writes.isNotEmpty()) {
        // One batched write, as Include does: a template across a whole rig is hundreds of
        // properties and per-property publishing would visibly stutter it.
        state.show.fxEngine.writeProgrammerProperties(ProgrammerOwner.WEB, writes, fadeMs = fadeMs)
    }
    return ApplyTemplateResponse(writes.size, skips)
}

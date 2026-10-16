package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.groupHintsForTargets
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerClearTargetInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.routes.ApplyTemplateOutcome
import uk.me.cormack.lighting7.routes.CODE_TEMPLATE_NEEDS_SELECTION
import uk.me.cormack.lighting7.routes.CODE_TEMPLATE_OUTSIDE_MASK
import uk.me.cormack.lighting7.routes.RecordLookCoreResult
import uk.me.cormack.lighting7.routes.RecordMode
import uk.me.cormack.lighting7.routes.RecordSource
import uk.me.cormack.lighting7.routes.TemplatePressLog
import uk.me.cormack.lighting7.routes.applyTemplateToProgrammer
import uk.me.cormack.lighting7.routes.clearProgrammerCompletely
import uk.me.cormack.lighting7.routes.familyOf
import uk.me.cormack.lighting7.routes.isGenericTemplate
import uk.me.cormack.lighting7.routes.parseEnumOrNull
import uk.me.cormack.lighting7.routes.performProgrammerRecordLook
import uk.me.cormack.lighting7.routes.pressWouldRelease
import uk.me.cormack.lighting7.routes.templateOutsideMaskMessage
import uk.me.cormack.lighting7.state.State

/** The longest fade a programmer tool takes, in seconds. */
private const val MAX_FADE_SECONDS = 600.0

/**
 * The programmer tools that write it: `set_programmer`, `clear_programmer`, `set_blind`,
 * `apply_palette` and `record_look`. Each is a door onto the code a desk gesture already goes
 * through — the picker's WEB write, Clear and Release, the Blind switch, the template chip's click
 * and ⌥click, Record to Look — so a model and the operator cannot get two different answers.
 * All act on the current project.
 */
internal class ProgrammerTools(private val state: State) {

    fun setProgrammer(input: JsonObject): ToolExecutionResult = guarded { setProgrammerUnguarded(input) }

    private fun setProgrammerUnguarded(input: JsonObject): ToolExecutionResult {
        val targets = parseTargets(input, required = true).getOrElse { return errorResult(it.message ?: "Bad arguments") }!!
        val values = input.present("values") as? JsonArray ?: return errorResult("Missing 'values': [{propertyName, value}]")
        val fadeMs = fadeMs(input).getOrElse { return errorResult(it.message ?: "Bad arguments") }
        val fixtures = state.show.fixtures

        val refusals = targets.flatMap { target ->
            values.mapNotNull { (it as? JsonObject)?.text("propertyName") }.mapNotNull { propertyName ->
                TriggerIndex.refusalLive(fixtures, target.discriminator, target.key, propertyName, "programmer")
                    ?: CommandIndex.refusalLive(fixtures, target.discriminator, target.key, propertyName, "programmer")
            }
        }
        if (refusals.isNotEmpty()) return errorResult(refusals.distinct().joinToString("; "))

        val resolved = resolveProgrammerValues(fixtures, targets, values)
        if (resolved.problems.isNotEmpty()) return errorResult(resolved.problems.joinToString("; "))

        // The group each head arrived through, so Record collapses it back to a group row.
        val groupHints = groupHintsForTargets(fixtures, targets)
        val writes = resolved.writes.map {
            ProgrammerWriter.PropertyWrite(it.head, it.propertyName, it.value, sourceGroup = groupHints[it.head.targetKey])
        }
        val landed = state.show.fxEngine.programmer.writeProperties(ProgrammerOwner.WEB, writes, fadeMs = fadeMs)

        val written = ArrayList<ProgrammerValueWrite>()
        val skipped = resolved.skipped.toMutableList()
        resolved.writes.forEachIndexed { index, write ->
            if (landed[index].isEmpty()) {
                skipped += LookValueSkip(write.head.targetKey, write.requestedName, "${write.propertyName} resolves to no DMX channels")
            } else {
                written += write
            }
        }
        return ToolExecutionResult(
            success = true,
            description = "Set ${written.size} value(s) in the programmer" +
                (if (skipped.isNotEmpty()) ", ${skipped.size} skipped" else ""),
            result = buildJsonObject {
                put("written", buildJsonArray {
                    for (write in written) {
                        addJsonObject {
                            put("targetKey", write.head.targetKey)
                            put("propertyName", write.propertyName)
                            put("value", write.value.serialize())
                            groupHints[write.head.targetKey]?.let { put("group", it) }
                        }
                    }
                })
                putValueSkips(skipped)
            }.toString(),
        )
    }

    fun clearProgrammer(input: JsonObject): ToolExecutionResult = guarded { clearProgrammerUnguarded(input) }

    private fun clearProgrammerUnguarded(input: JsonObject): ToolExecutionResult {
        val targets = parseTargets(input, required = false).getOrElse { return errorResult(it.message ?: "Bad arguments") }
        val fadeMs = fadeMs(input).getOrElse { return errorResult(it.message ?: "Bad arguments") }

        if (targets != null && targets.isEmpty()) return errorResult("'targets' is empty: omit it to clear everything")
        if (targets == null) {
            val cleared = clearProgrammerCompletely(state, fadeMs)
            return ToolExecutionResult(
                success = true,
                description = "Cleared the programmer — ${cleared.entryCount} value(s), ${cleared.effectsCleared} effect(s)",
                result = buildJsonObject {
                    put("valuesCleared", cleared.entryCount)
                    put("effectsCleared", cleared.effectsCleared)
                }.toString(),
            )
        }

        // Every target checked before any is cleared, so a typo leaves the programmer as it was.
        val fixtures = state.show.fixtures
        val unknown = targets.mapNotNull { target ->
            val found = runCatching {
                when (target) {
                    is TargetRef.Fixture -> fixtures.untypedGroupableFixture(target.key)
                    is TargetRef.Group -> fixtures.untypedGroup(target.key)
                }
            }.isSuccess
            if (found) null else "no ${target.discriminator} '${target.key}' is patched"
        }
        if (unknown.isNotEmpty()) return errorResult(unknown.joinToString("; "))

        val answers = targets.map { target ->
            ProgrammerHandler.clearTarget(state, ProgrammerClearTargetInMessage(target.discriminator, target.key, fadeMs))
        }
        val failed = answers.mapNotNull { it.error }
        return ToolExecutionResult(
            success = failed.isEmpty(),
            description = if (failed.isEmpty()) {
                "Cleared ${answers.sumOf { it.values }} value(s) and ${answers.sumOf { it.effects }} effect(s) " +
                    "on ${targets.joinToString { it.key }}"
            } else {
                failed.joinToString("; ")
            },
            result = buildJsonObject {
                put("cleared", buildJsonArray {
                    for (answer in answers) {
                        addJsonObject {
                            put("type", answer.targetType)
                            put("key", answer.targetKey)
                            put("values", answer.values)
                            put("effects", answer.effects)
                            answer.error?.let { put("error", it) }
                            if (answer.partial.isNotEmpty()) {
                                put("partial", buildJsonArray {
                                    for (p in answer.partial) {
                                        addJsonObject {
                                            put("effectId", p.effectId)
                                            put("effectType", p.effectType)
                                            put("targetKey", p.targetKey)
                                            put("propertyName", p.propertyName)
                                        }
                                    }
                                })
                            }
                        }
                    }
                })
            }.toString(),
        )
    }

    /** The WS `programmer.setBlind` branch, call for call. */
    fun setBlind(input: JsonObject): ToolExecutionResult = guarded { setBlindUnguarded(input) }

    private fun setBlindUnguarded(input: JsonObject): ToolExecutionResult {
        val blind = input.flag("blind") ?: return errorResult("Missing 'blind': true or false")
        val fadeMs = fadeMs(input).getOrElse { return errorResult(it.message ?: "Bad arguments") }
        state.show.fxEngine.programmer.setBlind(blind, fadeMs)
        state.sceneryService.onBlindChanged()
        state.effectsService.onBlindChanged()
        val now = state.show.programmerStore.blind
        return ToolExecutionResult(
            success = true,
            description = if (now) "Blind on: the programmer is off stage" else "Blind off: the programmer is on stage",
            result = buildJsonObject { put("blind", now) }.toString(),
        )
    }

    fun applyTemplate(input: JsonObject): ToolExecutionResult = guarded { applyTemplateUnguarded(input) }

    private fun applyTemplateUnguarded(input: JsonObject): ToolExecutionResult {
        val project = state.projectManager.currentProject
        // A layer that tracks the palette by default, as the desk's press is (apply-model D9). The
        // `mode` this replaced is refused rather than ignored: ignored, LITERALS would toggle a layer.
        if (input.present("mode") != null) {
            return errorResult("'mode' is gone: apply_palette puts on a layer by default; pass asValues: true for literals")
        }
        val layer = input.flag("asValues") != true
        val requested = parseTargets(input, required = false).getOrElse { return errorResult(it.message ?: "Bad arguments") }.orEmpty()
        val families = maskOf(input)
        val fadeMs = if (input.present("fadeSeconds") == null) null else fadeMs(input).getOrElse { return errorResult(it.message ?: "Bad arguments") }

        val templateId = input.integer("paletteId")
        val templateName = input.text("paletteName")?.trim()?.takeIf { it.isNotEmpty() }
        if ((templateId == null) == (templateName == null)) return errorResult("Give exactly one of paletteId or paletteName")

        val found = transaction(state.database) {
            val template = if (templateId != null) {
                DaoTemplate.findById(templateId)?.takeIf { it.project.id == project.id }
            } else {
                DaoTemplate.find { (DaoTemplates.project eq project.id) and (DaoTemplates.name eq templateName!!) }.firstOrNull()
            }
            template?.let {
                Found(
                    source = LayerSource.template(it.id.value, it.uuid, it.name),
                    family = it.familyOf(),
                    isGeneric = it.isGenericTemplate(),
                )
            }
        } ?: return errorResult(
            if (templateId != null) "Palette not found: $templateId" else "No palette named '$templateName' in this project",
        )

        if (found.isGeneric && requested.isEmpty()) {
            return errorResult("'${found.source.name}' is generic, so it needs targets to land on", CODE_TEMPLATE_NEEDS_SELECTION)
        }
        val targets = requested.map { CueTargetDto(it.discriminator, it.key) }
        return if (layer) toggleTemplate(project.id.value, found, targets, families)
        else applyTemplateLiterals(project.id.value, found, targets, families, fadeMs)
    }

    private class Found(val source: LayerSource, val family: PropertyMaskGroup?, val isGeneric: Boolean)

    /** The press: the toggle route's path, with no solo siblings. */
    private fun toggleTemplate(
        projectId: Int,
        found: Found,
        targets: List<CueTargetDto>,
        families: Set<PropertyMaskGroup>?,
    ): ToolExecutionResult {
        if (families != null && !maskAllows(families, found.family) && !pressWouldRelease(state, found.source.uuid, targets)) {
            return errorResult(templateOutsideMaskMessage(found.source.name, found.family, families), CODE_TEMPLATE_OUTSIDE_MASK)
        }
        val outcome = try {
            state.show.programmerLayerStack.toggle(source = found.source, targets = targets, propertyMask = found.family?.name)
        } catch (e: IllegalStateException) {
            return errorResult(e.message ?: "Target not found")
        }
        if (outcome.action == "applied") TemplatePressLog.record(state, projectId, found.source.id)
        return ToolExecutionResult(
            success = true,
            description = "${outcome.action} palette '${found.source.name}' as a layer",
            result = buildJsonObject {
                put("asValues", false)
                put("action", outcome.action)
                put("effectCount", outcome.effectCount)
                if (outcome.released > 0) put("released", outcome.released)
                found.family?.let { put("propertyMask", it.name) }
            }.toString(),
        )
    }

    /** `asValues`: literals (or a detached effect copy) through [applyTemplateToProgrammer]. */
    private fun applyTemplateLiterals(
        projectId: Int,
        found: Found,
        requested: List<CueTargetDto>,
        families: Set<PropertyMaskGroup>?,
        fadeMs: Long?,
    ): ToolExecutionResult {
        val snapshot = state.show.templateRegistry.snapshot(found.source.uuid)
            ?: return errorResult("Palette '${found.source.name}' is not loaded yet; try again")
        // A per-fixture template with no targets lands on its own heads, as its pad press does.
        val targets = requested.ifEmpty {
            snapshot.rows.mapNotNull { it.target as? TargetRef.Fixture }.distinct()
                .map { CueTargetDto(TargetRef.Fixture.TYPE, it.key) }
        }
        if (targets.isEmpty() || (requested.isEmpty() && snapshot.rows.any { it.isDeferred })) {
            return errorResult("'${found.source.name}' has generic rows, so it needs targets to land on", CODE_TEMPLATE_NEEDS_SELECTION)
        }
        return when (
            val outcome = applyTemplateToProgrammer(
                state, projectId, snapshot, targets, fadeMs ?: snapshot.fadeDurationMs ?: 0, families,
            )
        ) {
            is ApplyTemplateOutcome.Refused -> errorResult(outcome.message, outcome.code)
            is ApplyTemplateOutcome.Applied -> {
                val response = outcome.response
                ToolExecutionResult(
                    success = true,
                    description = if (response.effectIds.isNotEmpty()) {
                        "Started ${response.effectIds.size} copy(ies) of '${found.source.name}''s effect"
                    } else {
                        "Applied '${found.source.name}': ${response.written} value(s)" +
                            (if (response.skipped.isNotEmpty()) ", ${response.skipped.size} skipped" else "")
                    },
                    result = buildJsonObject {
                        put("asValues", true)
                        put("written", response.written)
                        if (response.effectIds.isNotEmpty()) {
                            put("effectIds", buildJsonArray { response.effectIds.forEach { add(JsonPrimitive(it)) } })
                        }
                        putValueSkips(response.skipped.map { LookValueSkip(it.fixtureKey, it.propertyName, it.reason) })
                    }.toString(),
                )
            }
        }
    }

    fun recordLook(input: JsonObject): ToolExecutionResult = guarded { recordLookUnguarded(input) }

    private fun recordLookUnguarded(input: JsonObject): ToolExecutionResult {
        val modeName = input.text("mode") ?: "CREATE"
        val mode = parseEnumOrNull<RecordMode>(modeName) ?: return errorResult("Unknown record mode '$modeName'")
        val sourceName = input.text("source") ?: "TOUCHED"
        val source = parseEnumOrNull<RecordSource>(sourceName) ?: return errorResult("Unknown record source '$sourceName'")
        val mask = maskOf(input)
        val targets = parseTargets(input, required = false).getOrElse { return errorResult(it.message ?: "Bad arguments") }
        val effectIds = input.present("effectIds")?.let { ids ->
            (ids as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull ?: return errorResult("effectIds must be integers") }
                ?: return errorResult("effectIds must be an array of integers")
        }.orEmpty()

        val result = performProgrammerRecordLook(
            state,
            state.projectManager.currentProject,
            mode = mode,
            source = source,
            lookId = input.integer("lookId"),
            name = input.text("name"),
            notes = input.text("notes"),
            mask = mask,
            targets = targets?.map { CueTargetDto(it.discriminator, it.key) },
            effectIds = effectIds,
            scenery = input.flag("scenery") ?: true,
        )
        return when (result) {
            is RecordLookCoreResult.Failure -> errorResult(result.message)
            is RecordLookCoreResult.Ok -> {
                val r = result.response
                ToolExecutionResult(
                    success = true,
                    description = (if (r.created) "Recorded new look" else "Recorded into look") +
                        " '${r.look.name}' (id=${r.look.id}) — ${r.rowsWritten} row(s) written, ${r.rowsRemoved} removed",
                    result = buildJsonObject {
                        put("lookId", r.look.id)
                        put("name", r.look.name)
                        put("created", r.created)
                        put("rowsWritten", r.rowsWritten)
                        put("rowsRemoved", r.rowsRemoved)
                        put("effectsWritten", r.effectsWritten)
                        put("sceneryWritten", r.sceneryWritten)
                        put("sceneryRemoved", r.sceneryRemoved)
                        put("rows", buildJsonArray {
                            for (row in r.look.rows) {
                                addJsonObject {
                                    put("targetType", row.targetType)
                                    put("targetKey", row.targetKey)
                                    row.elementKey?.let { put("elementKey", it) }
                                    put("propertyName", row.propertyName)
                                    put("value", row.value)
                                }
                            }
                        })
                        if (r.skipped.isNotEmpty()) {
                            put("skipped", buildJsonArray {
                                for (skip in r.skipped) {
                                    addJsonObject {
                                        skip.targetKey?.let { put("targetKey", it) }
                                        skip.propertyName?.let { put("propertyName", it) }
                                        put("reason", skip.reason)
                                    }
                                }
                            })
                        }
                    }.toString(),
                )
            }
        }
    }

    /**
     * The `targets` argument as [TargetRef]s: null when absent and not [required], a failure naming
     * the entry when one is malformed.
     */
    private fun parseTargets(input: JsonObject, required: Boolean): Result<List<TargetRef>?> = runCatching {
        val array = when (val raw = input.present("targets")) {
            null -> if (required) throw IllegalArgumentException("Missing 'targets': [{type: 'fixture' | 'group', key}]") else return@runCatching null
            is JsonArray -> raw
            else -> throw IllegalArgumentException("'targets' must be an array of {type: 'fixture' | 'group', key}")
        }
        array.mapIndexed { index, element ->
            val obj = element as? JsonObject
            val type = (obj?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val key = (obj?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.content
            (if (type != null && key != null) TargetRef.ofOrNull(type, key) else null)
                ?: throw IllegalArgumentException("targets[$index] must be {type: 'fixture' | 'group', key}")
        }
    }

    private fun fadeMs(input: JsonObject): Result<Long> = runCatching {
        val raw = input.present("fadeSeconds") ?: return@runCatching 0L
        val seconds = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?: throw IllegalArgumentException("fadeSeconds must be a number")
        require(seconds in 0.0..MAX_FADE_SECONDS) { "fadeSeconds must be between 0 and ${MAX_FADE_SECONDS.toInt()}, got $seconds" }
        (seconds * 1000).toLong()
    }

    private fun JsonObjectBuilder.putValueSkips(skips: List<LookValueSkip>) {
        if (skips.isEmpty()) return
        put("skipped", buildJsonArray {
            for (skip in skips) {
                addJsonObject {
                    put("targetKey", skip.target)
                    put("propertyName", skip.propertyName)
                    put("reason", skip.reason)
                }
            }
        })
    }

    /** A wrongly typed argument is thrown by the readers below and answered here, by name. */
    private inline fun guarded(block: () -> ToolExecutionResult): ToolExecutionResult = try {
        block()
    } catch (e: IllegalArgumentException) {
        errorResult(e.message ?: "Bad arguments")
    }

    /** [key]'s value, with an explicit JSON null read as absent. */
    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonObject.text(key: String): String? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("'$key' must be a string")
    }

    private fun JsonObject.integer(key: String): Int? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: throw IllegalArgumentException("'$key' must be an integer")
    }

    private fun JsonObject.flag(key: String): Boolean? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw IllegalArgumentException("'$key' must be true or false")
    }

    private fun maskOf(input: JsonObject): Set<PropertyMaskGroup>? {
        val names = input.present("mask")?.let { mask ->
            (mask as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("'mask' entries must be family names")
            } ?: throw IllegalArgumentException("'mask' must be an array of family names")
        }
        return parseMaskGroups(names)
    }

    private fun errorResult(message: String, code: String? = null) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject {
            put("error", message)
            code?.let { put("code", it) }
        }.toString(),
    )
}

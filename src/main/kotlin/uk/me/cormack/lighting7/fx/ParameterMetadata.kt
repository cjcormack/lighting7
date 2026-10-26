package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve

/**
 * The rules a parameter's editor metadata keeps (fx-settings plan D1), and the one parameter type
 * that leans on it, `choice`. A built-in's frontmatter that breaks one fails to load, and a user FX
 * definition that breaks one is refused, so the editor never meets a range it cannot draw.
 */

/** A parameter whose value is one of its declared [ParameterInfo.options]. */
const val CHOICE_TYPE = "choice"

private val NUMERIC_TYPES = setOf("ubyte", "int", "double", "float")

/**
 * How a stored value reads, and the types each unit may read: `%` a byte level as a percent of full,
 * `position` a byte on an axis about its centre, `ratio` a 0–1 share as a percent, `dmx` DMX steps and
 * `ms` milliseconds of any number.
 */
private val UNIT_TYPES = mapOf(
    "%" to setOf("ubyte"),
    "position" to setOf("ubyte"),
    "ratio" to setOf("double", "float"),
    "dmx" to NUMERIC_TYPES,
    "ms" to NUMERIC_TYPES,
)

val PARAMETER_UNITS: Set<String> = UNIT_TYPES.keys

val PARAMETER_ROLES = setOf("main", "detail")

/** Every problem with [parameters]' metadata, each naming its parameter; empty when there is none. */
fun parameterMetadataProblems(parameters: List<ParameterInfo>): List<String> {
    val byName = parameters.associateBy { it.name }
    val problems = mutableListOf<String>()
    parameters.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { problems += "parameter '$it' twice" }
    val paired = mutableSetOf<String>()
    for (p in parameters) {
        val at = "parameter '${p.name}'"
        p.unit?.let { unit ->
            val types = UNIT_TYPES[unit]
            when {
                types == null -> problems += "$at: unit '$unit' is not one of ${PARAMETER_UNITS.joinToString()}"
                p.type !in types -> problems += "$at: unit '$unit' reads a ${types.joinToString(" or ")}, not a ${p.type}"
            }
        }
        p.role?.let { if (it !in PARAMETER_ROLES) problems += "$at: role '$it' is not one of ${PARAMETER_ROLES.joinToString()}" }
        if (p.min != null && p.max != null && p.min > p.max) problems += "$at: min ${p.min} is above max ${p.max}"
        p.step?.let { if (it <= 0.0) problems += "$at: step must be above 0" }
        if ((p.min != null || p.max != null || p.step != null) && p.type !in NUMERIC_TYPES) {
            problems += "$at: a range needs a number, not a ${p.type}"
        }
        val options = p.options
        if (p.type == CHOICE_TYPE) {
            when {
                options.isNullOrEmpty() -> problems += "$at: a choice needs options"
                choiceOption(p, p.defaultValue) == null -> problems += "$at: default '${p.defaultValue}' is not one of its options"
            }
        } else if (options != null && p.type != "easingCurve") {
            problems += "$at: options are a choice's, not a ${p.type}'s"
        }
        options?.groupBy { it.value.uppercase() }?.filter { it.value.size > 1 }?.keys?.forEach { problems += "$at: option '$it' twice" }
        options?.filter { it.value.isBlank() || it.label.isBlank() }?.forEach { _ -> problems += "$at: an option needs a value and a label" }
        p.shownWhen?.let { condition ->
            val other = byName[condition.param]
            when {
                other == null || other === p -> problems += "$at: shownWhen names no other parameter '${condition.param}'"
                condition.values.isEmpty() -> problems += "$at: shownWhen names no values"
                else -> other.options?.let { declared ->
                    val known = declared.map { it.value }.toSet()
                    condition.values.filter { it !in known }.forEach { problems += "$at: shownWhen value '$it' is not an option of '${other.name}'" }
                }
            }
        }
        p.pair?.let { high ->
            val other = byName[high]
            when {
                other == null || other === p -> problems += "$at: pair names no other parameter '$high'"
                other.type != p.type || p.type !in NUMERIC_TYPES -> problems += "$at: pair '$high' must be a number of the same type"
                !paired.add(p.name) || !paired.add(high) -> problems += "$at: a parameter belongs to one pair"
            }
        }
    }
    return problems
}

/**
 * [value] as one of [param]'s options — matched ignoring case, answered in the option's own spelling
 * — or null when no option matches.
 */
fun choiceOption(param: ParameterInfo, value: String): String? =
    param.options?.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }?.value

/**
 * Why [parameters] are refused for an effect of [schema], or null: a `choice` holding a value none
 * of its options is. Every write boundary asks it, through `routes/storedFxRules.kt`'s `fxWriteProblem`.
 * A choice with no options — one an older desk's sync stripped — takes any value, as [TypedParams.choice] reads it.
 */
fun choiceProblem(schema: List<ParameterInfo>, parameters: Map<String, String>): String? {
    for (p in schema) {
        if (p.type != CHOICE_TYPE || p.options.isNullOrEmpty()) continue
        val value = parameters[p.name]?.takeIf { it.isNotBlank() } ?: continue
        if (choiceOption(p, value) == null) {
            return "'${p.name}' is one of ${p.options.orEmpty().joinToString { it.value }}, not '$value'"
        }
    }
    return null
}

/** [choiceProblem] for the effect type [fxType] names; null for a type this desk lacks. */
fun FxRegistry.choiceProblem(fxType: String, parameters: Map<String, String>): String? =
    getRegistration(fxType)?.let { choiceProblem(it.parameters, parameters) }

/** An easing curve's options are the desk's own curves, so no FX has to list them. */
internal fun ParameterInfo.withImpliedOptions(): ParameterInfo =
    if (type == "easingCurve" && options == null) copy(options = EASING_CURVE_OPTIONS) else this

private val EASING_CURVE_OPTIONS = EasingCurve.entries.map { curve ->
    ParameterOption(curve.name, curve.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
}

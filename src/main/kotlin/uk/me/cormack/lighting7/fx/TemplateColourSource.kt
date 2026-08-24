package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The grammar an **FX colour parameter** uses to name a colour template, and the resolver behind it.
 *
 * ## Why a parameter and not a value
 *
 * This grammar is legal in exactly one place: the `parameters` map of an effect. It is refused in a
 * Look row (`validateLookRows`) and in a cue's property assignments
 * ([CueAssignmentResolver.parseAssignmentValue]), because a *value* is a literal and a dependency on
 * a template is expressed by a **layer** — which is what ⌥click on a template chip already builds.
 * An effect parameter has no layer to hang off, so it is the one consumer that needs a reference of
 * its own.
 *
 * It replaced the positional colour list (`P1` / `P2` / `P*`, scoped `look > cue > global` by a
 * `PaletteCascade`), which was the last surviving sense of the word "palette" in this codebase.
 * `tmpl:` rather than `ref:` on purpose: `ref:{uuid}` is retired *and* still actively rejected at
 * the Look write boundary as the `FU-LOOK-NESTED` non-recursion guarantee, so reusing it would
 * collide with a live check.
 *
 * ## What a reference resolves to
 *
 * One [ExtendedColour], via [TemplateResolver.resolveColourGeneric] — see that function for why an
 * effect resolves a colour intent without a head in hand, and what it costs. Only a **generic**
 * colour template can be named: a per-fixture template (eight heads aimed at one spot) holds no
 * single colour, so there is nothing for a fixture-agnostic effect output to take from it.
 *
 * ## The one thing to hold when wiring a call site
 *
 * [TemplateRegistry.snapshot] falls back to `loadTemplateSnapshot`, which opens its **own
 * transaction**. So every path that spawns an effect must resolve its references once, on the
 * request thread, before the effect goes live — never under `FxEngine.cueAssignmentsLock` and never
 * from the tick loop. [prewarmTemplateColours] is that call. Positional-palette lookups were pure
 * memory reads; this is the one way the replacement is not like-for-like.
 */

/** The prefix marking a colour parameter as a reference to a template rather than a literal. */
const val TEMPLATE_COLOUR_REF_PREFIX = "tmpl:"

/** Is this colour string a template reference? Shape only — says nothing about the template existing. */
fun isTemplateColourRef(value: String): Boolean =
    value.trim().startsWith(TEMPLATE_COLOUR_REF_PREFIX, ignoreCase = true)

/** The uuid a reference names, or null when the string is not a reference or the uuid is malformed. */
fun parseTemplateColourRefUuid(value: String): UUID? {
    val trimmed = value.trim()
    if (!isTemplateColourRef(trimmed)) return null
    val raw = trimmed.substring(TEMPLATE_COLOUR_REF_PREFIX.length).trim()
    return runCatching { UUID.fromString(raw) }.getOrNull()
}

/** Serialise a reference to [templateUuid]. */
fun serializeTemplateColourRef(templateUuid: UUID): String = "$TEMPLATE_COLOUR_REF_PREFIX$templateUuid"

private val logger = LoggerFactory.getLogger("uk.me.cormack.lighting7.fx.TemplateColourSource")

/**
 * A colour-source function for [TypedParams]: resolves a `tmpl:` reference, and answers **null** for
 * anything else so the caller falls through to its own literal parser.
 *
 * Null is also the answer for a reference that cannot be honoured — an unknown uuid, a template in
 * another family, or a per-fixture one. The caller decides what that means: a single-colour
 * parameter falls back to the literal parser (which reads an unparseable string as white), and a
 * colour *list* drops the entry, because a list is allowed to be shorter but a colour is not allowed
 * to be absent.
 */
fun templateColourSource(registry: TemplateRegistry): (String) -> ExtendedColour? = { raw ->
    val uuid = parseTemplateColourRefUuid(raw)
    when {
        uuid == null -> {
            if (isTemplateColourRef(raw)) logger.warn("Malformed template colour reference '{}'", raw.trim())
            null
        }
        else -> resolveTemplateColour(registry, uuid)
    }
}

/**
 * Resolve one template uuid to its generic colour, or null with a warn saying which way it failed.
 *
 * The three refusals are separate log lines on purpose: "you deleted it", "you pointed a colour
 * parameter at a position template" and "that template is per-fixture" need different fixes.
 */
private fun resolveTemplateColour(registry: TemplateRegistry, uuid: UUID): ExtendedColour? {
    val snapshot = registry.snapshot(uuid) ?: run {
        logger.warn("Template colour reference names no template: {}", uuid)
        return null
    }
    val colourRows = snapshot.rows.filter { TemplateProperty.ofOrNull(it.propertyName) == TemplateProperty.COLOUR }
    if (colourRows.isEmpty()) {
        logger.warn("Template '{}' ({}) holds no colour", snapshot.name, uuid)
        return null
    }
    val row = colourRows.singleOrNull()?.takeIf { it.isDeferred } ?: run {
        // Per-fixture: several rows, or one naming a head. Either way there is no single colour for
        // a fixture-agnostic effect output to take.
        logger.warn(
            "Template '{}' ({}) is per fixture — an effect parameter needs a generic colour",
            snapshot.name, uuid,
        )
        return null
    }
    val intent = parseTemplateIntent(row.value) as? TemplateIntent.Colour ?: run {
        logger.warn("Template '{}' ({}) has an unreadable colour intent '{}'", snapshot.name, uuid, row.value)
        return null
    }
    return TemplateResolver.resolveColourGeneric(intent)
}

/**
 * Read every `tmpl:` reference in [parameters] through [registry] once, so the registry's cache is
 * warm before the effect starts ticking.
 *
 * Call this on the request thread at every spawn site. The return value is deliberately ignored —
 * a reference that cannot resolve is reported by [resolveTemplateColour]'s warn and then handled at
 * tick time by the parameter's own fallback, so failing to pre-warm is a latency bug rather than a
 * correctness one. It is still a bug: the fallback would otherwise open a transaction from the
 * 50 Hz loop.
 */
fun prewarmTemplateColours(registry: TemplateRegistry, parameters: Map<String, String>) {
    parameters.values.forEach { value ->
        value.split(",").forEach { entry ->
            parseTemplateColourRefUuid(entry)?.let { registry.snapshot(it) }
        }
    }
}

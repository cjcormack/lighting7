package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The refusal of the first argument [def]'s schema does not declare, or null. The tools read their
 * arguments by name and ignore the rest, so without this a client holding an old tool list
 * (terminology plan P5) would have an old field dropped and be told the call succeeded. Both tool
 * sets run it before anything else, for every tool but those in [CHECKS_OWN_ARGUMENTS].
 */
internal fun undeclaredArgumentRefusal(def: ToolDef, input: JsonObject): String? {
    if (def.name in CHECKS_OWN_ARGUMENTS) return null
    val found = undeclaredArgument(def.inputSchema, input) ?: return null
    RETIRED_ARGUMENTS[def.name to found.path.substringAfterLast('.')]?.let {
        return "'${found.path}' is not an argument of ${def.name}: $it"
    }
    return "'${found.path}' is not an argument of ${def.name}; it takes ${found.declared.joinToString()}. " +
        "A client that fetched the tool list before the desk was updated should reconnect for the current one."
}

/**
 * The tools that refuse an unknown argument themselves, among every other problem of the call at once
 * — which this check, answering the first undeclared key alone, would cut short.
 */
internal val CHECKS_OWN_ARGUMENTS = setOf(
    "set_stage", "set_scene", "upload_scene_image", "render_view", "move_scenery",
    "unpatch_fixtures", "rename_fixtures", "edit_groups", "override_venue_fixtures",
    "extract_venue", "attach_to_venue", "publish_venue", "delete_cue_stacks", "delete_cues", "copy_cues", "copy_to_project",
)

/** An argument at [path] that its schema does not declare, beside the keys it does. */
internal data class UndeclaredArgument(val path: String, val declared: List<String>)

/**
 * The first argument [schema] does not declare, or null. An object schema with
 * `additionalProperties` is a map and takes any key; `anyOf` is not looked inside.
 */
internal fun undeclaredArgument(schema: JsonObject, value: JsonElement, at: String = ""): UndeclaredArgument? {
    if ("anyOf" in schema || "oneOf" in schema) return null
    when (value) {
        is JsonObject -> {
            val properties = schema["properties"] as? JsonObject ?: return null
            val open = schema["additionalProperties"].let { it != null && it != JsonPrimitive(false) }
            for ((key, child) in value) {
                val path = if (at.isEmpty()) key else "$at.$key"
                val childSchema = properties[key] as? JsonObject
                if (childSchema == null) {
                    if (open) continue
                    return UndeclaredArgument(path, properties.keys.toList())
                }
                undeclaredArgument(childSchema, child, path)?.let { return it }
            }
        }
        is JsonArray -> {
            val items = schema["items"] as? JsonObject ?: return null
            value.forEachIndexed { i, item -> undeclaredArgument(items, item, "$at[$i]")?.let { return it } }
        }
        else -> {}
    }
    return null
}

/** What replaced an argument a tool once took, keyed by the tool and the argument's own name. */
private val RETIRED_ARGUMENTS: Map<Pair<String, String>, String> = mapOf(
    ("add_cue_to_stack" to "sortOrder") to
        "name the entry the cue goes after with after ({entryId} or {number}), or leave it out to append",
    ("activate_cue_stack" to "cueId") to
        "name the entry by entryId or by its number in the stack: a cue may sit in a stack more than once",
    ("set_standby" to "cueId") to
        "name the entry by entryId or by its number in the stack: a cue may sit in a stack more than once",
    ("apply_look" to "targets") to
        "a Look lands whole, on its own fixtures, so apply_look takes no targets",
    ("apply_palette" to "mode") to
        "apply_palette puts on a layer by default; pass asValues: true for literals",
    ("mark_up_prompt_book" to "cueId") to
        "name the entry the cue is called from — entryId, or stackId and number (a cue may sit in several stacks, so cueId names no place)",
)

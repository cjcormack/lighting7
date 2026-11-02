package uk.me.cormack.lighting7.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The record folders the undo's restore names (`SyncDispositions` is where each table's is decided). */
object RecordFolders {
    const val LOOKS = "looks"
    const val PALETTES = "palettes"
    const val CUE_STACKS = "cueStacks"
    const val CUE_STACK_ENTRIES = "cueStackEntries"
    const val CUES = "cues"
    const val CUE_ATTRIBUTE_ASSIGNMENTS = "cueAttributeAssignments"
    const val CUE_LAYERS = "cueLayers"
    const val CUE_AD_HOC_FX = "cueAdHocFx"
    const val CUE_TRIGGERS = "cueTriggers"
    const val CUE_SLOTS = "cueSlots"
    const val BUSK_PAGES = "buskPages"
    const val BUSK_RIG = "buskRig"
    const val PROMPT_BOOK_ANCHORS = "promptBookAnchors"

    /** A cue's children, each its own folder, each naming its cue as `cueUuid`. */
    val CUE_CHILDREN: Set<String> = setOf(CUE_ATTRIBUTE_ASSIGNMENTS, CUE_LAYERS, CUE_AD_HOC_FX, CUE_TRIGGERS)
}

/** The image's JSON as an object, or null when it is not one. */
fun RecordImage.jsonObjectOrNull(): JsonObject? = runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull()

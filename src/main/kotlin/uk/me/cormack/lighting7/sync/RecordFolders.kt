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
    const val PROMPT_BOOKS = "promptBooks"
    const val PROMPT_BOOK_ANNOTATIONS = "promptBookAnnotations"
    const val FIXTURE_PATCHES = "fixturePatches"
    const val FIXTURE_GROUPS = "fixtureGroups"
    const val UNIVERSE_CONFIGS = "universeConfigs"
    const val SHOW_HIDES = "showHides"
    const val SHOW_OVERRIDES = "showOverrides"
    const val SHOW_COLOURS = "showColours"
    const val SHOW_GROUP_ORDERS = "showGroupOrders"
    const val RIGGINGS = "riggings"
    const val STAGE_REGIONS = "stageRegions"
    const val STAGE_ELEMENTS = "stageElements"
    const val STAGE_VIEWPOINTS = "stageViewpoints"
    const val SCRIPTS = "scripts"
    const val FX_DEFINITIONS = "fxDefinitions"
    const val SPEED_MASTERS = "speedMasters"
    const val PARKED_CHANNELS = "parkedChannels"
    const val SHOW_PARK_RELEASES = "showParkReleases"
    const val CONTROL_SURFACE_BINDINGS = "controlSurfaceBindings"

    /** A cue's children, each its own folder, each naming its cue as `cueUuid`. */
    val CUE_CHILDREN: Set<String> = setOf(CUE_ATTRIBUTE_ASSIGNMENTS, CUE_LAYERS, CUE_AD_HOC_FX, CUE_TRIGGERS)

    /** The book's children: a project has one book, so neither names it. */
    val PROMPT_BOOK_CHILDREN: Set<String> = setOf(PROMPT_BOOK_ANCHORS, PROMPT_BOOK_ANNOTATIONS)

    /** The rig as the loader reads it: a change to any reloads the fixtures. */
    val RIG: Set<String> = setOf(
        FIXTURE_PATCHES, FIXTURE_GROUPS, UNIVERSE_CONFIGS, SHOW_HIDES, SHOW_OVERRIDES, SHOW_COLOURS, SHOW_GROUP_ORDERS,
    )

    /** The parks: a change to either moves output. */
    val PARKS: Set<String> = setOf(PARKED_CHANNELS, SHOW_PARK_RELEASES)

    /** What changes output when undone (undo plan §5): the rig and the parks. */
    val OUTPUT: Set<String> = RIG + PARKS
}

/** The image's JSON as an object, or null when it is not one. */
fun RecordImage.jsonObjectOrNull(): JsonObject? = runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull()

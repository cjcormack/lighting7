package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fx.MasterClock
import java.util.UUID

// ─── A cue's speed masters (venue-visit plan S16, D15, D20) ──────────────────────────────────────
//
// A cue may set speed masters' tempo when it comes on: a list of (master, BPM) pairs, stored on the
// cue as `cues.speed_masters`. It is the cue's, not its entries', so a shared cue sets the same tempo
// from every stack. It never tracks: a later cue sets nothing an earlier one set.
//
// It applies on every GO into one of the cue's entries — GO TO and GO BACK included, and when another
// stack already holds the cue — and on every loose press, but never on a reload's re-GO
// (`CueStackManager.activateEntry`'s and `takeLooseCue`'s `setTempo`). A tap afterwards wins until
// the next cue that sets that master. A master that follows another, or no longer exists, is skipped
// at GO and named in the log (`fx/CueTempo.kt`).

/** One master a cue sets on GO, by uuid, and the tempo it sets it to. */
@Serializable
data class CueSpeedMasterDto(
    val speedMasterUuid: String,
    val bpm: Double,
)

private val REST_KEYS = setOf("speedMasterUuid", "bpm")
private val TOOL_KEYS = setOf("speedMasterUuid", "masterIndex", "bpm")

/** One of a project's masters as [parseCueSpeedMasters] checks a list against it. */
data class SpeedMasterRef(val uuid: String, val index: Int, val name: String)

/**
 * A whole list in the wire shape `[{speedMasterUuid, bpm}]`, checked against [masters] (the
 * project's), every problem at once. A uuid must name one of them, or one of [kept] — the uuids the
 * cue already stores, so a list that still names a master deleted under it can be re-saved and the
 * dead row removed. [bpm] is 20–300, a master's range, and a master may be named once. [where]
 * prefixes every message. With [byIndex] (the MCP tools' shape) a row may name its master by
 * `masterIndex` instead — 1 for Master 1 — which a tool building a project that is not current can
 * know, where its uuids it cannot.
 */
fun parseCueSpeedMasters(
    items: List<JsonElement>,
    masters: List<SpeedMasterRef>,
    kept: Collection<String>,
    where: String,
    problems: MutableList<String>,
    byIndex: Boolean = false,
): List<CueSpeedMasterDto> {
    val byUuid = masters.associateBy { it.uuid }
    val indexed = masters.associateBy { it.index }
    val known = kept.toSet()
    val keys = if (byIndex) TOOL_KEYS else REST_KEYS
    val out = mutableListOf<CueSpeedMasterDto>()
    val seen = mutableSetOf<String>()
    items.forEachIndexed { i, raw ->
        val at = "$where[$i]"
        val obj = raw as? JsonObject ?: run { problems += "$at must be an object"; return@forEachIndexed }
        for (key in obj.keys) {
            if (key !in keys) problems += "$at: unknown field '$key' (known: ${keys.sorted().joinToString()})"
        }
        val before = problems.size
        val ref = (obj["speedMasterUuid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val indexRaw = obj["masterIndex"]?.takeIf { byIndex && it !is JsonNull }
        val uuid = when {
            indexRaw != null && !ref.isNullOrEmpty() -> { problems += "$at: name the master by speedMasterUuid or masterIndex, not both"; null }
            indexRaw != null -> {
                val index = (indexRaw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                index?.let { indexed[it] }?.uuid
                    ?: run { problems += "$at.masterIndex names no speed master in this project (it has ${masters.joinToString { "${it.index}" }})"; null }
            }
            ref.isNullOrEmpty() -> {
                problems += if (byIndex) "$at needs a master: masterIndex (1 for Master 1) or speedMasterUuid" else "$at.speedMasterUuid is required: a speed master's uuid"
                null
            }
            runCatching { UUID.fromString(ref) }.isFailure -> { problems += "$at.speedMasterUuid '$ref' is not a uuid"; null }
            else -> UUID.fromString(ref).toString().takeIf { it in byUuid || it in known }
                ?: run { problems += "$at.speedMasterUuid names no speed master in this project"; null }
        }
        val label = uuid?.let { byUuid[it] }?.let { "$at ('${it.name}')" } ?: at
        if (uuid != null && !seen.add(uuid)) problems += "$label names a master twice: a cue sets each master once"
        val bpmRaw = obj["bpm"]?.takeIf { it !is JsonNull }
        val bpm = (bpmRaw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        when {
            bpmRaw == null -> problems += "$label.bpm is required"
            bpm == null -> problems += "$label.bpm must be a number"
            bpm < MasterClock.MIN_BPM || bpm > MasterClock.MAX_BPM ->
                problems += "$label.bpm must be between ${MasterClock.MIN_BPM.toInt()} and ${MasterClock.MAX_BPM.toInt()}"
        }
        if (problems.size == before && uuid != null && bpm != null) out += CueSpeedMasterDto(uuid, bpm)
    }
    return out
}

/** [project]'s masters as [parseCueSpeedMasters] checks a list against them, by index. Must run inside a transaction. */
fun speedMasterRefsOf(project: DaoProject): List<SpeedMasterRef> =
    speedMastersOf(project).map { SpeedMasterRef(it.uuid.toString(), it.masterIndex, it.name) }

/** [project]'s masters, as [parseCueSpeedMasters] checks a list against them. Must run inside a transaction. */
fun speedMastersOf(project: DaoProject): List<DaoSpeedMaster> =
    DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }.sortedBy { it.masterIndex }

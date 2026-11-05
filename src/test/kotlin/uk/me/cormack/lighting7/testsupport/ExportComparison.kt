package uk.me.cormack.lighting7.testsupport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.KSerializer
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.PaletteLayerConversion
import uk.me.cormack.lighting7.sync.LegacyNames
import uk.me.cormack.lighting7.sync.dto.CueAttributeAssignmentJson
import uk.me.cormack.lighting7.sync.dto.CueLayerJson
import uk.me.cormack.lighting7.sync.dto.FixtureGroupJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.sync.dto.PaletteJson
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.sync.canonicalDecode
import uk.me.cormack.lighting7.sync.canonicalEncode
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.canonicalJson
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every regular file under [root], keyed by its path relative to [root].
 *
 * Files under `promptScripts` and `sceneImages` are prompt-book PDFs and scene images — binary.
 * They are represented by a `sha256:` digest rather than decoded text: `Files.readString` on one
 * either throws `MalformedInputException` or, on a lenient decoder, maps invalid bytes to U+FFFD
 * and makes two different files compare equal. The digest keeps the comparison exact either way.
 */
fun readExportFiles(root: Path): Map<String, String> {
    val out = mutableMapOf<String, String>()
    Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile).forEach { p ->
            val rel = root.relativize(p).toString().replace(File.separatorChar, '/')
            out[rel] = if (RecordHasher.BINARY_DIRS.any { rel.startsWith("$it/") }) {
                "sha256:" + RecordHasher.sha256Hex(Files.readAllBytes(p))
            } else {
                Files.readString(p)
            }
        }
    }
    return out
}

/**
 * Every UUID appearing anywhere in [root]'s JSON documents, found by plain pattern match.
 *
 * Deliberately independent of `ExportUuidRemapper`'s own notion of an identity: a test that
 * asked the remapper which UUIDs exist would share any blind spot the remapper has, which is
 * exactly how embedded-child identities went unremapped once already. `installs.json` is
 * excluded — the install identity is legitimately the same in a project and its clone.
 */
fun allUuidsIn(root: Path): Set<String> {
    val pattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    return readExportFiles(root)
        .filterKeys { it.endsWith(".json") && it != "installs.json" }
        .values
        .flatMap { pattern.findAll(it).map { m -> m.value.lowercase() } }
        .toSet()
}

/**
 * Assert two export folders hold the same files with the same bytes.
 *
 * @param installsShapeOnly compare `installs.json` structurally rather than byte-wise. Needed
 *   when the two exports straddle a DB reset: a fresh install row is bootstrapped with a new
 *   UUID, and round-trip portability is about the project graph, not the metadata stamp.
 * @param ignoreProjectIdentity normalise `project.json`'s `name` and `description` before
 *   comparing. Needed when comparing a project against its clone, which is renamed by
 *   definition.
 */
fun assertExportsEqual(
    a: Path,
    b: Path,
    installsShapeOnly: Boolean = false,
    ignoreProjectIdentity: Boolean = false,
) {
    val filesA = readExportFiles(a)
    val filesB = readExportFiles(b)
    assertEquals(filesA.keys.sorted(), filesB.keys.sorted(), "export file sets differ")
    filesA.forEach { (rel, contentA) ->
        val contentB = filesB.getValue(rel)
        if (installsShapeOnly && rel == "installs.json") {
            val installsB = canonicalDecode(InstallsJson.serializer(), contentB)
            assertEquals(1, installsB.installs.size, "installs.json must contain one entry")
            installsB.installs.forEach { (uuid, name) ->
                UUID.fromString(uuid)
                assertTrue(name.isNotBlank(), "installs.json friendlyName must be non-blank")
            }
            return@forEach
        }
        if (ignoreProjectIdentity && rel == "project.json") {
            assertEquals(
                normaliseProjectIdentity(contentA),
                normaliseProjectIdentity(contentB),
                "project.json differs beyond name/description",
            )
            return@forEach
        }
        assertEquals(contentA, contentB, "byte mismatch in $rel")
    }
}

private fun normaliseProjectIdentity(json: String): String {
    val decoded = canonicalDecode(ProjectJson.serializer(), json)
    return canonicalEncode(
        ProjectJson.serializer(),
        decoded.copy(name = "<name>", description = "<description>"),
    )
}

/**
 * [json], one archive record in today's field names, with every FX it holds that fx-settings plan D3
 * moved as the importer stores it: through [LegacyNames.consolidateFx] then [LegacyNames.absorbFx],
 * so a level wave's `fxType`, `family`, `parameters` and a beam FX's `attribute` are Wave's, a
 * movement's Path's, and a renamed parameter its new name. Answers [json] itself when it holds none.
 */
fun consolidatedRecord(json: String): String {
    fun consolidate(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::consolidate))
        is JsonObject -> {
            val fxType = (element["fxType"] as? JsonPrimitive)?.contentOrNull
            val parameters = (element["parameters"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
            val stored = fxType?.let {
                LegacyNames.StoredFx(
                    fxType = it, family = (element["family"] as? JsonPrimitive)?.contentOrNull.orEmpty(), cycle = 0.0,
                    order = "", phaseOffset = 0.0, parameters = parameters, direction = "", runs = null, spread = 0.0,
                    attribute = (element["attribute"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            val waved = stored?.let { LegacyNames.consolidateFx(it) { null }?.fx ?: it }
            val today = waved?.let { LegacyNames.absorbFx(it) { null }?.fx ?: it }
            if (stored != null && today != stored) {
                JsonObject(
                    element + listOfNotNull(
                        "fxType" to JsonPrimitive(today!!.fxType),
                        "family" to JsonPrimitive(today.family),
                        "parameters" to JsonObject(today.parameters.mapValues { JsonPrimitive(it.value) }),
                        today.attribute?.let { "attribute" to JsonPrimitive(it) },
                    ),
                )
            } else {
                JsonObject(element.mapValues { consolidate(it.value) })
            }
        }
        else -> element
    }
    val record = runCatching { canonicalJson.parseToJsonElement(json) }.getOrNull() ?: return json
    val out = consolidate(record)
    return if (out == record) json else canonicalEncode(JsonElement.serializer(), out)
}

/**
 * [archive], an expected export tree, with the retired per-row move-in-dark flag lifted as the
 * importer lifts it (move-in-dark plan D13, sync v46): every `cueAttributeAssignments` record loses
 * its `moveInDark`, and the cue of each that carried it `true` gains `moveInDarkMode: EARLY`.
 */
fun liftRowMoveInDark(archive: Path) {
    val rows = archive.resolve("cueAttributeAssignments")
    if (!Files.isDirectory(rows)) return
    val flagged = HashSet<String>()
    Files.list(rows).use { it.toList() }.forEach { file ->
        val row = canonicalJson.parseToJsonElement(Files.readString(file)) as JsonObject
        val flag = row["moveInDark"] ?: return@forEach
        if ((flag as? JsonPrimitive)?.contentOrNull == "true") flagged += (row["cueUuid"] as JsonPrimitive).content
        Files.writeString(file, canonicalEncode(JsonElement.serializer(), JsonObject(row - "moveInDark")))
    }
    val cues = archive.resolve("cues")
    if (flagged.isEmpty() || !Files.isDirectory(cues)) return
    Files.list(cues).use { it.toList() }.forEach { file ->
        val cue = canonicalJson.parseToJsonElement(Files.readString(file)) as JsonObject
        if ((cue["uuid"] as? JsonPrimitive)?.content !in flagged) return@forEach
        Files.writeString(file, canonicalEncode(JsonElement.serializer(), JsonObject(cue + ("moveInDarkMode" to JsonPrimitive("EARLY")))))
    }
}

/**
 * [archive], an archive below v47 in today's field names, as the importer stores it
 * (palette-references plan D17): each cue's plain palette layers become reference rows by
 * [PaletteLayerConversion.decide], judged over the archive's own patches and groups, and their
 * `cueLayers` records go.
 */
fun convertPaletteLayers(archive: Path) {
    fun <T> records(folder: String, serializer: KSerializer<T>): List<Pair<Path, T>> {
        val dir = archive.resolve(folder)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { it.toList() }.filter { it.toString().endsWith(".json") }.sorted()
            .map { it to canonicalDecode(serializer, Files.readString(it)) }
    }
    val patches = records("fixturePatches", FixturePatchJson.serializer()).map { it.second }
    val keyOf = patches.associate { it.uuid to it.key }
    val typeOf = patches.associate { it.key to it.fixtureTypeKey }
    val groups = records("fixtureGroups", FixtureGroupJson.serializer()).associate { (_, group) ->
        group.name to group.members.sortedBy { it.sortOrder }.mapNotNull { keyOf[it.fixturePatchUuid] }
    }
    val palettes = records("palettes", PaletteJson.serializer()).associate { (_, palette) ->
        palette.uuid to PaletteLayerConversion.Palette(
            UUID.fromString(palette.uuid), palette.name, palette.fx != null,
            palette.rows.sortedBy { it.sortOrder }.map {
                PaletteLayerConversion.PaletteRow(if (it.targetType == DEFERRED_TARGET_TYPE) null else it.targetKey, it.attribute)
            },
            palette.fadeDurationMs,
        )
    }
    val looks = records("looks", LookJson.serializer()).associate { (_, look) ->
        look.uuid to PaletteLayerConversion.Look(
            look.name,
            look.rows.mapNotNull { row -> TargetRef.ofOrNull(row.targetType, row.targetKey)?.let { PaletteLayerConversion.LookRow(it, row.elementKey, row.attribute) } },
        )
    }
    val rows = records("cueAttributeAssignments", CueAttributeAssignmentJson.serializer()).map { it.second }
    val rig = object : PaletteLayerConversion.Rig {
        override fun members(group: String): List<String>? = groups[group]

        override fun familyOf(head: String, attribute: String) = PaletteLayerConversion.typeFamily(typeOf[head], attribute)
    }
    for ((cue, layers) in records("cueLayers", CueLayerJson.serializer()).groupBy { it.second.cueUuid }) {
        val own = rows.filter { it.cueUuid == cue }
        val decision = PaletteLayerConversion.decide(
            PaletteLayerConversion.Cue(
                cue,
                layers.mapIndexed { index, (_, layer) ->
                    PaletteLayerConversion.Layer(
                        id = index, uuid = UUID.fromString(layer.uuid), sortOrder = layer.sortOrder,
                        look = layer.lookUuid?.let { looks[it] }, palette = layer.paletteUuid?.let { palettes[it] },
                        enabled = layer.enabled, targets = layer.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) },
                        mask = layer.attributeMask, blendMode = layer.blendMode, amount = layer.amount, stomp = layer.stomp,
                        delayed = layer.delayMs != null, interval = layer.intervalMs != null, randomWindow = layer.randomWindowMs != null,
                    )
                },
                own.mapNotNull { row -> TargetRef.ofOrNull(row.targetType, row.targetKey)?.let { PaletteLayerConversion.OwnRow(it, row.attribute) } },
                nextSortOrder = (own.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            ),
            rig,
        )
        for (row in decision.rows) {
            val json = CueAttributeAssignmentJson(
                uuid = row.uuid.toString(), cueUuid = cue, targetType = row.target.discriminator, targetKey = row.target.key,
                attribute = row.attribute, value = row.value, sortOrder = row.sortOrder, fadeDurationMs = row.fadeMs,
            )
            val dir = Files.createDirectories(archive.resolve("cueAttributeAssignments"))
            Files.writeString(dir.resolve("${row.uuid}.json"), canonicalEncode(CueAttributeAssignmentJson.serializer(), json))
        }
        for ((file, layer) in layers) if (UUID.fromString(layer.uuid) in decision.converted) Files.delete(file)
    }
}

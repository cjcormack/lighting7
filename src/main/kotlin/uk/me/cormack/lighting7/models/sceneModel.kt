package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─── Model objects: an OBJECT built from parts ──────────────────────────────────────────────────
//
// An `OBJECT` whose `shape` is `MODEL` carries `parts`: boxes, cylinders and cones, spheres,
// extruded outlines and lathed profiles, each placed, turned and finished on its own, optionally
// textured with a scene image. The parts are written in the model's own units, and the whole model
// is fitted to the element's box — its footprint centred on the origin, its lowest point on the
// base — so one model can be applied at any size. Nothing on the desk reads a part: the Stage view
// draws them (`frontend/src/components/stage3d/scene/builders/model.ts`), and [modelBounds] is the
// one sum both sides must agree on, pinned by `src/test/resources/stage/modelBounds.fixture.json`.

/** The shapes a model part may take. */
enum class PartShape { BOX, CYLINDER, SPHERE, EXTRUDE, LATHE }

/**
 * How a part's texture lies on it: `STRETCH` puts the whole image on each face (a box's six, a
 * cylinder's side and its caps, an outline's front and back), `TILE` repeats it every
 * [PartTexture.tileM] of the model's own units.
 */
enum class PartTextureMode { STRETCH, TILE }

/** A scene image ([SCENE_IMAGE_HASH]) on a part. */
@Serializable
data class PartTexture(
    val image: String,
    val mode: PartTextureMode? = null,
    /** One repeat's size, in the model's units; `TILE` only, default 1. */
    val tileM: Double? = null,
)

/**
 * One part of a model, in the model's own frame: x across (towards stage left), y upstage, z up.
 * A box, a cylinder and a sphere are centred on ([x], [y], [z]); an extrusion's [outline] and a
 * lathe's [profile] are drawn about it. The turn is applied about that point: [rollDeg] about the
 * part's y, then [pitchDeg] about its x, then [yawDeg] about z — right-handed, so a positive yaw
 * turns anticlockwise from above (the desk's yaw), a positive pitch leans the top downstage and a
 * positive roll leans it towards stage left.
 */
@Serializable
data class ModelPart(
    val shape: PartShape,
    val name: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    /** A box's, a sphere's, a cylinder's base diameter. */
    val widthM: Double? = null,
    /** A box's, a sphere's (default its width), an extrusion's thickness. */
    val depthM: Double? = null,
    /** A box's, a sphere's (default its width), a cylinder's. */
    val heightM: Double? = null,
    /** A cylinder's top diameter: absent is its width, 0 a cone's point. */
    val topWidthM: Double? = null,
    /** An extrusion's outline, `[x, z]` pairs in the part's x–z plane. */
    val outline: List<List<Double>>? = null,
    /** Holes cut through an extrusion, each an outline of its own. */
    val holes: List<List<List<Double>>>? = null,
    /** A lathe's profile, `[radius, z]` pairs revolved about the part's z axis. */
    val profile: List<List<Double>>? = null,
    /** How many sides a lathe is drawn with. */
    val segments: Int? = null,
    /** `#rrggbb`; absent is the element's finish colour. */
    val colour: String? = null,
    val pattern: SurfacePattern? = null,
    val emissive: Boolean? = null,
    val texture: PartTexture? = null,
    /** False: drawn, but neither shadowing nor stopping a beam — a small detail not worth a collider. */
    val shadow: Boolean? = null,
)

/** The most parts one model may hold: each is a mesh, and a colliding one a shadow box. */
const val MAX_MODEL_PARTS = 200

/** The most outline, hole and profile points one model may hold, all parts together. */
const val MAX_MODEL_POINTS = 4000

/** How far from its model's origin a part may sit, and how large one may be, in the model's units. */
const val MODEL_EXTENT = 100.0

/** A model narrower than this along any axis is flat, and cannot be fitted to a box. */
const val MIN_MODEL_EXTENT = 1e-4

/** An axis-aligned box in the model's frame. */
data class ModelBounds(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    val widthM: Double get() = maxX - minX
    val depthM: Double get() = maxY - minY
    val heightM: Double get() = maxZ - minZ
}

/**
 * A part's own box before it is turned: a box's or a sphere's half-sizes, a cylinder's widest
 * radius, an outline's extent and half its thickness, a profile's widest radius and its height.
 */
fun ModelPart.localBounds(): ModelBounds {
    val w = widthM ?: 0.0
    return when (shape) {
        PartShape.BOX -> ModelBounds(-w / 2, -(depthM ?: 0.0) / 2, -(heightM ?: 0.0) / 2, w / 2, (depthM ?: 0.0) / 2, (heightM ?: 0.0) / 2)
        PartShape.SPHERE -> {
            val d = depthM ?: w
            val h = heightM ?: w
            ModelBounds(-w / 2, -d / 2, -h / 2, w / 2, d / 2, h / 2)
        }
        PartShape.CYLINDER -> {
            val r = max(w, topWidthM ?: w) / 2
            val h = heightM ?: 0.0
            ModelBounds(-r, -r, -h / 2, r, r, h / 2)
        }
        PartShape.EXTRUDE -> {
            val pts = outline.orEmpty()
            val d = depthM ?: 0.0
            ModelBounds(pts.minOf { it[0] }, -d / 2, pts.minOf { it[1] }, pts.maxOf { it[0] }, d / 2, pts.maxOf { it[1] })
        }
        PartShape.LATHE -> {
            val pts = profile.orEmpty()
            val r = pts.maxOf { it[0] }
            ModelBounds(-r, -r, pts.minOf { it[1] }, r, r, pts.maxOf { it[1] })
        }
    }
}

/**
 * The part's turn as a row-major 3×3 matrix in the model's frame: `Rz(yaw) · Rx(pitch) · Ry(roll)`,
 * so a point is rolled first and yawed last.
 */
fun ModelPart.rotation(): DoubleArray {
    val a = Math.toRadians(yawDeg ?: 0.0)
    val b = Math.toRadians(pitchDeg ?: 0.0)
    val c = Math.toRadians(rollDeg ?: 0.0)
    val (ca, sa, cb, sb, cc) = listOf(cos(a), sin(a), cos(b), sin(b), cos(c))
    val sc = sin(c)
    // Rz(a) · Rx(b) · Ry(c), multiplied out.
    return doubleArrayOf(
        ca * cc - sa * sb * sc, -sa * cb, ca * sc + sa * sb * cc,
        sa * cc + ca * sb * sc, ca * cb, sa * sc - ca * sb * cc,
        -cb * sc, sb, cb * cc,
    )
}

/**
 * The model's box: the union of every part's own box ([localBounds]) turned by its [rotation] and
 * moved to its place — the eight corners, so a turned part's box is the box round its turned box.
 * Every part counts, `shadow: false` ones included. The Stage view computes the same sum to fit the
 * model to its element.
 */
fun modelBounds(parts: List<ModelPart>): ModelBounds {
    var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
    for (part in parts) {
        val b = part.localBounds()
        val r = part.rotation()
        val tx = part.x ?: 0.0
        val ty = part.y ?: 0.0
        val tz = part.z ?: 0.0
        for (cx in doubleArrayOf(b.minX, b.maxX)) for (cy in doubleArrayOf(b.minY, b.maxY)) for (cz in doubleArrayOf(b.minZ, b.maxZ)) {
            val wx = r[0] * cx + r[1] * cy + r[2] * cz + tx
            val wy = r[3] * cx + r[4] * cy + r[5] * cz + ty
            val wz = r[6] * cx + r[7] * cy + r[8] * cz + tz
            minX = min(minX, wx); minY = min(minY, wy); minZ = min(minZ, wz)
            maxX = max(maxX, wx); maxY = max(maxY, wy); maxZ = max(maxZ, wz)
        }
    }
    return ModelBounds(minX, minY, minZ, maxX, maxY, maxZ)
}

/** Twice a polygon's signed area (the shoelace sum); 0 for a degenerate one. */
private fun doubledArea(points: List<List<Double>>): Double =
    points.indices.sumOf { i ->
        val p = points[i]
        val q = points[(i + 1) % points.size]
        p[0] * q[1] - q[0] * p[1]
    }

/**
 * A list of `[a, b]` pairs, each a finite number within ±[MODEL_EXTENT]; null with a problem for
 * anything else. [count] collects how many points were read, for the model's [MAX_MODEL_POINTS].
 */
private fun readPoints(
    array: JsonArray,
    where: String,
    minPoints: Int,
    maxPoints: Int,
    problems: MutableList<String>,
    count: IntArray,
): List<List<Double>>? {
    if (array.size < minPoints || array.size > maxPoints) {
        problems += "$where must hold $minPoints–$maxPoints points, each [a, b]"
        return null
    }
    count[0] += array.size
    val out = ArrayList<List<Double>>(array.size)
    var ok = true
    for ((i, e) in array.withIndex()) {
        val pair = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }
        if (pair == null || pair.size != 2 || e.size != 2 || pair.any { !it.isFinite() || abs(it) > MODEL_EXTENT }) {
            problems += "$where[$i] must be a pair of numbers within ±$MODEL_EXTENT"
            ok = false
            continue
        }
        out += pair
    }
    return out.takeIf { ok }
}

/**
 * `params.parts` validated as a model's parts, every problem at once, or null. [imageStored] says
 * whether a texture's hash names an image this project holds, as a cloth's paint is checked.
 */
internal fun readModelParts(
    array: JsonArray,
    where: String,
    problems: MutableList<String>,
    imageStored: (String) -> Boolean,
): List<ModelPart>? {
    val before = problems.size
    if (array.isEmpty() || array.size > MAX_MODEL_PARTS) {
        problems += "$where must hold 1–$MAX_MODEL_PARTS parts"
        return null
    }
    val count = intArrayOf(0)
    val parts = array.mapIndexedNotNull { i, e ->
        val obj = e as? JsonObject ?: run { problems += "$where[$i] must be an object"; return@mapIndexedNotNull null }
        readPart(obj, "$where[$i]", problems, imageStored, count)
    }
    if (count[0] > MAX_MODEL_POINTS) problems += "$where: the model holds ${count[0]} outline and profile points; at most $MAX_MODEL_POINTS"
    if (problems.size > before) return null
    val b = modelBounds(parts)
    for ((axis, size) in listOf("x" to b.widthM, "y" to b.depthM, "z" to b.heightM)) {
        if (size < MIN_MODEL_EXTENT) problems += "$where: the model is flat along $axis, so it cannot be fitted to its element"
    }
    return parts.takeIf { problems.size == before }
}

private fun readPart(
    obj: JsonObject,
    where: String,
    problems: MutableList<String>,
    imageStored: (String) -> Boolean,
    count: IntArray,
): ModelPart? {
    val before = problems.size
    val r = ParamReader(obj, where, problems)
    val shape = r.enum<PartShape>("shape", required = true) ?: return null
    val at = "$where ($shape)"
    val sized = ParamReader(obj, at, problems)
    // Shared by every shape; read through the first reader, so the second reports only shape keys.
    val name = r.string("name")?.trim()
    if (name != null && (name.isEmpty() || name.length > 60)) problems += "$where.name must be 1–60 characters"
    val x = r.number("x", -MODEL_EXTENT, MODEL_EXTENT)
    val y = r.number("y", -MODEL_EXTENT, MODEL_EXTENT)
    val z = r.number("z", -MODEL_EXTENT, MODEL_EXTENT)
    val yaw = r.number("yawDeg", -360.0, 360.0, unit = "degrees")
    val pitch = r.number("pitchDeg", -360.0, 360.0, unit = "degrees")
    val roll = r.number("rollDeg", -360.0, 360.0, unit = "degrees")
    val colour = normaliseFinishColour(r.string("colour"), "$where.colour", problems)
    val pattern = r.enum<SurfacePattern>("pattern")
    val emissive = r.bool("emissive")
    val shadow = r.bool("shadow")
    val texture = r.obj("texture")?.let { readTexture(it, "$where.texture", problems, imageStored) }
    var part = ModelPart(
        shape = shape, name = name, x = x, y = y, z = z, yawDeg = yaw, pitchDeg = pitch, rollDeg = roll,
        colour = colour, pattern = pattern, emissive = emissive, texture = texture, shadow = shadow,
    )
    val size = 0.001
    part = when (shape) {
        PartShape.BOX -> part.copy(
            widthM = sized.number("widthM", size, MODEL_EXTENT, required = true),
            depthM = sized.number("depthM", size, MODEL_EXTENT, required = true),
            heightM = sized.number("heightM", size, MODEL_EXTENT, required = true),
        )
        PartShape.SPHERE -> part.copy(
            widthM = sized.number("widthM", size, MODEL_EXTENT, required = true),
            depthM = sized.number("depthM", size, MODEL_EXTENT),
            heightM = sized.number("heightM", size, MODEL_EXTENT),
        )
        PartShape.CYLINDER -> part.copy(
            widthM = sized.number("widthM", 0.0, MODEL_EXTENT, required = true),
            heightM = sized.number("heightM", size, MODEL_EXTENT, required = true),
            topWidthM = sized.number("topWidthM", 0.0, MODEL_EXTENT),
        ).also {
            if (it.widthM != null && max(it.widthM, it.topWidthM ?: it.widthM) < size) {
                problems += "$at: widthM and topWidthM cannot both be 0"
            }
        }
        PartShape.EXTRUDE -> {
            val outline = sized.array("outline")?.let { readPoints(it, "$at.outline", 3, 500, problems, count) }
            if (outline == null && problems.none { it.startsWith("$at.outline") }) problems += "$at.outline is required: [[x, z], …], at least 3 points"
            if (outline != null && abs(doubledArea(outline)) < 1e-8) problems += "$at.outline encloses no area"
            val holes = sized.array("holes")?.mapIndexedNotNull { i, h ->
                val hole = h as? JsonArray ?: run { problems += "$at.holes[$i] must be an array of points"; return@mapIndexedNotNull null }
                readPoints(hole, "$at.holes[$i]", 3, 500, problems, count)?.also {
                    if (abs(doubledArea(it)) < 1e-8) problems += "$at.holes[$i] encloses no area"
                }
            }
            part.copy(
                outline = outline,
                holes = holes?.takeIf { it.isNotEmpty() },
                depthM = sized.number("depthM", size, MODEL_EXTENT, required = true),
            )
        }
        PartShape.LATHE -> {
            val profile = sized.array("profile")?.let { readPoints(it, "$at.profile", 2, 200, problems, count) }
            if (profile == null && problems.none { it.startsWith("$at.profile") }) problems += "$at.profile is required: [[radius, z], …], at least 2 points"
            if (profile != null) {
                if (profile.any { it[0] < 0.0 }) problems += "$at.profile: a radius cannot be negative"
                if (profile.maxOf { it[0] } < size) problems += "$at.profile: every radius is 0, so there is nothing to revolve"
            }
            part.copy(profile = profile, segments = sized.int("segments", 3, 96))
        }
    }
    // Only the first reader has seen the shared keys: an unknown key is one neither reader read.
    sized.markRead(r.readKeys)
    sized.rejectUnknown()
    return part.takeIf { problems.size == before }
}

private fun readTexture(obj: JsonObject, where: String, problems: MutableList<String>, imageStored: (String) -> Boolean): PartTexture? {
    val before = problems.size
    val r = ParamReader(obj, where, problems)
    val raw = r.string("image")?.trim()?.lowercase()
    val mode = r.enum<PartTextureMode>("mode")
    val tile = r.number("tileM", 0.01, MODEL_EXTENT)
    r.rejectUnknown()
    when {
        raw == null -> if (problems.none { it.startsWith("$where.image") }) problems += "$where.image is required: a scene image's SHA-256"
        !SCENE_IMAGE_HASH.matches(raw) -> problems += "$where.image must be an image's SHA-256: 64 hex characters"
        !imageStored(raw) -> problems += "$where.image names no stored image"
    }
    if (tile != null && mode != PartTextureMode.TILE) problems += "$where.tileM is a TILE texture's; this one is ${mode ?: PartTextureMode.STRETCH}"

    if (problems.size > before || raw == null) return null
    return PartTexture(raw, mode, tile)
}

/** Every texture hash a stored `parts` array names, read leniently, for [sceneImageHashesOf]. */
internal fun partTextureHashesOf(parts: JsonArray?): List<String> =
    parts.orEmpty().mapNotNull { part ->
        val texture = (part as? JsonObject)?.get("texture") as? JsonObject
        (texture?.get("image") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { SCENE_IMAGE_HASH.matches(it) }
    }

/**
 * The size [params]' model has before it is fitted — [modelBounds] of its parts — or null where
 * [params] is not a well-formed `MODEL` (the write's own validation says why). `set_scene` sizes a
 * model to it where a row leaves a size out, so parts written in metres are drawn at their size.
 */
fun naturalModelSize(params: JsonObject): ModelBounds? {
    if (enumOrNull<ObjectShape>((params["shape"] as? JsonPrimitive)?.contentOrNull) != ObjectShape.MODEL) return null
    val parts = params["parts"] as? JsonArray ?: return null
    val scratch = mutableListOf<String>()
    return readModelParts(parts, "parts", scratch, imageStored = { true })?.let(::modelBounds)
}

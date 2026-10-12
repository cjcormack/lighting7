package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `MODEL` object's parts (`models/sceneModel.kt`): validated every problem at once at the write
 * boundary, stored canonically, and summed to the bounds the Stage view fits them by — the shared
 * vector `src/test/resources/stage/modelBounds.fixture.json`, which the frontend's
 * `builders/model.test.ts` reads too.
 */
class SceneModelTest {

    private val hash = "ab".repeat(32)

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun parse(kind: StageElementKind, text: String, stored: Set<String> = setOf(hash)): Pair<ElementParams?, List<String>> {
        val problems = mutableListOf<String>()
        return parseElementParams(kind, obj(text), 2.0, 3.0, "params", problems, imageStored = { it in stored }) to problems
    }

    @Test
    fun `a model's parts parse case-insensitively and encode canonically with defaults omitted`() {
        val (params, problems) = parse(
            StageElementKind.OBJECT,
            """{"shape":"model","flies":true,"parts":[
                {"shape":"box","widthM":1,"depthM":0.5,"heightM":2,"colour":"#AA3300","pattern":"boards"},
                {"shape":"Cylinder","z":2.5,"widthM":0.8,"topWidthM":0,"heightM":1,"yawDeg":15,"shadow":false},
                {"shape":"extrude","name":"Arch","outline":[[0,0],[2,0],[1,3]],"holes":[[[0.8,0.5],[1.2,0.5],[1,1]]],"depthM":0.2,
                 "texture":{"image":"${hash.uppercase()}","mode":"tile","tileM":0.5}},
                {"shape":"lathe","profile":[[0.4,0],[0.1,1]],"segments":12,"emissive":true},
                {"shape":"sphere","widthM":1}
            ]}""",
        )
        assertEquals(emptyList(), problems)
        val model = assertIs<ObjectParams>(params)
        assertEquals(ObjectShape.MODEL, model.shape)
        assertEquals(5, model.parts.size)
        assertEquals(PartTexture(hash, PartTextureMode.TILE, 0.5), model.parts[2].texture)
        val encoded = encodeElementParams(StageElementKind.OBJECT, model)
        assertTrue(""""shape":"MODEL"""" in encoded, encoded)
        assertTrue(""""colour":"#aa3300"""" in encoded, encoded)
        assertTrue(""""shape":"CYLINDER"""" in encoded, encoded)
        // A sphere's unstated depth and height stay unstated: they default to its width when read.
        assertTrue("""{"shape":"SPHERE","widthM":1.0}""" in encoded, encoded)
        assertEquals(model, readElementParams(StageElementKind.OBJECT, encoded))
    }

    @Test
    fun `every problem in a model's parts is reported at once, each naming its part`() {
        val (params, problems) = parse(
            StageElementKind.OBJECT,
            """{"shape":"MODEL","parts":[
                {"shape":"box","widthM":1,"heightM":2,"outline":[[0,0]]},
                {"shape":"torus"},
                {"shape":"EXTRUDE","outline":[[0,0],[1,1],[2,2]],"depthM":0.1},
                {"shape":"LATHE","profile":[[0,0],[-1,1]]},
                {"shape":"SPHERE","widthM":1,"x":400,"colour":"red","texture":{"image":"${"9".repeat(64)}","tileM":2}},
                "a box"
            ]}""",
        )
        assertNull(params)
        val expected = listOf(
            "params.parts[0] (BOX).depthM is required",
            "params.parts[0] (BOX): unknown field 'outline'",
            "params.parts[1].shape must be one of BOX, CYLINDER, SPHERE, EXTRUDE, LATHE",
            "params.parts[2] (EXTRUDE).outline encloses no area",
            "params.parts[3] (LATHE).profile: a radius cannot be negative",
            "params.parts[4].x must be between",
            "params.parts[4].colour must be a colour as #rrggbb",
            "params.parts[4].texture.image names no stored image",
            "params.parts[4].texture.tileM is a TILE texture's",
            "params.parts[5] must be an object",
        )
        for (e in expected) assertTrue(problems.any { it.startsWith(e) }, "missing '$e' in $problems")
    }

    @Test
    fun `parts are a MODEL's, refused by name anywhere else, and a MODEL needs them`() {
        assertTrue(parse(StageElementKind.OBJECT, """{"shape":"BOX","parts":[{"shape":"BOX","widthM":1,"depthM":1,"heightM":1}]}""").second
            .any { it == "params.parts is a MODEL's (an OBJECT with shape MODEL); this OBJECT's shape is BOX" })
        assertTrue(parse(StageElementKind.FLAT, """{"parts":[]}""").second
            .any { it == "params.parts is a MODEL's (an OBJECT with shape MODEL); this FLAT takes none" })
        assertTrue(parse(StageElementKind.OBJECT, """{"shape":"MODEL"}""").second.any { it.startsWith("params.parts is required for a MODEL") })
        assertTrue(parse(StageElementKind.OBJECT, """{"shape":"MODEL","parts":[]}""").second.any { it == "params.parts must hold 1–200 parts" })
    }

    @Test
    fun `a model flat along an axis cannot be fitted, and is refused`() {
        val (_, problems) = parse(
            StageElementKind.OBJECT,
            """{"shape":"MODEL","parts":[{"shape":"LATHE","profile":[[0.5,1],[0.2,1]]}]}""",
        )
        assertTrue(problems.any { it == "params.parts: the model is flat along z, so it cannot be fitted to its element" }, problems.toString())
    }

    @Test
    fun `the bounds answer the shared vector`() {
        val text = checkNotNull(javaClass.getResource("/stage/modelBounds.fixture.json")) { "the shared vector is missing" }.readText()
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 6)
        for (c in cases) {
            val name = c.jsonObject["name"]!!.jsonPrimitive.content
            val problems = mutableListOf<String>()
            val parts = assertNotNull(readModelParts(c.jsonObject["parts"]!!.jsonArray, name, problems, imageStored = { true }), "$name: $problems")
            val b = modelBounds(parts)
            val want = c.jsonObject["bounds"]!!.jsonObject
            for ((key, got) in listOf("minX" to b.minX, "minY" to b.minY, "minZ" to b.minZ, "maxX" to b.maxX, "maxY" to b.maxY, "maxZ" to b.maxZ)) {
                assertEquals(want[key]!!.jsonPrimitive.double, got, 1e-9, "$name: $key")
            }
        }
    }

    @Test
    fun `a model's natural size is its bounds, and nothing for anything that is not a model`() {
        val size = assertNotNull(
            naturalModelSize(obj("""{"shape":"MODEL","parts":[{"shape":"BOX","widthM":2,"depthM":1,"heightM":3},{"shape":"BOX","x":3,"widthM":1,"depthM":1,"heightM":1}]}""")),
        )
        assertEquals(4.5, size.widthM, 1e-9)
        assertEquals(1.0, size.depthM, 1e-9)
        assertEquals(3.0, size.heightM, 1e-9)
        assertNull(naturalModelSize(obj("""{"shape":"BOX"}""")))
        assertNull(naturalModelSize(obj("""{"shape":"MODEL","parts":[{"shape":"BOX"}]}""")))
    }

    @Test
    fun `a model's textures are scene images the store keeps, beside a cloth's paint`() {
        val other = "cd".repeat(32)
        val stored = """{"shape":"MODEL","parts":[{"shape":"BOX","texture":{"image":"${hash.uppercase()}"}},{"shape":"SPHERE","texture":{"image":"nope"}},{"shape":"BOX"}],
            "paint":{"front":"$other"}}"""
        assertEquals(setOf(hash, other), sceneImageHashesOf(stored))
        assertEquals(emptySet(), sceneImageHashesOf("""{"parts":"not a list"}"""))
    }
}

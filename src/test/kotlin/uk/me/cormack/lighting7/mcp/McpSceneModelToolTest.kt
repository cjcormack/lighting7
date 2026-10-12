package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.storedParamsText
import uk.me.cormack.lighting7.sync.SceneImageRepoSync
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.scenePng
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `set_scene` making and applying a `MODEL` object — parts written in metres drawn at their own
 * size, `modelFrom` placing copies, a part textured with an uploaded image — and `get_scene` reading
 * a model back with its own size.
 */
class McpSceneModelToolTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): List<String> =
        json()["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun element(name: String): JsonObject =
        call("get_scene", "{}").json()["elements"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    private fun JsonObject.number(key: String): Double = this[key]!!.jsonPrimitive.double

    /** A tree: a trunk and a cone of foliage, 1.6 m across and 3 m tall, in metres. */
    private val treeParts = """[
        {"shape":"CYLINDER","name":"Trunk","z":0.5,"widthM":0.25,"heightM":1},
        {"shape":"CYLINDER","name":"Foliage","z":2,"widthM":1.6,"topWidthM":0,"heightM":2,"colour":"#2f6b2a"}
    ]"""

    @Test
    fun `a model sent without a size is drawn at its parts' size, and one sent with a size is fitted to it`() {
        val made = call(
            "set_scene",
            """{"elements":[
                {"name":"Tree 1","kind":"OBJECT","layer":"SET","x":-2,"y":4,"params":{"shape":"MODEL","parts":$treeParts}},
                {"name":"Big tree","kind":"OBJECT","layer":"SET","x":2,"y":4,"heightM":6,"params":{"shape":"MODEL","parts":$treeParts}}
            ]}""",
        )
        assertTrue(made.success, made.result)
        val tree = element("Tree 1")
        assertEquals(1.6, tree.number("widthM"), 1e-9)
        assertEquals(1.6, tree.number("depthM"), 1e-9)
        assertEquals(3.0, tree.number("heightM"), 1e-9)
        val big = element("Big tree")
        assertEquals(6.0, big.number("heightM"), 1e-9)
        assertEquals(1.6, big.number("widthM"), 1e-9)
        // get_scene says a model's own size, and a row read back is sent back as it stands.
        assertEquals(3.0, big["modelSize"]!!.jsonObject.number("heightM"), 1e-9)
        assertEquals(2, big["params"]!!.jsonObject["parts"]!!.jsonArray.size)
        val resent = call("set_scene", """{"elements":[$big]}""")
        assertTrue(resent.success, resent.result)
        assertEquals(6.0, element("Big tree").number("heightM"), 1e-9)
    }

    @Test
    fun `modelFrom places a copy with the source's parts, size and finish, each overridable`() {
        val made = call(
            "set_scene",
            """{"elements":[
                {"name":"Tree 1","kind":"OBJECT","layer":"SET","x":-2,"y":4,"finish":{"colour":"#553311"},"params":{"shape":"MODEL","parts":$treeParts}},
                {"name":"Tree 2","x":0,"y":5,"modelFrom":"Tree 1"},
                {"name":"Tree 3","x":2,"y":5,"heightM":4.5,"modelFrom":"Tree 1","params":{"flies":true,"states":{"trimM":6}}}
            ]}""",
        )
        assertTrue(made.success, made.result)
        val source = element("Tree 1")
        val copy = element("Tree 2")
        assertEquals("OBJECT", copy["kind"]!!.jsonPrimitive.content)
        assertEquals(source["params"], copy["params"])
        assertEquals(source.number("heightM"), copy.number("heightM"), 1e-9)
        assertEquals("#553311", copy["finish"]!!.jsonObject["colour"]!!.jsonPrimitive.content)
        val flown = element("Tree 3")
        assertEquals(4.5, flown.number("heightM"), 1e-9)
        assertEquals(1.6, flown.number("widthM"), 1e-9)
        assertEquals(true, flown["params"]!!.jsonObject["flies"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(source["params"]!!.jsonObject["parts"], flown["params"]!!.jsonObject["parts"])
    }

    @Test
    fun `modelFrom is refused for a name that is not a model, one set later in the call, or beside parts`() {
        call("set_scene", """{"elements":[{"name":"Crate","kind":"OBJECT","widthM":1,"depthM":1,"heightM":1}]}""").also { assertTrue(it.success, it.result) }
        val refused = call(
            "set_scene",
            """{"elements":[
                {"name":"A","modelFrom":"Crate"},
                {"name":"B","modelFrom":"Nowhere"},
                {"name":"C","modelFrom":"Later"},
                {"name":"D","modelFrom":"Later","params":{"parts":[]}},
                {"name":"Later","kind":"OBJECT","params":{"shape":"MODEL","parts":$treeParts}}
            ]}""",
        )
        assertFalse(refused.success)
        val problems = refused.problems()
        assertTrue(problems.any { it == "elements[0] ('A'): modelFrom names 'Crate', which is not an OBJECT with shape MODEL" }, problems.toString())
        assertTrue(problems.any { it.startsWith("elements[1] ('B'): modelFrom names no element 'Nowhere'") }, problems.toString())
        assertTrue(problems.any { it.startsWith("elements[2] ('C'): modelFrom names no element 'Later'") }, problems.toString())
        assertTrue(problems.any { it.startsWith("elements[3] ('D'): modelFrom names no element 'Later'") }, problems.toString())
    }

    @Test
    fun `a part textured with an uploaded image is refused until the image is stored, and the store then keeps it`() {
        val png = scenePng(64, 64, seed = 5)
        val unknown = "7".repeat(64)
        val refused = call(
            "set_scene",
            """{"elements":[{"name":"Column","kind":"OBJECT","params":{"shape":"MODEL","parts":[
                {"shape":"LATHE","profile":[[0.2,0],[0.15,2.5],[0.25,2.6]],"texture":{"image":"$unknown","mode":"TILE","tileM":0.4}}]}}]}""",
        )
        assertFalse(refused.success)
        assertTrue(refused.problems().any { it == "elements[0] ('Column').params.parts[0].texture.image names no stored image" }, refused.result)

        val hash = call(
            "upload_scene_image",
            """{"mediaType":"image/png","base64":"${Base64.getEncoder().encodeToString(png)}"}""",
        ).json()["hash"]!!.jsonPrimitive.content
        val written = call(
            "set_scene",
            """{"elements":[{"name":"Column","kind":"OBJECT","params":{"shape":"MODEL","parts":[
                {"shape":"LATHE","profile":[[0.2,0],[0.15,2.5],[0.25,2.6]],"texture":{"image":"$hash","mode":"TILE","tileM":0.4}}]}}]}""",
        )
        assertTrue(written.success, written.result)
        val column = element("Column")
        assertEquals(0.5, column.number("widthM"), 1e-9)
        assertEquals(2.6, column.number("heightM"), 1e-9)
        // The image is referenced, so sync exports it and the prune keeps it.
        val referenced = transaction(state.database) { SceneImageRepoSync.referencedHashes(DaoProject.findById(projectId)!!.id.value) }
        assertTrue(hash in referenced, referenced.toString())
    }

    @Test
    fun `a show copying a venue's textured model takes the texture into its own store`() {
        val f = seedShowOnVenue(state)
        val png = scenePng(48, 48, seed = 9)
        // The venue's model, on the pinned version, textured with an image only the venue's store holds.
        val (hash, showUuid) = transaction(state.database) {
            val show = DaoProject[f.showId]
            val snapshot = DaoProject[RigScope.of(show).venue!!.snapshotId]
            val hash = state.sceneImages.store(snapshot.uuid.toString(), png, "image/png").hash
            DaoStageElement.new {
                project = snapshot
                name = "Venue column"
                kind = StageElementKind.OBJECT.name
                layer = StageElementLayer.VENUE.name
                widthM = 0.5; depthM = 0.5; heightM = 3.0
                params = storedParamsText(
                    Json.parseToJsonElement(
                        """{"shape":"MODEL","parts":[{"shape":"LATHE","profile":[[0.25,0],[0.2,3]],"texture":{"image":"$hash"}}]}""",
                    ).jsonObject,
                )
                sortOrder = 99
            }
            hash to show.uuid.toString()
        }
        assertFalse(state.sceneImages.exists(showUuid, hash))

        val copied = call("set_scene", """{"projectId":${f.showId},"elements":[{"name":"Column copy","layer":"SET","x":1,"modelFrom":"Venue column"}]}""")
        assertTrue(copied.success, copied.result)
        assertEquals(1, copied.json()["imagesCopied"]!!.jsonPrimitive.content.toInt())
        assertTrue(state.sceneImages.exists(showUuid, hash), "the show's own store now holds the texture")
        val referenced = transaction(state.database) { SceneImageRepoSync.referencedHashes(f.showId) }
        assertTrue(hash in referenced, referenced.toString())
    }
}

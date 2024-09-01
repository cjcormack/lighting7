package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoScene
import uk.me.cormack.lighting7.models.DaoScenes
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.scriptSettings.IntValue
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.state.State

@OptIn(DelicateCoroutinesApi::class)
internal fun Route.routeApiRestLightsScene(state: State) {
    route("/scene") {
        get("/list") {
            val scenes = transaction(state.database) {
                state.show.project.scenes.orderBy(DaoScenes.id to SortOrder.DESC).toList().map {
                    SceneDetails(
                        it.id.value,
                        it.name,
                        it.script.id.value,
                        state.show.fixtures.isSceneActive(it.name),
                        it.settingsValues.orEmpty(),
                    )
                }
            }
            call.respond(scenes)
        }

        post<SceneResource> {
            val newScene = call.receive<NewScene>()
            val sceneDetails = transaction(state.database) {
                val sceneScript = DaoScript.findById(newScene.scriptId) ?: throw Error("Script not found")
                if (sceneScript.project.id != state.show.project.id) {
                    throw Error("Wrong project")
                }
                val scene = DaoScene.new {
                    name = newScene.name
                    script = sceneScript
                    project = state.show.project
                    settingsValues = newScene.settingsValues
                }
                SceneDetails(
                    scene.id.value,
                    scene.name,
                    scene.script.id.value,
                    state.show.fixtures.isSceneActive(scene.name),
                    scene.settingsValues.orEmpty(),
                )
            }
            state.show.fixtures.scenesChanged()
            call.respond(sceneDetails)
        }

        get<SceneId> {
            val sceneDetails = transaction(state.database) {
                val scene = DaoScene.findById(it.id) ?: throw Error("Scene not found")

                SceneDetails(
                    scene.id.value,
                    scene.name,
                    scene.script.id.value,
                    state.show.fixtures.isSceneActive(scene.name),
                    scene.settingsValues.orEmpty(),
                )
            }
            call.respond(sceneDetails)
        }

        put<SceneId> {
            val newSceneDetails = call.receive<NewScene>()
            val sceneDetails = transaction(state.database) {
                val sceneScript = DaoScript.findById(newSceneDetails.scriptId) ?: throw Error("Script not found")
                if (sceneScript.project.id != state.show.project.id) {
                    throw Error("Wrong project")
                }

                val scene = DaoScene.findById(it.id) ?: throw Error("Scene not found")
                scene.name = newSceneDetails.name
                scene.script = sceneScript
                scene.settingsValues = newSceneDetails.settingsValues

                SceneDetails(
                    scene.id.value,
                    scene.name,
                    scene.script.id.value,
                    state.show.fixtures.isSceneActive(scene.name),
                    scene.settingsValues.orEmpty(),
                )
            }
            state.show.fixtures.scenesChanged()
            call.respond(sceneDetails)
        }

        delete<SceneId> {
            transaction(state.database) {
                DaoScene.findById(it.id)?.delete()
            }
            state.show.fixtures.scenesChanged()
            call.respond("")
        }

        post<SceneId.Run> {
            val runScene = call.receive<RunScene>()
            var response: RunResult? = null

            GlobalScope.launch {
                response = state.show.runScene(it.parent.id).toRunResult()
            }.join()
            call.respond(checkNotNull(response))
        }
    }
}

@Resource("/")
data object SceneResource

@Resource("/{id}")
data class SceneId(val id: Int) {
    @Resource("/run")
    data class Run(val parent: SceneId)
}

@Serializable
data class NewScene(
    val name: String,
    val scriptId: Int,
    // TODO needs to become generic
    val settingsValues: Map<String, IntValue>,
)

@Serializable
class RunScene()

@Serializable
data class SceneDetails(
    val id: Int,
    val name: String,
    val scriptId: Int,
    val isActive: Boolean,
    val settingsValues: Map<String, ScriptSettingValue>,
)

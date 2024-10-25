package uk.me.cormack.lighting7.routes

import io.ktor.http.*
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoScene
import uk.me.cormack.lighting7.models.DaoScenes
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.Mode
import uk.me.cormack.lighting7.scriptSettings.IntValue
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.State

@OptIn(DelicateCoroutinesApi::class)
internal fun Route.routeApiRestLightsScene(state: State) {
    route("/scene") {
        get<ListResource> {
            val sceneIds = transaction(state.database) {
                val scenes = DaoScene.find { (DaoScenes.project eq state.show.project.id) and (DaoScenes.mode eq it.mode) }
                    .orderBy(DaoScenes.id to SortOrder.DESC)

                scenes.map { it.id.value }
            }
            call.respond(sceneIds)
        }

        post<SceneResource> {
            val newScene = call.receive<NewScene>()
            val sceneDetails = transaction(state.database) {
                val sceneScript = DaoScript.findById(newScene.scriptId) ?: throw Error("Script not found")
                if (sceneScript.project.id != state.show.project.id) {
                    throw Error("Wrong project")
                }
                DaoScene.new {
                    mode = newScene.mode
                    name = newScene.name
                    script = sceneScript
                    project = state.show.project
                    settingsValues = newScene.settingsValues
                }.details(state.show)
            }
            state.show.fixtures.sceneListChanged()
            call.respond(sceneDetails)
        }

        get<SceneId> {
            val sceneDetails = transaction(state.database) {
                val scene = DaoScene.findById(it.id) ?: throw Error("Scene not found")
                scene.details(state.show)
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
                scene.mode = newSceneDetails.mode
                scene.name = newSceneDetails.name
                scene.script = sceneScript
                scene.settingsValues = newSceneDetails.settingsValues

                scene.details(state.show)
            }
            state.show.fixtures.sceneChanged(sceneDetails.id)
            call.respond(sceneDetails)
        }

        delete<SceneId> {
            transaction(state.database) {
                DaoScene.findById(it.id)?.delete()
            }
            state.show.fixtures.sceneListChanged()
            call.respond("")
        }

        post<SceneId.Run> {
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

@Resource("/list")
data class ListResource(
    val mode: Mode,
)

@Resource("/{id}")
data class SceneId(val id: Int) {
    @Resource("/run")
    data class Run(val parent: SceneId)
}

@Serializable
data class NewScene(
    val mode: Mode,
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
    val mode: Mode,
    val name: String,
    val scriptId: Int,
    val isActive: Boolean,
    val settingsValues: Map<String, ScriptSettingValue>,
)

internal fun DaoScene.details(show: Show): SceneDetails {
    return SceneDetails(
        this.id.value,
        this.mode,
        this.name,
        this.script.id.value,
        show.fixtures.isSceneActive(this.id.value),
        this.settingsValues.orEmpty(),
    )
}

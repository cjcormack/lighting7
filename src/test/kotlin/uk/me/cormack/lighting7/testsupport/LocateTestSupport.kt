package uk.me.cormack.lighting7.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.routes.ToggleLocateRequest
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State

/**
 * Rig-seeding + toggle helpers shared by the Locate route tests. Kept here rather than
 * duplicated per test class: the patch-row shape, the `"hex"` type key and the
 * [DbFixtureLoader] call are all things a schema or loader change has to be able to fix once.
 *
 * Hex channel map (relative to `startChannel`, 1-based): dimmer +0, R/G/B +1/+2/+3,
 * amber +4, white +5, uv +6, strobe +7, mode +9, program speed +10, dimmer mode +11.
 */
object LocateTestSupport {
    /** Patch one `hex` fixture at [startChannel] and reload the show's fixtures. */
    fun seedHex(state: State, projectId: Int, key: String, startChannel: Int) =
        seedFixture(state, projectId, "hex", key, startChannel)

    /**
     * Patch one fixture of [fixtureTypeKey] and reload. Generalises [seedHex] for tests that
     * need something a hex isn't — a moving head, for pan/tilt.
     */
    fun seedFixture(
        state: State,
        projectId: Int,
        fixtureTypeKey: String,
        key: String,
        startChannel: Int,
    ) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoFixturePatch.new {
                this.project = project
                universeConfig = DaoUniverseConfig.all().first()
                this.fixtureTypeKey = fixtureTypeKey
                this.key = key
                displayName = key
                this.startChannel = startChannel
                sortOrder = startChannel
            }
        }
        reloadFixtures(state, projectId)
    }

    /** Group the already-seeded patches [memberKeys] under [name] and reload. */
    fun seedGroup(state: State, projectId: Int, name: String, vararg memberKeys: String) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val group = DaoFixtureGroup.new { this.project = project; this.name = name }
            memberKeys.forEachIndexed { index, memberKey ->
                DaoFixtureGroupMember.new {
                    this.group = group
                    fixturePatch = DaoFixturePatch.find { DaoFixturePatches.key eq memberKey }.first()
                    sortOrder = index
                }
            }
        }
        reloadFixtures(state, projectId)
    }

    fun reloadFixtures(state: State, projectId: Int) {
        DbFixtureLoader.loadFixtures(
            projectId,
            state.show.fixtures,
            state.database,
            parkSource = state.show.parkManager,
        )
    }

    /** `POST /api/rest/locate/toggle` for one target. */
    suspend fun toggleLocate(client: HttpClient, type: String, key: String): HttpResponse =
        client.post("/api/rest/locate/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLocateRequest(type, key))
        }
}

package uk.me.cormack.lighting7.plugins

import io.ktor.resources.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
//import io.ktor.server.routing.
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.Script
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.State
import java.io.File
import kotlin.script.experimental.api.*



package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State

// Resource classes for type-safe routing

@Serializable
@Resource("/fx/definitions")
class FxDefinitionsResource

@Serializable
@Resource("/fx/definitions/{definitionId}")
class FxDefinitionResource(val definitionId: Int)

@Serializable
@Resource("/fx/definitions/compile")
class FxDefinitionCompileCheckResource

@Serializable
@Resource("/fx/definitions/{definitionId}/compile")
class FxDefinitionCompileResource(val definitionId: Int)

@Serializable
@Resource("/fx/definitions/{definitionId}/test")
class FxDefinitionTestResource(val definitionId: Int)

/**
 * REST API routes for FX definitions CRUD.
 *
 * These endpoints manage the fx_definitions table (user-created effects).
 * Built-in effects are read-only and served via the existing fx/library endpoint.
 */
internal fun Route.routeApiRestFxDefinitions(state: State) {

    // GET /fx/definitions - List all user-created definitions for the current project
    get<FxDefinitionsResource> {
        val currentProject = state.show.project
        val definitions = transaction(state.database) {
            DaoFxDefinition.find { DaoFxDefinitions.project eq currentProject.id }
                .map { it.toDto() }
        }
        call.respond(definitions)
    }

    // POST /fx/definitions - Create a new FX definition
    post<FxDefinitionsResource> {
        val request = call.receive<CreateFxDefinitionRequest>()
        val currentProject = state.show.project

        val definition = transaction(state.database) {
            DaoFxDefinition.new {
                effectId = request.effectId
                name = request.name
                category = request.category
                outputType = FxOutputType.valueOf(request.outputType)
                effectMode = EffectMode.valueOf(request.effectMode)
                parameters = request.parameters
                compatibleProperties = request.compatibleProperties
                script = request.script
                isBuiltin = false
                project = currentProject
                defaultStepTiming = request.defaultStepTiming
                timingSource = try { TimingSource.valueOf(request.timingSource) } catch (_: Exception) { TimingSource.BEAT }
            }
        }

        // Compile and register the effect
        val regResult = registerUserEffect(state, definition)
        if (!regResult.success) {
            call.respond(HttpStatusCode.UnprocessableEntity, FxCompileResponse(
                success = false,
                messages = regResult.diagnostics.map { FxCompileMessage(it.severity, it.message, it.location) },
            ))
            return@post
        }

        call.respond(HttpStatusCode.Created, transaction(state.database) { definition.toDto() })
    }

    // GET /fx/definitions/{id} - Get a single definition
    get<FxDefinitionResource> { resource ->
        val definition = transaction(state.database) {
            DaoFxDefinition.findById(resource.definitionId)?.toDto()
        }
        if (definition == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("FX definition not found"))
            return@get
        }
        call.respond(definition)
    }

    // PUT /fx/definitions/{id} - Update a definition
    put<FxDefinitionResource> { resource ->
        val request = call.receive<UpdateFxDefinitionRequest>()

        val definition = transaction(state.database) {
            val def = DaoFxDefinition.findById(resource.definitionId)
            if (def == null || def.isBuiltin) return@transaction null

            request.effectId?.let { def.effectId = it }
            request.name?.let { def.name = it }
            request.category?.let { def.category = it }
            request.outputType?.let { def.outputType = FxOutputType.valueOf(it) }
            request.effectMode?.let { def.effectMode = EffectMode.valueOf(it) }
            request.parameters?.let { def.parameters = it }
            request.compatibleProperties?.let { def.compatibleProperties = it }
            request.script?.let { def.script = it }
            request.defaultStepTiming?.let { def.defaultStepTiming = it }
            request.timingSource?.let { def.timingSource = try { TimingSource.valueOf(it) } catch (_: Exception) { TimingSource.BEAT } }

            def
        }

        if (definition == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("FX definition not found or is built-in"))
            return@put
        }

        // Re-compile and re-register
        val regResult = registerUserEffect(state, definition)
        if (!regResult.success) {
            call.respond(HttpStatusCode.UnprocessableEntity, FxCompileResponse(
                success = false,
                messages = regResult.diagnostics.map { FxCompileMessage(it.severity, it.message, it.location) },
            ))
            return@put
        }

        call.respond(transaction(state.database) { definition.toDto() })
    }

    // DELETE /fx/definitions/{id} - Delete a definition
    delete<FxDefinitionResource> { resource ->
        val deleted = transaction(state.database) {
            val def = DaoFxDefinition.findById(resource.definitionId) ?: return@transaction false
            if (def.isBuiltin) return@transaction false

            val effectId = def.effectId
            def.delete()

            // Unregister from FxRegistry
            state.show.fxRegistry.unregister(effectId)
            true
        }

        if (!deleted) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("FX definition not found or is built-in"))
            return@delete
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /fx/definitions/compile - Standalone compile check (no ID needed)
    post<FxDefinitionCompileCheckResource> {
        val request = call.receive<CompileFxDefinitionRequest>()
        val effectMode = EffectMode.valueOf(request.effectMode)

        val result = state.show.fxScriptCompiler.compileCheck(request.script, effectMode)
        call.respond(FxCompileResponse(
            success = result.success,
            messages = result.messages.map { FxCompileMessage(it.severity, it.message, it.location) },
        ))
    }

    // POST /fx/definitions/{id}/compile - Compile check
    post<FxDefinitionCompileResource> { resource ->
        val request = call.receive<CompileFxDefinitionRequest>()
        val effectMode = EffectMode.valueOf(request.effectMode)

        val result = state.show.fxScriptCompiler.compileCheck(request.script, effectMode)
        call.respond(FxCompileResponse(
            success = result.success,
            messages = result.messages.map { FxCompileMessage(it.severity, it.message, it.location) },
        ))
    }

    // POST /fx/definitions/{id}/test - Compile, register, and test
    post<FxDefinitionTestResource> { resource ->
        val definition = transaction(state.database) {
            DaoFxDefinition.findById(resource.definitionId)
        }

        if (definition == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("FX definition not found"))
            return@post
        }

        val regResult = registerUserEffect(state, definition)
        call.respond(FxCompileResponse(
            success = regResult.success,
            messages = regResult.diagnostics.map { FxCompileMessage(it.severity, it.message, it.location) },
        ))
    }
}

/**
 * Result of attempting to compile and register a user-created FX definition.
 */
data class RegisterEffectResult(
    val success: Boolean,
    val diagnostics: List<FxCompileDiagnostic> = emptyList(),
)

/**
 * Compile and register a user-created FX definition in the FxRegistry.
 */
private fun registerUserEffect(state: State, definition: DaoFxDefinition): RegisterEffectResult {
    // Read all fields in a single transaction
    data class DefSnapshot(
        val effectMode: EffectMode,
        val script: String,
        val effectId: String,
        val name: String,
        val outputType: FxOutputType,
        val parameters: List<ParameterInfo>,
        val compatibleProperties: List<String>,
        val category: String,
        val defaultStepTiming: Boolean,
        val timingSource: TimingSource,
        val dbId: Int,
    )

    val snap = transaction(state.database) {
        DefSnapshot(
            effectMode = definition.effectMode,
            script = definition.script,
            effectId = definition.effectId,
            name = definition.name,
            outputType = definition.outputType,
            parameters = definition.parameters,
            compatibleProperties = definition.compatibleProperties,
            category = definition.category,
            defaultStepTiming = definition.defaultStepTiming,
            timingSource = definition.timingSource,
            dbId = definition.id.value,
        )
    }

    val compiled = state.show.fxScriptCompiler.compile(snap.script, snap.effectMode)
    if (!compiled.isSuccess) {
        return RegisterEffectResult(
            success = false,
            diagnostics = compiled.diagnostics.map { FxCompileDiagnostic(it.severity, it.message, it.location) },
        )
    }

    val factory = ScriptEffectAdapter.createFactory(
        compiled = compiled,
        schema = snap.parameters,
        effectName = snap.name,
        outputType = snap.outputType,
        defaultStepTiming = snap.defaultStepTiming,
    )

    state.show.fxRegistry.register(EffectRegistration(
        id = snap.effectId,
        name = snap.name,
        category = snap.category,
        outputType = snap.outputType,
        effectMode = snap.effectMode,
        parameters = snap.parameters,
        compatibleProperties = snap.compatibleProperties,
        source = EffectSource.USER,
        sourceDefinitionId = snap.dbId,
        script = snap.script,
        defaultStepTiming = snap.defaultStepTiming,
        timingSource = snap.timingSource,
        factory = factory,
    ))

    return RegisterEffectResult(success = true)
}

// --- DTOs ---

@Serializable
data class FxDefinitionDto(
    val id: Int,
    val effectId: String,
    val name: String,
    val category: String,
    val outputType: String,
    val effectMode: String,
    val parameters: List<ParameterInfo>,
    val compatibleProperties: List<String>,
    val script: String,
    val isBuiltin: Boolean,
    val defaultStepTiming: Boolean,
    val timingSource: String = "BEAT",
)

@Serializable
data class CreateFxDefinitionRequest(
    val effectId: String,
    val name: String,
    val category: String,
    val outputType: String = "SLIDER",
    val effectMode: String = "STANDARD",
    val parameters: List<ParameterInfo> = emptyList(),
    val compatibleProperties: List<String> = emptyList(),
    val script: String,
    val defaultStepTiming: Boolean = false,
    val timingSource: String = "BEAT",
)

@Serializable
data class UpdateFxDefinitionRequest(
    val effectId: String? = null,
    val name: String? = null,
    val category: String? = null,
    val outputType: String? = null,
    val effectMode: String? = null,
    val parameters: List<ParameterInfo>? = null,
    val compatibleProperties: List<String>? = null,
    val script: String? = null,
    val defaultStepTiming: Boolean? = null,
    val timingSource: String? = null,
)

@Serializable
data class CompileFxDefinitionRequest(
    val script: String,
    val effectMode: String = "STANDARD",
)

@Serializable
data class FxCompileResponse(
    val success: Boolean,
    val messages: List<FxCompileMessage>,
)

@Serializable
data class FxCompileMessage(
    val severity: String,
    val message: String,
    val location: String? = null,
)

// Extension to convert DAO to DTO
internal fun DaoFxDefinition.toDto(): FxDefinitionDto = FxDefinitionDto(
    id = this.id.value,
    effectId = this.effectId,
    name = this.name,
    category = this.category,
    outputType = this.outputType.name,
    effectMode = this.effectMode.name,
    parameters = this.parameters,
    compatibleProperties = this.compatibleProperties,
    script = this.script,
    isBuiltin = this.isBuiltin,
    defaultStepTiming = this.defaultStepTiming,
    timingSource = this.timingSource.name,
)

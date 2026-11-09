package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.SurfaceGrammar
import uk.me.cormack.lighting7.midi.decodeBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `src/test/resources/sync/legacy-v52/` is `RichProjectFixture` as a desk on main exported it at
 * `formatVersion` 52, with a GO added on an X-Touch encoder, the button variant v53 moves to its push.
 * Frozen: never regenerate it from a later commit. Every binding imports in the grammar, and
 * re-exported it is the same archive with only its bindings translated.
 */
class LegacyV52ArchiveTest {

    private lateinit var state: State
    private lateinit var scratch: Path

    private val frozen: Path = Path.of("src/test/resources/sync/legacy-v52")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        scratch = Files.createTempDirectory("legacy-v52-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { scratch.toFile().deleteRecursively() }
    }

    private fun bindingFiles(): List<Path> =
        Files.list(frozen.resolve("controlSurfaceBindings")).use { files -> files.toList() }

    @Test
    fun `the frozen archive is v52 and every binding is a BindingTarget`() {
        assertEquals(52, canonicalDecode(FormatVersionJson.serializer(), Files.readString(frozen.resolve("formatVersion.json"))).formatVersion)
        for (file in bindingFiles()) {
            val payload = Files.readString(file).substringAfter("\"targetPayload\": ").substringBefore("\n")
            assertTrue("\\\"does\\\"" !in payload && "\\\"type\\\"" in payload, "${file.name} is an old binding")
        }
    }

    @Test
    fun `a v52 archive's bindings import in the grammar, an encoder's GO on its push`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId

        transaction(state.database) {
            val rows = DaoControlSurfaceBinding.all().filter { it.project.id.value == imported }
            assertEquals(bindingFiles().size, rows.size)
            for (row in rows) {
                val binding = decodeBinding(row.targetPayload, row.targetType)
                assertEquals(binding.functionId, row.targetType, "${row.controlId}: an old row imports translated")
                assertTrue(row.targetPayload.contains("\"does\""), row.targetPayload)
            }
            // The stack the frozen GO names, by uuid, which the import keeps.
            val stack = DaoCueStack.all().single { it.project.id.value == imported && it.uuid.toString() == "debbe51d-4996-44c7-978c-e2853790f759" }
            val go = rows.single { it.deviceTypeKey == "x-touch-compact-standard" && it.controlId == "enc-2" }
            assertEquals(ControlPart.PUSH.name, go.part)
            assertEquals(Binding(Does.Go, Subject.Record(RecordKind.STACK, stack.uuid)), decodeBinding(go.targetPayload, go.targetType))
            assertTrue(rows.filter { it != go }.all { it.part == ControlPart.MAIN.name }, "the fixture's other encoders hold turns")
        }
    }

    @Test
    fun `re-exported, it is the frozen archive with only its bindings translated`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId
        val exported = scratch.resolve("exported")
        ProjectExporter(state).export(imported, exported)

        val expected = scratch.resolve("expected")
        Files.walk(frozen).use { files -> files.toList() }.filter(Files::isRegularFile).forEach { file ->
            val rel = frozen.relativize(file)
            val folder = rel.getName(0).toString()
            val target = expected.resolve(rel.toString())
            Files.createDirectories(target.parent)
            when {
                rel.toString() == "formatVersion.json" ->
                    Files.writeString(target, canonicalEncode(FormatVersionJson.serializer(), FormatVersionJson()))
                folder == "controlSurfaceBindings" -> Files.writeString(target, LegacyNames.record(folder, Files.readString(file)))
                else -> Files.copy(file, target)
            }
        }
        assertExportsEqual(expected, exported, installsShapeOnly = true)
    }

    @Test
    fun `an archive naming a part that is not one is refused`() {
        val imported = ProjectImporter(state).import(frozen, nameOverride = null).projectId
        val exported = scratch.resolve("exported")
        ProjectExporter(state).export(imported, exported)
        val push = Files.list(exported.resolve("controlSurfaceBindings")).use { files -> files.toList() }
            .single { "\"PUSH\"" in Files.readString(it) }
        Files.writeString(push, Files.readString(push).replace("\"PUSH\"", "\"push\""))
        state.shutdown()
        IntegrationTestDb.reset()
        state = State(testAppConfig())

        val refused = assertFailsWith<ImportError> { ProjectImporter(state).import(exported, nameOverride = "Bad part") }
        assertTrue("push" in (refused.message ?: ""), refused.message)
    }

    @Test
    fun `an archive's translate and a database's are one function`() {
        val file = bindingFiles().first { Files.readString(it).contains("\"enc-2\"") }
        val translated = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson.serializer(),
            LegacyNames.record("controlSurfaceBindings", Files.readString(file)),
        )
        val old = canonicalDecode(uk.me.cormack.lighting7.sync.dto.ControlSurfaceBindingJson.serializer(), Files.readString(file))
        val row = SurfaceGrammar.row(old.deviceTypeKey, old.controlId, old.targetPayload, SurfaceGrammar.NoInts)!!
        assertEquals(row.payload, translated.targetPayload)
        assertEquals(row.targetType, translated.targetType)
        assertEquals(row.part.name, translated.part)
    }
}

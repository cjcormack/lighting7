package uk.me.cormack.lighting7.routes

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Tests that project DTOs no longer contain script-based configuration mode fields.
 */
class ProjectDtoTest {

    @Test
    fun `ProjectListDto does not have mode field`() {
        val fields = ProjectListDto::class.members.map { it.name }
        assertFalse(fields.contains("mode"), "ProjectListDto should not have a 'mode' field")
    }

    @Test
    fun `ProjectDetailDto does not have mode field`() {
        val fields = ProjectDetailDto::class.members.map { it.name }
        assertFalse(fields.contains("mode"), "ProjectDetailDto should not have a 'mode' field")
    }

    @Test
    fun `ProjectDetailDto does not have loadFixturesScriptId field`() {
        val fields = ProjectDetailDto::class.members.map { it.name }
        assertFalse(fields.contains("loadFixturesScriptId"), "ProjectDetailDto should not have a 'loadFixturesScriptId' field")
    }

    @Test
    fun `ProjectDetailDto does not have loadFixturesScriptName field`() {
        val fields = ProjectDetailDto::class.members.map { it.name }
        assertFalse(fields.contains("loadFixturesScriptName"), "ProjectDetailDto should not have a 'loadFixturesScriptName' field")
    }

    @Test
    fun `ProjectDetailDto does not have trackChangedScriptId field`() {
        val fields = ProjectDetailDto::class.members.map { it.name }
        assertFalse(fields.contains("trackChangedScriptId"), "ProjectDetailDto should not have a 'trackChangedScriptId' field")
    }

    @Test
    fun `ProjectDetailDto does not have trackChangedScriptName field`() {
        val fields = ProjectDetailDto::class.members.map { it.name }
        assertFalse(fields.contains("trackChangedScriptName"), "ProjectDetailDto should not have a 'trackChangedScriptName' field")
    }
}

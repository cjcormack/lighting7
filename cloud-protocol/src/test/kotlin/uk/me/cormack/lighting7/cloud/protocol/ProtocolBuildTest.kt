package uk.me.cormack.lighting7.cloud.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolBuildTest {
    /** The build reads the desk's Kotlin version; a literal pinned in this build's settings would fail here. */
    @Test
    fun `this module is compiled with the desk's Kotlin`() {
        assertEquals(System.getProperty("desk.kotlinVersion"), KotlinVersion.CURRENT.toString())
    }
}

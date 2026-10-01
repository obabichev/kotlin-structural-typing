package com.obabichev.structural.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StructuralGradlePluginTest {
    private val plugin = StructuralGradlePlugin()

    @Test
    fun `points at the compiler plugin published with it`() {
        val artifact = plugin.getPluginArtifact()
        assertEquals("structural-compiler-plugin", artifact.artifactId)
        // These are generated from the project's own coordinates: a release once nearly shipped pointing at a SNAPSHOT.
        assertEquals(System.getProperty("structural.expectedGroup"), artifact.groupId)
        assertEquals(System.getProperty("structural.expectedVersion"), artifact.version)
        assertEquals(System.getProperty("structural.expectedVersion"), ARTIFACT_VERSION)
    }

    @Test
    fun `names the compiler plugin the compiler loads`() {
        assertEquals("com.obabichev.structural", plugin.getCompilerPluginId())
    }

    @Test
    fun `applies to a project without the Kotlin plugin without adding anything`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(StructuralGradlePlugin::class.java)
        assertNull(project.configurations.findByName("implementation"))
    }
}

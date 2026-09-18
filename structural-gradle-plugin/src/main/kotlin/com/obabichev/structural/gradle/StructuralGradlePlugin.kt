package com.obabichev.structural.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies structural typing to a module: adds the `@Structural` annotation to its dependencies and the compiler plugin
 * to the Kotlin compilation.
 */
class StructuralGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            target.dependencies.add("implementation", "$ARTIFACT_GROUP:structural-annotations:$ARTIFACT_VERSION")
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "com.obabichev.structural"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(ARTIFACT_GROUP, "structural-compiler-plugin", ARTIFACT_VERSION)

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }
}

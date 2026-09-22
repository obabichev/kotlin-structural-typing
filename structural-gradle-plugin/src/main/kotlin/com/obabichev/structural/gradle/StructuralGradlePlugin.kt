package com.obabichev.structural.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/** Kotlin versions this release works with; the compiler plugin uses internal compiler APIs. */
private const val SUPPORTED_KOTLIN_LINE = "2.4."

/**
 * Applies structural typing to a module: adds the `@Structural` annotation to its dependencies and the compiler plugin
 * to the Kotlin compilation.
 */
class StructuralGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            checkKotlinVersion(target)
            target.dependencies.add("implementation", "$ARTIFACT_GROUP:structural-annotations:$ARTIFACT_VERSION")
        }
    }

    /** Without this, an unsupported Kotlin version fails inside the compiler with a NoClassDefFoundError. */
    private fun checkKotlinVersion(project: Project) {
        val version = kotlinPluginVersion(project) ?: return
        if (!version.startsWith(SUPPORTED_KOTLIN_LINE)) {
            throw GradleException(
                "The structural typing plugin $ARTIFACT_VERSION works with Kotlin ${SUPPORTED_KOTLIN_LINE}x, " +
                    "but this build uses Kotlin $version.",
            )
        }
    }

    // getKotlinPluginVersion() lives in the Kotlin Gradle plugin itself, which this plugin doesn't depend on.
    private fun kotlinPluginVersion(project: Project): String? = runCatching {
        Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt")
            .getMethod("getKotlinPluginVersion", Project::class.java)
            .invoke(null, project) as String
    }.getOrNull()

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "com.obabichev.structural"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(ARTIFACT_GROUP, "structural-compiler-plugin", ARTIFACT_VERSION)

    /**
     * Names the @Structural interfaces published by this compilation's dependencies, so classes here can implement
     * them. Resolved lazily: the compile classpath must not be resolved while the build is being configured.
     */
    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val classpath = kotlinCompilation.compileDependencyFiles
        return kotlinCompilation.target.project.provider {
            StructuralIndexReader.read(classpath).map { SubpluginOption("interface", it) }
        }
    }
}

package com.obabichev.structural.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lets the Kotlin IDE plugin load the structural compiler plugin without turning off
 * `kotlin.k2.only.bundled.compiler.plugins.enabled`: when a project's build uses the plugin, the IDE gets the copy shipped
 * with this IDE plugin, compiled against the IDE's own Kotlin compiler.
 */
class StructuralCompilerPluginProvider : KotlinBundledFirCompilerPluginProvider {
    override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? {
        if (!StructuralCompilerPluginJar.isStructuralCompilerPlugin(userSuppliedPluginJar)) return null
        val bundled = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
            ?.pluginPath
            ?.resolve("compiler-plugin/structural-compiler-plugin.jar")
            ?.takeIf { Files.isRegularFile(it) }
        if (bundled == null) LOG.warn("Structural compiler plugin jar is missing from the $PLUGIN_ID plugin distribution")
        return bundled
    }

    private companion object {
        const val PLUGIN_ID = "com.obabichev.structural.ide"
        val LOG = logger<StructuralCompilerPluginProvider>()
    }
}

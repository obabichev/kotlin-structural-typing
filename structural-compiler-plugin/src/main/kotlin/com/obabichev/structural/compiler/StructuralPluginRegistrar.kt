package com.obabichev.structural.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.name.ClassId

/** Entry point loaded by the Kotlin compiler (see META-INF/services). K2 only. */
class StructuralPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.obabichev.structural"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val imported = configuration.importedInterfaces()
        StructuralDebug.note(
            "registerExtensions options=${configuration.getList(IMPORTED_INTERFACES)} " +
                "contentRoots=${configuration.get(CLIConfigurationKeys.CONTENT_ROOTS)?.size} " +
                "output=${configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY)} imported=$imported",
        )
        FirExtensionRegistrarAdapter.registerExtension(StructuralFirRegistrar(imported))
        // No output directory when the IDE analyzes code, or when compiling straight to a jar: nothing to publish then.
        configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY)?.let { output ->
            IrGenerationExtension.registerExtension(StructuralIndexWriter(output))
        }
    }
}

/**
 * The @Structural interfaces published by dependencies. The build names them through [StructuralCommandLineProcessor];
 * failing that, the plugin reads the indexes off the classpath itself, which is what plain `kotlinc` and the tests do.
 */
private fun CompilerConfiguration.importedInterfaces(): List<ClassId> {
    val named = getList(IMPORTED_INTERFACES).mapNotNull { runCatching { ClassId.fromString(it) }.getOrNull() }
    if (named.isNotEmpty()) return named
    val classpath = get(CLIConfigurationKeys.CONTENT_ROOTS).orEmpty().filterIsInstance<JvmClasspathRoot>().map { it.file }
    return StructuralIndexFile.readFrom(classpath)
}

/** [imported]: @Structural interfaces published by dependencies, which this module can implement by shape too. */
class StructuralFirRegistrar(private val imported: List<ClassId> = emptyList()) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> StructuralSupertypeGenerator(session, imported) }
        +{ session: FirSession -> StructuralOverrideMarker(session, imported) }
        +{ session: FirSession -> StructuralCheckers(session, imported) }
        registerDiagnosticContainers(StructuralDiagnostics)
    }
}

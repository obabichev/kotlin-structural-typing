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
        val classpath = configuration.get(CLIConfigurationKeys.CONTENT_ROOTS)
            .orEmpty()
            .filterIsInstance<JvmClasspathRoot>()
            .map { it.file }
        FirExtensionRegistrarAdapter.registerExtension(
            StructuralFirRegistrar(StructuralIndexFile.readFrom(classpath)),
        )
        // No output directory when the IDE analyzes code, or when compiling straight to a jar: nothing to publish then.
        configuration.get(JVMConfigurationKeys.OUTPUT_DIRECTORY)?.let { output ->
            IrGenerationExtension.registerExtension(StructuralIndexWriter(output))
        }
    }
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

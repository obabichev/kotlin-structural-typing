package dev.structural.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** Entry point loaded by the Kotlin compiler (see META-INF/services). K2 only. */
class StructuralPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "dev.structural"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(StructuralFirRegistrar())
    }
}

class StructuralFirRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::StructuralSupertypeGenerator
        +::StructuralOverrideMarker
        +::StructuralCheckers
        registerDiagnosticContainers(StructuralDiagnostics)
    }
}

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.devkit.DevKitComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** Registers the FIR extensions. The DevKit generates the per-Kotlin-version entry point that loads this. */
class StructuralComponentRegistrar : DevKitComponentRegistrar {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
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

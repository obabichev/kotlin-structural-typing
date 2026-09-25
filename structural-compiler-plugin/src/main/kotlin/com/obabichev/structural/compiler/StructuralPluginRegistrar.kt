package com.obabichev.structural.compiler

import java.io.File
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** Entry point loaded by the Kotlin compiler (see META-INF/services). K2 only. */
class StructuralPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.obabichev.structural"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(StructuralFirRegistrar(configuration.jvmClasspathRoots))
    }
}

class StructuralFirRegistrar(private val classpath: List<File> = emptyList()) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::StructuralSupertypeGenerator
        +::StructuralOverrideMarker
        +::StructuralCheckers
        // Gives dependency classes their interfaces as the compiler deserializes them; see StructuralDeserialization.
        +{ session: FirSession -> StructuralLibrarySessionHook(session, classpath) }
        registerDiagnosticContainers(StructuralDiagnostics)
    }
}

package com.obabichev.structural.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.devkit.DevKitComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/** The entry point the DevKit wraps, once per Kotlin version. */
class StructuralComponentRegistrar : DevKitComponentRegistrar {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) =
        registerStructuralExtensions(configuration)
}

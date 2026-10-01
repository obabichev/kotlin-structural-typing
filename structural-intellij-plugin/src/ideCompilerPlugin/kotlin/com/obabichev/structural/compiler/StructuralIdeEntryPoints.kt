package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fakeElement

/**
 * Entry points for the copy of the compiler plugin that the IntelliJ plugin hands to the IDE. That copy is compiled
 * against the IDE's own Kotlin compiler and loaded through `META-INF/services`, so it needs a plain
 * `CompilerPluginRegistrar` and `CommandLineProcessor` rather than the ones the DevKit generates per Kotlin version.
 */
class StructuralPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "com.obabichev.structural"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) =
        registerStructuralExtensions(configuration)
}

class StructuralCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "com.obabichev.structural"
    override val pluginOptions: Collection<CliOption> = listOf(INTERFACE_OPTION)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            INTERFACE_OPTION -> configuration.add(IMPORTED_INTERFACES, value)
            else -> error("Unknown option ${option.optionName}")
        }
    }
}

/** The IDE ships a 2.4.20 compiler, so this is the post-2.4.20 form of the shim in `SourceElements.kt`. */
internal fun KtSourceElement.pluginGenerated(): KtSourceElement =
    fakeElement(KtFakeSourceElementKind.PluginGenerated.Default)

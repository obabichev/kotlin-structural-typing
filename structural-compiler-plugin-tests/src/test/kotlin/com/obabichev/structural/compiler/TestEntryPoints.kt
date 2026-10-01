package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fakeElement

/**
 * The parts the DevKit supplies when it builds the plugin, written plainly for these tests: they compile against one
 * Kotlin compiler, so there is no version to choose between.
 */
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

internal fun KtSourceElement.pluginGenerated(): KtSourceElement =
    fakeElement(KtFakeSourceElementKind.PluginGenerated.Default)

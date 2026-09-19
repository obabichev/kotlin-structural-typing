package com.obabichev.structural.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.devkit.DevKitCLP
import org.jetbrains.kotlin.config.CompilerConfiguration

/** The plugin has no options. */
@Suppress("unused") // Used via reflection.
class StructuralCommandLineProcessor : DevKitCLP {
    override val pluginOptions: Collection<CliOption> get() = emptyList()

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration): Unit =
        error("Unexpected config option: '${option.optionName}'")
}

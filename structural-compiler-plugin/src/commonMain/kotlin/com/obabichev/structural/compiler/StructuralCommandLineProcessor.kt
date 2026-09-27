package com.obabichev.structural.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.devkit.DevKitCLP
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Lets the Gradle plugin name the interfaces a module can implement, instead of the compiler plugin reading them from
 * the classpath itself. Both work when compiling; only this one reaches IntelliJ, which passes a module's compiler
 * plugin options from the Gradle import but doesn't hand plugins a classpath to read.
 */
class StructuralCommandLineProcessor : DevKitCLP {
    override val pluginOptions: Collection<CliOption> = listOf(INTERFACE_OPTION)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option) {
            INTERFACE_OPTION -> configuration.add(IMPORTED_INTERFACES, value)
            else -> error("Unknown option ${option.optionName}")
        }
    }
}

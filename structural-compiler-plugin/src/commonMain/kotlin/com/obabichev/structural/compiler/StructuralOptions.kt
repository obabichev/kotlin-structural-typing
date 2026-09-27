package com.obabichev.structural.compiler

import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** @Structural interfaces the build found on the compile classpath, one option per interface. */
val IMPORTED_INTERFACES: CompilerConfigurationKey<List<String>> =
    CompilerConfigurationKey.create("@Structural interfaces published by dependencies")

val INTERFACE_OPTION: CliOption = CliOption(
    optionName = "interface",
    valueDescription = "<class id>",
    description = "A @Structural interface published by a dependency, e.g. com/example/Sized",
    required = false,
    allowMultipleOccurrences = true,
)

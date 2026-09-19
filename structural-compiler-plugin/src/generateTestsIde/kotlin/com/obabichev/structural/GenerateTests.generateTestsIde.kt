package com.obabichev.structural

import org.jetbrains.kotlin.compiler.plugin.devkit.SourceSetName
import org.jetbrains.kotlin.generators.dsl.TestGroup

context(_: SourceSetName)
actual fun TestGroup.addExtraTests() {
}

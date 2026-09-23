package com.obabichev.structural.compiler.runners

import com.obabichev.structural.compiler.StructuralComponentRegistrar
import org.jetbrains.kotlin.compiler.plugin.devkit.runners.DevKitJvmBoxTest
import org.jetbrains.kotlin.compiler.plugin.devkit.services.configurePlugin

open class AbstractJvmBoxTest : DevKitJvmBoxTest({ configurePlugin(StructuralComponentRegistrar()) })

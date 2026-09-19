package com.obabichev.structural.compiler.runners

import com.obabichev.structural.compiler.StructuralComponentRegistrar
import org.jetbrains.kotlin.compiler.plugin.devkit.runners.DevKitJvmDiagnosticTest
import org.jetbrains.kotlin.compiler.plugin.devkit.services.configurePlugin

open class AbstractJvmDiagnosticTest : DevKitJvmDiagnosticTest({ configurePlugin(StructuralComponentRegistrar()) })

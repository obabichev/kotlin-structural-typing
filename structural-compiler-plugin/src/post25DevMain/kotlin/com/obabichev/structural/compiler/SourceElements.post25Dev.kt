package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement

internal actual fun KtSourceElement.pluginGenerated(): KtSourceElement =
    fakeElement(KtFakeSourceElementKind.PluginGenerated.Default)

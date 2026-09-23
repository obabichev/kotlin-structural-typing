package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fakeElement

/** Before 2.4.20 the kind itself marked a plugin-generated element. */
internal actual fun KtSourceElement.pluginGenerated(): KtSourceElement =
    fakeElement(KtFakeSourceElementKind.PluginGenerated)

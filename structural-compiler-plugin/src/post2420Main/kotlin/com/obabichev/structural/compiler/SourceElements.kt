package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fakeElement

/** From 2.4.20 the kind is abstract and plugins that don't name themselves use Default. */
internal actual fun KtSourceElement.pluginGenerated(): KtSourceElement =
    fakeElement(KtFakeSourceElementKind.PluginGenerated.Default)

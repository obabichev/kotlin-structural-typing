package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtSourceElement

/** Marks a source element as produced by this plugin. Its API differs between Kotlin versions. */
internal expect fun KtSourceElement.pluginGenerated(): KtSourceElement

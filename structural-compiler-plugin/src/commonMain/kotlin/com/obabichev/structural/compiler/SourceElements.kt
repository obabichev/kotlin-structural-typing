package com.obabichev.structural.compiler

import org.jetbrains.kotlin.KtSourceElement

/**
 * The source element to give a supertype the plugin adds. How a plugin-generated fake element is built changed twice in
 * the compiler, so each supported range has its own actual.
 */
internal expect fun KtSourceElement.pluginGenerated(): KtSourceElement

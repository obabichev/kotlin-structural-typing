package com.obabichev.structural.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.psi.KtElement

/**
 * Diagnostics of the plugin. Typed by [KtElement] rather than `PsiElement`: the Gradle compiler relocates IntelliJ classes
 * to `org.jetbrains.kotlin.com.intellij`, which doesn't exist when the IDE runs the plugin.
 */
object StructuralDiagnostics : KtDiagnosticsContainer() {
    val INFERRED_MEMBER_TYPES by warning1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    private object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("Structural") {
            it.put(INFERRED_MEMBER_TYPES, "{0}", CommonRenderers.STRING)
        }
    }
}

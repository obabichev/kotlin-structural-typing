package dev.structural.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.warning1

object StructuralDiagnostics : KtDiagnosticsContainer() {
    val INFERRED_PROPERTY_TYPES by warning1<PsiElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    private object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("Structural") {
            it.put(INFERRED_PROPERTY_TYPES, "{0}", CommonRenderers.STRING)
        }
    }
}

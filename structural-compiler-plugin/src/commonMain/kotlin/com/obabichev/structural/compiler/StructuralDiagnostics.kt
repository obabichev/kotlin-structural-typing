package com.obabichev.structural.compiler

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.psi.KtElement

/**
 * Diagnostics of the plugin. Typed by [KtElement] rather than `PsiElement`: the Gradle compiler relocates IntelliJ classes
 * to `org.jetbrains.kotlin.com.intellij`, which doesn't exist when the IDE runs the plugin.
 */
object StructuralDiagnostics : KtDiagnosticsContainer() {
    val INFERRED_MEMBER_TYPES by warning1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)

    /** On a @Structural interface that can never be added to a class; reported on its name. */
    val UNUSABLE_INTERFACE by warning1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)

    /** On a class that misses an interface by a detail; reported on its name. */
    val NEAR_MISS by warning1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)

    /**
     * On the argument the compiler rejects, next to its own `Argument type mismatch`. An error rather than a warning
     * because the compiler prints no warnings once a compilation has an error, which is exactly this situation; it is
     * only ever reported where resolution already failed.
     */
    val ARGUMENT_NEAR_MISS by error1<KtElement, String>(SourceElementPositioningStrategies.DEFAULT)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    private object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("Structural") {
            it.put(INFERRED_MEMBER_TYPES, "{0}", CommonRenderers.STRING)
            it.put(UNUSABLE_INTERFACE, "{0}", CommonRenderers.STRING)
            it.put(NEAR_MISS, "{0}", CommonRenderers.STRING)
            it.put(ARGUMENT_NEAR_MISS, "{0}", CommonRenderers.STRING)
        }
    }
}

package com.obabichev.structural.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import java.io.File

/**
 * A class from a dependency, matching a @Structural interface that also comes from a dependency. The compiler plugin
 * gives such a class its interfaces as it deserializes the class file (see StructuralDeserialization), which is the
 * only point where a class nobody can recompile can still gain a supertype.
 *
 * Whether that reaches the editor is a separate question from whether it works in a build: IntelliJ builds its sessions
 * its own way and hands plugins no classpath. This test answers it, because a feature that compiles but shows errors in
 * the editor is not usable.
 */
class StructuralDependencyClassHighlightingTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        Registry.get("kotlin.k2.only.bundled.compiler.plugins.enabled").setValue(false, testRootDisposable)
        val jar = checkNotNull(System.getProperty("structural.compilerPluginJar")) { "jar path not passed by Gradle" }
        KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
            pluginClasspaths = arrayOf(jar)
            pluginOptions = arrayOf("plugin:com.obabichev.structural:interface=com/example/shapes/Sized")
        }
        val jars = checkNotNull(System.getProperty("structural.fixtureLibrary")) { "libraries not passed by Gradle" }
        for (entry in jars.split(File.pathSeparator).filter { it.endsWith(".jar") }) {
            PsiTestUtil.addLibrary(myFixture.module, entry)
        }
    }

    private fun errorsIn(name: String, code: String): List<String> {
        myFixture.configureByText(name, code.trimIndent())
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .map { it.description }
            // The light fixture has no real JDK, so every file reports kotlin.Int's missing supertypes.
            .filterNot { it.contains("MISSING_DEPENDENCY") }
    }

    /**
     * Records that the feature does not reach the editor yet, so that it fails the day it does and this becomes the
     * assertion it should be: `assertEmpty(errors)`.
     *
     * The hook needs the dependency's class files to decide whether its shape matches, and IntelliJ hands compiler
     * plugins no classpath -- `CLIConfigurationKeys.CONTENT_ROOTS` is null there. The interfaces to match against do
     * arrive, through the plugin options the Gradle import passes on; the bytes to match do not.
     */
    fun testDependencyClassIsNotAcceptedInTheEditorYet() {
        val errors = errorsIn(
            "Use.kt",
            """
            import com.example.dependency.Rectangular
            import com.example.shapes.area

            fun use(): Int = area(Rectangular(2, 3, "red"))
            """,
        )
        assertEquals(
            listOf("[ARGUMENT_TYPE_MISMATCH] Argument type mismatch: actual type is 'Rectangular', but 'Sized' was expected."),
            errors,
        )
    }

    /** The control: a dependency class of the wrong shape must still be rejected. */
    fun testDependencyClassThatDoesNotMatchIsRejected() {
        val errors = errorsIn(
            "Reject.kt",
            """
            import com.example.dependency.Unrelated
            import com.example.shapes.area

            fun use(): Int = area(Unrelated(2))
            """,
        )
        assertFalse("a class that doesn't match must not be accepted", errors.isEmpty())
    }
}

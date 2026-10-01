package com.obabichev.structural.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import java.io.File

/**
 * The same analysis as [StructuralHighlightingTest], for an interface that comes from a dependency rather than from the
 * file being analyzed. Its own class because the fixture keeps one project per class and this one needs a library on
 * the module.
 */
class StructuralCrossModuleHighlightingTest : BasePlatformTestCase() {
    /**
     * The project is configured once, before anything is analyzed: the frontend keeps a session per project, so a file
     * analyzed before the compiler plugin is configured would be resolved without it and stay that way.
     */
    override fun setUp() {
        super.setUp()
        useStructuralPlugin(imported = listOf("com/example/shapes/Sized"))
        useFixtureLibrary()
    }

    /**
     * Configures the project the way a Gradle import does: the compiler plugin as a jar on the Kotlin compiler plugin
     * classpath, and one `interface` option per @Structural interface published by a dependency. The registry key is
     * what the IDE uses to refuse plugins it doesn't ship; the IntelliJ plugin's own answer to that is tested
     * separately, in StructuralCompilerPluginJarTest.
     */
    private fun useStructuralPlugin(imported: List<String> = emptyList()) {
        Registry.get("kotlin.k2.only.bundled.compiler.plugins.enabled").setValue(false, testRootDisposable)
        val jar = checkNotNull(System.getProperty("structural.compilerPluginJar")) { "jar path not passed by Gradle" }
        KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
            pluginClasspaths = arrayOf(jar)
            pluginOptions = imported.map { "plugin:com.obabichev.structural:interface=$it" }.toTypedArray()
        }
    }

    /** Puts `:sample-library`, which publishes a @Structural interface, on the module's classpath. */
    private fun useFixtureLibrary() {
        val jars = checkNotNull(System.getProperty("structural.fixtureLibrary")) { "library not passed by Gradle" }
        for (jar in jars.split(File.pathSeparator).filter { it.endsWith(".jar") }) {
            PsiTestUtil.addLibrary(myFixture.module, jar)
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

    /** The interface comes from a dependency, as it does for anyone with more than one module. */
    fun testMatchingClassIsAcceptedAsAnInterfaceFromAnotherModule() {
        val errors = errorsIn(
            "Photos.kt",
            """
            import com.example.shapes.Sized
            import com.example.shapes.area

            class Photo(val width: Int, val height: Int)

            fun use(): Int = area(Photo(2, 3))

            fun asInterface(): Sized = Photo(2, 3)
            """,
        )
        assertEmpty(errors)
    }
}

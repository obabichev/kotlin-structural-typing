package com.obabichev.structural.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder

/**
 * Analyzes Kotlin code the way the IDE does: through the Kotlin plugin running in this test IDE, which resolves
 * declarations on demand instead of phase by phase. Every bug that only showed in the editor -- enum supertypes, the
 * missing `override`, matching an interface published by another module -- was invisible to the compiler plugin's own
 * tests, because those run the command line compiler.
 *
 * The plugin's extensions are handed to the analysis directly, which is what a Gradle project's compiler plugin
 * classpath ends up doing.
 */
class StructuralHighlightingTest : BasePlatformTestCase() {
    private val annotation = """
        package com.obabichev.structural
        annotation class Structural
    """.trimIndent()

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

    private fun errorsIn(name: String, code: String): List<String> {
        myFixture.configureByText(name, code.trimIndent())
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .map { it.description }
            // The light fixture has no real JDK, so every file reports kotlin.Int's missing supertypes.
            .filterNot { it.contains("MISSING_DEPENDENCY") }
    }

    fun testPlainKotlinFileHasNoErrors() {
        assertEmpty(errorsIn("Plain.kt", "class Rectangular(val width: Int, val height: Int)"))
    }

    /** Proves the Kotlin plugin really analyzes the file: without this, an empty result would mean nothing. */
    fun testKotlinErrorsAreReported() {
        assertFalse(errorsIn("Broken.kt", "fun broken(): Int = \"not an int\"").isEmpty())
    }

    fun testMatchingClassIsAcceptedAsTheInterface() {
        useStructuralPlugin()
        myFixture.addFileToProject("Structural.kt", annotation)
        val errors = errorsIn(
            "Shapes.kt",
            """
            import com.obabichev.structural.Structural

            @Structural
            interface Sized {
                val width: Int
                val height: Int
            }

            class Rectangular(val width: Int, val height: Int)

            fun size(target: Sized) = target.width * target.height

            fun use() = size(Rectangular(2, 3))
            """,
        )
        assertEmpty(errors)
    }

}

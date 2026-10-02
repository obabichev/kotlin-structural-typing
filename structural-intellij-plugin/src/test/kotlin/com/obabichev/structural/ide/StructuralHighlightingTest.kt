package com.obabichev.structural.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import java.io.File

/**
 * Analyzes Kotlin code the way the IDE does: through the Kotlin plugin running in this test IDE, which resolves
 * declarations on demand instead of phase by phase. Every bug that only showed in the editor -- enum supertypes, the
 * missing `override`, matching an interface published by another module -- was invisible to the compiler plugin's own
 * tests, because those run the command line compiler.
 */
class StructuralHighlightingTest : BasePlatformTestCase() {
    private val annotation = """
        package com.obabichev.structural
        annotation class Structural
    """.trimIndent()

    /**
     * The project is configured once, before anything is analyzed: the frontend keeps a session per project, so a file
     * analyzed before the compiler plugin is configured would be resolved without it and stay that way.
     */
    override fun setUp() {
        super.setUp()
        useStructuralPlugin()
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

    private fun errorsIn(name: String, code: String): List<String> {
        myFixture.configureByText(name, code.trimIndent())
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .map { it.description }
            // The light fixture has no real JDK, so every file reports kotlin.Int's missing supertypes.
            .filterNot { it.contains("MISSING_DEPENDENCY") }
    }

    /** The editor should say why an interface is ignored, in the place the user can act on. */
    fun testAnInterfaceThatCanNeverApplyIsReported() {
        myFixture.addFileToProject("Structural.kt", annotation)
        myFixture.configureByText(
            "Box.kt",
            """
            import com.obabichev.structural.Structural

            @Structural
            interface Box<T> {
                val value: T
            }
            """.trimIndent(),
        )
        val warnings = myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .map { it.description }
        assertTrue(
            "expected the editor to explain the ignored interface, got $warnings",
            warnings.any { it.contains("'Box' is ignored") && it.contains("type parameters") },
        )
    }

    fun testPlainKotlinFileHasNoErrors() {
        assertEmpty(errorsIn("Plain.kt", "class Rectangular(val width: Int, val height: Int)"))
    }

    /** Proves the Kotlin plugin really analyzes the file: without this, an empty result would mean nothing. */
    fun testKotlinErrorsAreReported() {
        assertFalse(errorsIn("Broken.kt", "fun broken(): Int = \"not an int\"").isEmpty())
    }

    /**
     * A @Structural interface extending a generic one: the editor has to substitute the arguments as the build does,
     * both where the parameter is the member's type and where it appears inside another type.
     */
    fun testClassMatchingAnInterfaceExtendingAGenericOneIsAccepted() {
        myFixture.addFileToProject("Structural.kt", annotation)
        val errors = errorsIn(
            "Ranked.kt",
            """
            import com.obabichev.structural.Structural

            interface Many<T>

            object NoneAtAll : Many<String>

            interface Source<T> {
                fun load(): T
                val items: Many<T>
            }

            @Structural
            interface Texts : Source<String>

            class Note(val items: Many<String>) {
                fun load(): String = "hi"
            }

            fun read(texts: Texts): String = texts.load()

            fun use() = read(Note(NoneAtAll))
            """,
        )
        assertEmpty(errors)
    }

    fun testMatchingClassIsAcceptedAsTheInterface() {
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

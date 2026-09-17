package dev.structural.compiler

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.File
import kotlin.test.assertTrue

class Compiled(val succeeded: Boolean, val messages: String, private val classLoader: () -> ClassLoader) {
    val errors: List<String> get() = messages.lines().filter { it.startsWith("e:") }
    val warnings: List<String> get() = messages.lines().filter { it.startsWith("w:") }

    fun loadClass(name: String): Class<*> = classLoader().loadClass(name)

    /** Calls the top-level `run()` function of [className] after asserting that compilation succeeded. */
    fun run(className: String = "test.MainKt"): Any? {
        assertTrue(succeeded, messages)
        return loadClass(className).getMethod("run").invoke(null)
    }
}

fun kotlin(name: String, code: String): SourceFile = SourceFile.kotlin(name, code.trimIndent())

/** Compiles [sources] with the structural compiler plugin. */
fun compile(vararg sources: SourceFile, classpath: List<File> = emptyList(), withPlugin: Boolean = true): Compiled {
    val result = KotlinCompilation().apply {
        this.sources = sources.toList()
        if (withPlugin) compilerPluginRegistrars = listOf(StructuralPluginRegistrar())
        inheritClassPath = true
        classpaths = classpaths + classpath
        messageOutputStream = System.out
    }.compile()
    return Compiled(result.exitCode == KotlinCompilation.ExitCode.OK, result.messages) { result.classLoader }
}

/** `test.Sized` with two `Int` properties and `size(Sized)`. */
val SIZED = kotlin(
    "Sized.kt",
    """
    package test
    import dev.structural.Structural
    @Structural interface Sized { val width: Int; val height: Int }
    fun size(target: Sized) = target.width * target.height
    """,
)

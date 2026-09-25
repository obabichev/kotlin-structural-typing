package com.obabichev.structural.runtime

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.io.File
import kotlin.test.assertTrue

fun kotlin(name: String, code: String): SourceFile = SourceFile.kotlin(name, code.trimIndent())

/**
 * Compiles [sources] the way a dependency is built: an ordinary compilation, with no structural plugin anywhere, so
 * the class files carry no interface the runtime hasn't put there itself. Returns the directory holding them.
 */
fun dependency(vararg sources: SourceFile): File {
    val result = KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        messageOutputStream = System.out
    }.compile()
    assertTrue(result.exitCode == KotlinCompilation.ExitCode.OK, result.messages)
    val marker = result.classLoader.loadClass(MARKER)
    return File(marker.protectionDomain.codeSource.location.toURI())
}

/** Every compilation gets this, so the output directory can be found through it. */
const val MARKER = "dep.Marker"

val MARKER_SOURCE = kotlin("Marker.kt", "package dep\nclass Marker")

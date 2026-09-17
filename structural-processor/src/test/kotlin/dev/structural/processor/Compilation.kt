@file:OptIn(ExperimentalCompilerApi::class)

package dev.structural.processor

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

const val TEST_PACKAGES = "test.model"

class Compiled(
    val succeeded: Boolean,
    val messages: String,
    val generated: Map<String, String>,
    private val classLoader: () -> ClassLoader,
) {
    fun call(className: String, method: String): Any? =
        classLoader().loadClass(className).getMethod(method).invoke(null)

    fun generatedFile(name: String): String =
        generated[name] ?: error("No generated file $name; generated: ${generated.keys}")
}

fun kotlin(name: String, code: String): SourceFile = SourceFile.kotlin(name, code.trimIndent())

/** Compiles [sources] with the real processor. */
fun compile(
    vararg sources: SourceFile,
    options: Map<String, String> = mapOf(Config.PACKAGES_OPTION to TEST_PACKAGES),
): Compiled = compileWith(StructuralProcessorProvider(), sources.toList(), options)

/** Runs [block] once inside a KSP round over [sources], for testing components that need a [Resolver]. */
fun <T> resolve(vararg sources: SourceFile, block: (Resolver, KSPLogger) -> T): Pair<T, Compiled> {
    var outcome: Result<T>? = null
    val provider = object : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment) = object : SymbolProcessor {
            override fun process(resolver: Resolver): List<KSAnnotated> {
                if (outcome == null) outcome = runCatching { block(resolver, environment.logger) }
                return emptyList()
            }
        }
    }
    val compiled = compileWith(provider, sources.toList(), emptyMap())
    val result = outcome ?: error("Processor did not run:\n${compiled.messages}")
    return result.getOrThrow() to compiled
}

private fun compileWith(
    provider: SymbolProcessorProvider,
    sources: List<SourceFile>,
    options: Map<String, String>,
): Compiled {
    val compilation = KotlinCompilation().apply {
        this.sources = sources
        inheritClassPath = true
        messageOutputStream = System.out
        configureKsp {
            symbolProcessorProviders += provider
            processorOptions.putAll(options)
        }
    }
    val result = compilation.compile()
    return Compiled(
        succeeded = result.exitCode == KotlinCompilation.ExitCode.OK,
        messages = result.messages,
        generated = result.sourcesGeneratedBySymbolProcessor.associate { it.name to it.readText() },
        classLoader = { result.classLoader },
    )
}

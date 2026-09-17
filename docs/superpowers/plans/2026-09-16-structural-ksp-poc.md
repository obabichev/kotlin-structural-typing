# Structural Typing KSP PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A KSP processor that generates overloads plus proxy classes, so functions taking a `@Structural` interface accept any class with a matching shape.

**Architecture:** Three Gradle modules: `structural-annotations` (the annotation), `structural-processor` (KSP2 processor built from small components: Config, InterfaceRegistry, Matcher, Candidates, FunctionCollector, OverloadPlanner, Generator) and `sample` (end-to-end consumer). The planner is pure logic over type names; everything touching KSP is tested through kotlin-compile-testing.

**Tech Stack:** Kotlin 2.4.20, KSP 2.3.12 (KSP2), KotlinPoet 2.4.0 (`kotlinpoet-ksp`), kctfork 0.14.0 (`dev.zacsweers.kctfork:ksp`), Gradle 9.7.1, JUnit 5 via `kotlin("test")`.

**Spec:** `proposal.md`

## Global Constraints

- Gradle runs on JDK 17: prefix every Gradle command with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`; all modules use `kotlin { jvmToolchain(17) }`.
- Versions exactly: Kotlin `2.4.20`, KSP `2.3.12`, KotlinPoet `2.4.0`, kctfork `0.14.0`, Gradle `9.7.1`.
- Annotation FQN: `dev.structural.Structural`, `@Target(AnnotationTarget.CLASS)`, `@Retention(AnnotationRetention.BINARY)`.
- Processor package: `dev.structural.processor`. KSP option: `structural.packages`.
- Every processor message starts with `[structural] `.
- Warning formats (verbatim): `[structural] Skipping <fqName>(<params>): <reason>` and
  `[structural] Not generating <fqName> for <candidate>: it matches several @Structural interfaces (<ifaces, comma-separated>)`.
- Generated names: overload files `<File>_Structural.kt` / `<Owner>_Structural.kt`; proxies `<C>_As<I>`; local variable `structuralArgument`; proxy property `target`.
- No commits unless the user asks (repository is on `main`).

---

### Task 1: Build scaffolding, annotation, Config

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle wrapper
- Create: `structural-annotations/build.gradle.kts`, `structural-annotations/src/main/kotlin/dev/structural/Structural.kt`
- Create: `structural-processor/build.gradle.kts`, `structural-processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
- Create: `structural-processor/src/main/kotlin/dev/structural/processor/Config.kt`, `StructuralProcessor.kt`
- Test: `structural-processor/src/test/kotlin/dev/structural/processor/Compilation.kt`, `ConfigTest.kt`

**Interfaces:**
- Produces: `class Config(val packages: List<String>) { fun includesPackage(name: String): Boolean; companion object { const val PACKAGES_OPTION = "structural.packages"; fun parse(options: Map<String, String>): Config? } }`
- Produces (tests): `fun kotlin(name: String, code: String): SourceFile`, `fun compile(vararg sources: SourceFile, options: Map<String, String> = …): Compiled`, `fun <T> resolve(vararg sources: SourceFile, block: (Resolver, KSPLogger) -> T): Pair<T, Compiled>`, `class Compiled(succeeded, messages, generated) { fun call(className, method): Any?; fun generatedFile(name): String }`, `const val TEST_PACKAGES = "test.model"`.

- [ ] **Step 1: Build files**

File: `.gitignore`
```
.gradle/
build/
.kotlin/
.idea/
*.iml
```

File: `settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlin-structural-typing"

include(":structural-annotations", ":structural-processor")
```

File: `build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}
```

File: `gradle/libs.versions.toml`
```toml
[versions]
kotlin = "2.4.20"
ksp = "2.3.12"
kotlinpoet = "2.4.0"
kctfork = "0.14.0"

[libraries]
ksp-api = { module = "com.google.devtools.ksp:symbol-processing-api", version.ref = "ksp" }
kotlinpoet-ksp = { module = "com.squareup:kotlinpoet-ksp", version.ref = "kotlinpoet" }
kctfork-ksp = { module = "dev.zacsweers.kctfork:ksp", version.ref = "kctfork" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

File: `structural-annotations/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}
```

File: `structural-annotations/src/main/kotlin/dev/structural/Structural.kt`
```kotlin
package dev.structural

/**
 * Marks an interface whose shape, not its name, decides which classes a function accepts.
 *
 * The KSP processor generates overloads of functions taking this interface for every class in the
 * configured packages whose properties match it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Structural
```

File: `structural-processor/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(project(":structural-annotations"))
    testImplementation(libs.kctfork.ksp)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
```

File: `structural-processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
```
dev.structural.processor.StructuralProcessorProvider
```

Generate the wrapper with any Gradle 9.x install:

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) gradle wrapper --gradle-version 9.7.1`
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` created.

- [ ] **Step 2: Test harness and failing tests**

File: `structural-processor/src/test/kotlin/dev/structural/processor/Compilation.kt`
```kotlin
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
```

File: `structural-processor/src/test/kotlin/dev/structural/processor/ConfigTest.kt`
```kotlin
package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `parses comma-separated packages`() {
        val config = Config.parse(mapOf(Config.PACKAGES_OPTION to " a.b, c ,,"))
        assertEquals(listOf("a.b", "c"), config?.packages)
    }

    @Test
    fun `missing or blank option gives null`() {
        assertNull(Config.parse(emptyMap()))
        assertNull(Config.parse(mapOf(Config.PACKAGES_OPTION to " , ")))
    }

    @Test
    fun `includes subpackages but not name prefixes`() {
        val config = Config(listOf("a.b"))
        assertTrue(config.includesPackage("a.b"))
        assertTrue(config.includesPackage("a.b.c"))
        assertFalse(config.includesPackage("a.bc"))
        assertFalse(config.includesPackage("a"))
    }

    @Test
    fun `missing option fails the build`() {
        val compiled = compile(kotlin("A.kt", "package test\nclass A"), options = emptyMap())
        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "[structural] Missing KSP option 'structural.packages'")
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test`
Expected: compilation FAILS with unresolved references `Config`, `StructuralProcessorProvider`.

- [ ] **Step 4: Implement Config and a processor that only validates options**

File: `structural-processor/src/main/kotlin/dev/structural/processor/Config.kt`
```kotlin
package dev.structural.processor

/** Processor options. [packages] are the packages (with subpackages) whose classes are candidates. */
class Config(val packages: List<String>) {
    fun includesPackage(name: String): Boolean = packages.any { name == it || name.startsWith("$it.") }

    companion object {
        const val PACKAGES_OPTION = "structural.packages"

        fun parse(options: Map<String, String>): Config? =
            options[PACKAGES_OPTION]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.let(::Config)
    }
}
```

File: `structural-processor/src/main/kotlin/dev/structural/processor/StructuralProcessor.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

class StructuralProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = StructuralProcessor(environment)
}

class StructuralProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private var processed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        processed = true

        val config = Config.parse(environment.options)
        if (config == null) {
            logger.error(
                "[structural] Missing KSP option '${Config.PACKAGES_OPTION}': " +
                    "set it to a comma-separated list of packages to scan",
            )
            return emptyList()
        }
        return emptyList()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test`
Expected: BUILD SUCCESSFUL, 4 tests in `ConfigTest` pass.

---

### Task 2: OverloadPlanner (pure logic)

**Files:**
- Create: `structural-processor/src/main/kotlin/dev/structural/processor/OverloadPlanner.kt`
- Test: `structural-processor/src/test/kotlin/dev/structural/processor/OverloadPlannerTest.kt`

**Interfaces:**
- Produces:
  - `data class PlanCandidate(val name: String, val structuralMatches: Set<String>)`
  - `class PlanInput(declaredTypes: List<String>, structuralTypes: Set<String>, candidates: List<PlanCandidate>, supertypes: Map<String, Set<String>>, membersFirst: Boolean)`
  - `sealed interface Decision { val candidate: String }`, `data class GenerateOverload(candidate, iface: String, nominal: Boolean)`, `data class AmbiguousCandidate(candidate, ifaces: Set<String>)`
  - `object OverloadPlanner { fun plan(input: PlanInput): List<Decision> }`
- `supertypes` holds transitive proper supertypes (including `kotlin.Any`) for every candidate and declared type.

- [ ] **Step 1: Write the failing test**

File: `structural-processor/src/test/kotlin/dev/structural/processor/OverloadPlannerTest.kt`
```kotlin
package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class OverloadPlannerTest {
    private val any = "kotlin.Any"

    private fun plan(
        candidates: List<PlanCandidate>,
        supertypes: Map<String, Set<String>>,
        declared: List<String> = listOf("Sized"),
        structural: Set<String> = setOf("Sized"),
        membersFirst: Boolean = false,
    ): List<Decision> = OverloadPlanner.plan(
        PlanInput(
            declaredTypes = declared,
            structuralTypes = structural,
            candidates = candidates,
            supertypes = supertypes + mapOf("Sized" to setOf(any), "Named" to setOf(any)),
            membersFirst = membersFirst,
        ),
    )

    @Test
    fun `structural match gets a proxy overload`() {
        val decisions = plan(listOf(PlanCandidate("Rect", setOf("Sized"))), mapOf("Rect" to setOf(any)))
        assertEquals(listOf(GenerateOverload("Rect", "Sized", nominal = false)), decisions)
    }

    @Test
    fun `classes that do not match get nothing`() {
        assertEquals(emptyList(), plan(listOf(PlanCandidate("Circle", emptySet())), mapOf("Circle" to setOf(any))))
    }

    @Test
    fun `nominal implementors use the original function`() {
        assertEquals(emptyList(), plan(listOf(PlanCandidate("Impl", emptySet())), mapOf("Impl" to setOf("Sized", any))))
    }

    @Test
    fun `subclasses reuse the base class overload regardless of input order`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Mid", setOf("Sized")), PlanCandidate("Base", setOf("Sized"))),
            supertypes = mapOf("Mid" to setOf("Base", any), "Base" to setOf(any)),
        )
        assertEquals(listOf(GenerateOverload("Base", "Sized", nominal = false)), decisions)
    }

    @Test
    fun `nominal subclass of a matched class gets a direct overload`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Base", setOf("Sized")), PlanCandidate("Child", emptySet())),
            supertypes = mapOf("Base" to setOf(any), "Child" to setOf("Base", "Sized", any)),
        )
        assertEquals(
            listOf(GenerateOverload("Base", "Sized", nominal = false), GenerateOverload("Child", "Sized", nominal = true)),
            decisions,
        )
    }

    @Test
    fun `class matching several interfaces is ambiguous`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Both", setOf("Sized", "Named"))),
            supertypes = mapOf("Both" to setOf(any)),
            declared = listOf("Sized", "Named"),
            structural = setOf("Sized", "Named"),
        )
        assertEquals(listOf(AmbiguousCandidate("Both", setOf("Named", "Sized"))), decisions)
    }

    @Test
    fun `hand-written overloads are respected`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Rect", setOf("Sized")), PlanCandidate("Square", setOf("Sized"))),
            supertypes = mapOf("Rect" to setOf(any), "Square" to setOf("Rect", any)),
            declared = listOf("Sized", "Rect"),
        )
        assertEquals(emptyList(), decisions)
    }

    @Test
    fun `members win over generated extensions`() {
        val decisions = plan(
            candidates = listOf(PlanCandidate("Child", setOf("Sized")), PlanCandidate("Rect", setOf("Sized"))),
            supertypes = mapOf("Child" to setOf("Base", any), "Rect" to setOf(any), "Base" to setOf(any)),
            declared = listOf("Sized", "Base"),
            membersFirst = true,
        )
        assertEquals(listOf(GenerateOverload("Rect", "Sized", nominal = false)), decisions)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*OverloadPlannerTest'`
Expected: compilation FAILS with unresolved `OverloadPlanner`, `PlanInput`, `PlanCandidate`.

- [ ] **Step 3: Implement the planner**

File: `structural-processor/src/main/kotlin/dev/structural/processor/OverloadPlanner.kt`
```kotlin
package dev.structural.processor

/** A class from the scanned packages and the group's @Structural interfaces it matches by shape (not nominally). */
data class PlanCandidate(val name: String, val structuralMatches: Set<String>)

class PlanInput(
    /** Types of the structural parameter position in user-declared overloads of the group. */
    val declaredTypes: List<String>,
    /** The group's @Structural interfaces. */
    val structuralTypes: Set<String>,
    val candidates: List<PlanCandidate>,
    /** Transitive proper supertypes of every candidate and declared type. */
    val supertypes: Map<String, Set<String>>,
    /** True for member functions: Kotlin picks an applicable member before any extension. */
    val membersFirst: Boolean,
)

sealed interface Decision {
    val candidate: String
}

data class GenerateOverload(override val candidate: String, val iface: String, val nominal: Boolean) : Decision

data class AmbiguousCandidate(override val candidate: String, val ifaces: Set<String>) : Decision

/** Decides which overloads to generate for one function group (see "Overload resolution safety" in the proposal). */
object OverloadPlanner {
    fun plan(input: PlanInput): List<Decision> {
        fun supertypesOf(name: String) = input.supertypes[name].orEmpty()

        val planned = mutableListOf<String>()
        val decisions = mutableListOf<Decision>()
        // A subtype always has more supertypes than its supertypes, so this visits supertypes first.
        for (candidate in input.candidates.sortedBy { supertypesOf(it.name).size }) {
            if (candidate.name in input.declaredTypes) continue
            val supertypes = supertypesOf(candidate.name)
            val declaredApplicable = input.declaredTypes.filter { it in supertypes }
            if (input.membersFirst && declaredApplicable.isNotEmpty()) continue

            val applicable = (declaredApplicable + planned.filter { it in supertypes }).distinct()
            val hasMostSpecific = applicable.any { type -> applicable.all { it == type || it in supertypesOf(type) } }
            if (hasMostSpecific) continue

            val interfaces = (input.structuralTypes.filter { it in supertypes } + candidate.structuralMatches).toSortedSet()
            when (interfaces.size) {
                0 -> Unit
                1 -> {
                    val iface = interfaces.single()
                    decisions += GenerateOverload(candidate.name, iface, nominal = iface in supertypes)
                    planned += candidate.name
                }
                else -> decisions += AmbiguousCandidate(candidate.name, interfaces)
            }
        }
        return decisions
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*OverloadPlannerTest'`
Expected: 8 tests PASS.

---

### Task 3: Type helpers and InterfaceRegistry

**Files:**
- Create: `structural-processor/src/main/kotlin/dev/structural/processor/Types.kt`, `InterfaceRegistry.kt`
- Test: `structural-processor/src/test/kotlin/dev/structural/processor/InterfaceRegistryTest.kt`

**Interfaces:**
- Consumes: `resolve`, `kotlin` from Task 1.
- Produces (`Types.kt`, all `internal`): `const val STRUCTURAL_ANNOTATION`, `val KSDeclaration.fqName: String`, `fun KSType.isStructural(): Boolean`, `fun KSType.hasStructuralTypeArgument(): Boolean`, `fun KSType.key(): String` (fully qualified, e.g. `kotlin.Int?`), `fun KSType.display(): String` (simple names, e.g. `List<Sized>`), `fun KSDeclaration.effectiveVisibility(): Visibility`, `fun Visibility.isUsableFromGeneratedCode(): Boolean`, `fun KSClassDeclaration.allSupertypeNames(): Set<String>`.
- Produces: `class RequiredProperty(val name: String, val type: KSType, val mutable: Boolean)`, `class StructuralInterface(val declaration: KSClassDeclaration, val properties: List<RequiredProperty>) { val name: String }`, `class InterfaceRegistry(logger: KSPLogger) { fun get(declaration: KSClassDeclaration): StructuralInterface? }`.

- [ ] **Step 1: Write the failing test**

File: `structural-processor/src/test/kotlin/dev/structural/processor/InterfaceRegistryTest.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.getClassDeclarationByName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterfaceRegistryTest {
    private fun propertiesOf(code: String, name: String): Pair<Set<String>?, Compiled> =
        resolve(kotlin("Source.kt", code)) { resolver, logger ->
            InterfaceRegistry(logger).get(resolver.getClassDeclarationByName(name)!!)
                ?.properties
                ?.map { "${if (it.mutable) "var" else "val"} ${it.name}: ${it.type.key()}" }
                ?.toSet()
        }

    @Test
    fun `collects own and inherited abstract properties`() {
        val (properties, _) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            interface HasWidth { val width: Int }
            @Structural interface Sized : HasWidth {
                var height: Int?
                val area: Int get() = 0
            }
            """,
            "test.Sized",
        )
        assertEquals(setOf("val width: kotlin.Int", "var height: kotlin.Int?"), properties)
    }

    @Test
    fun `resolves property types inherited from generic superinterfaces`() {
        val (properties, _) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            interface HasValue<T> { val value: T }
            @Structural interface IntValue : HasValue<Int>
            """,
            "test.IntValue",
        )
        assertEquals(setOf("val value: kotlin.Int"), properties)
    }

    @Test
    fun `rejects abstract functions`() {
        val (properties, compiled) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            @Structural interface Shape {
                val width: Int
                fun area(): Int
            }
            """,
            "test.Shape",
        )
        assertNull(properties)
        assertContains(compiled.messages, "[structural] @Structural interface test.Shape must not declare abstract functions: area")
    }

    @Test
    fun `rejects classes`() {
        val (properties, compiled) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            @Structural class NotAnInterface(val width: Int)
            """,
            "test.NotAnInterface",
        )
        assertNull(properties)
        assertContains(compiled.messages, "[structural] @Structural can only be applied to interfaces: test.NotAnInterface")
    }

    @Test
    fun `rejects type parameters`() {
        val (properties, compiled) = propertiesOf(
            """
            package test
            import dev.structural.Structural
            @Structural interface Boxed<T> { val value: T }
            """,
            "test.Boxed",
        )
        assertNull(properties)
        assertContains(compiled.messages, "[structural] @Structural interface test.Boxed must not have type parameters")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*InterfaceRegistryTest'`
Expected: compilation FAILS with unresolved `InterfaceRegistry`, `key`.

- [ ] **Step 3: Implement helpers and registry**

File: `structural-processor/src/main/kotlin/dev/structural/processor/Types.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility

internal const val STRUCTURAL_ANNOTATION = "dev.structural.Structural"

internal val KSDeclaration.fqName: String
    get() = qualifiedName?.asString() ?: simpleName.asString()

internal fun KSType.isStructural(): Boolean {
    val declaration = declaration as? KSClassDeclaration ?: return false
    return declaration.annotations.any {
        it.shortName.asString() == "Structural" &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == STRUCTURAL_ANNOTATION
    }
}

internal fun KSType.hasStructuralTypeArgument(): Boolean = arguments.any { argument ->
    val type = argument.type?.resolve() ?: return@any false
    type.isStructural() || type.hasStructuralTypeArgument()
}

/** Fully qualified rendering used to compare parameter types. */
internal fun KSType.key(): String = buildString {
    append(declaration.fqName)
    if (arguments.isNotEmpty()) {
        append(arguments.joinToString(",", "<", ">") { "${it.variance.name} ${it.type?.resolve()?.key() ?: "*"}" })
    }
    if (isMarkedNullable) append("?")
}

/** Short rendering used in messages. */
internal fun KSType.display(): String = buildString {
    append(declaration.simpleName.asString())
    if (arguments.isNotEmpty()) {
        append(arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.display() ?: "*" })
    }
    if (isMarkedNullable) append("?")
}

/** PUBLIC or INTERNAL when every enclosing declaration allows it, otherwise the first restricting visibility. */
internal fun KSDeclaration.effectiveVisibility(): Visibility {
    var result = Visibility.PUBLIC
    var current: KSDeclaration? = this
    while (current != null) {
        when (val visibility = current.getVisibility()) {
            Visibility.PUBLIC -> Unit
            Visibility.INTERNAL -> result = Visibility.INTERNAL
            else -> return visibility
        }
        current = current.parentDeclaration
    }
    return result
}

internal fun Visibility.isUsableFromGeneratedCode(): Boolean = this == Visibility.PUBLIC || this == Visibility.INTERNAL

internal fun KSClassDeclaration.allSupertypeNames(): Set<String> =
    getAllSuperTypes().map { it.declaration.fqName }.toSet() + "kotlin.Any"
```

File: `structural-processor/src/main/kotlin/dev/structural/processor/InterfaceRegistry.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

class RequiredProperty(val name: String, val type: KSType, val mutable: Boolean)

class StructuralInterface(val declaration: KSClassDeclaration, val properties: List<RequiredProperty>) {
    val name: String get() = declaration.fqName
}

/** Validates @Structural interfaces once and caches the result (null for invalid ones). */
class InterfaceRegistry(private val logger: KSPLogger) {
    private val cache = mutableMapOf<String, StructuralInterface?>()

    fun get(declaration: KSClassDeclaration): StructuralInterface? {
        val name = declaration.fqName
        if (name !in cache) cache[name] = build(declaration)
        return cache[name]
    }

    private fun build(declaration: KSClassDeclaration): StructuralInterface? {
        val name = declaration.fqName
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error("[structural] @Structural can only be applied to interfaces: $name", declaration)
            return null
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error("[structural] @Structural interface $name must not have type parameters", declaration)
            return null
        }
        val abstractFunctions = declaration.getAllFunctions().filter { it.isAbstract }.map { it.simpleName.asString() }.toList()
        if (abstractFunctions.isNotEmpty()) {
            logger.error(
                "[structural] @Structural interface $name must not declare abstract functions: ${abstractFunctions.joinToString()}",
                declaration,
            )
            return null
        }
        val self = declaration.asStarProjectedType()
        val properties = declaration.getAllProperties()
            .filter { it.isAbstract() }
            .map { RequiredProperty(it.simpleName.asString(), it.asMemberOf(self), it.isMutable) }
            .toList()
        return StructuralInterface(declaration, properties)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*InterfaceRegistryTest'`
Expected: 5 tests PASS.

---

### Task 4: Matcher and Candidates

**Files:**
- Create: `structural-processor/src/main/kotlin/dev/structural/processor/Matcher.kt`, `Candidates.kt`
- Test: `structural-processor/src/test/kotlin/dev/structural/processor/MatcherTest.kt`, `CandidatesTest.kt`

**Interfaces:**
- Consumes: `InterfaceRegistry`, `StructuralInterface`, `RequiredProperty`, `Types.kt` helpers (Task 3); `Config` (Task 1).
- Produces: `object Matcher { fun implementsNominally(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean; fun matchesStructurally(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean }`, `object Candidates { fun find(files: List<KSFile>, config: Config): List<KSClassDeclaration> }`.

- [ ] **Step 1: Write the failing tests**

File: `structural-processor/src/test/kotlin/dev/structural/processor/MatcherTest.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.getClassDeclarationByName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatcherTest {
    private val interfaces = """
        package test
        import dev.structural.Structural
        @Structural interface Sized { val width: Number; val height: Int }
        @Structural interface Resizable { var width: Int }
        @Structural interface Labeled { val label: String? }
    """

    private fun matches(classes: String, candidate: String, iface: String): Boolean =
        resolve(kotlin("Source.kt", interfaces + classes)) { resolver, logger ->
            Matcher.matchesStructurally(
                resolver.getClassDeclarationByName("test.$candidate")!!,
                InterfaceRegistry(logger).get(resolver.getClassDeclarationByName("test.$iface")!!)!!,
            )
        }.first

    @Test
    fun `val accepts a subtype`() {
        assertTrue(matches("class Rect(val width: Int, val height: Int, val color: String)", "Rect", "Sized"))
    }

    @Test
    fun `missing property does not match`() {
        assertFalse(matches("class Flat(val width: Int)", "Flat", "Sized"))
    }

    @Test
    fun `private property does not match`() {
        assertFalse(matches("class Hidden(private val width: Int, val height: Int)", "Hidden", "Sized"))
    }

    @Test
    fun `inherited property from generic superclass matches with substituted type`() {
        val classes = """
            open class Base<T>(val width: T)
            class Derived(val height: Int) : Base<Double>(1.0)
        """
        assertTrue(matches(classes, "Derived", "Sized"))
    }

    @Test
    fun `nominal implementors are not structural matches`() {
        assertFalse(matches("class Nominal(override val width: Int, override val height: Int) : Sized", "Nominal", "Sized"))
    }

    @Test
    fun `var requires a mutable property of exactly the same type`() {
        assertTrue(matches("class Box(var width: Int)", "Box", "Resizable"))
        assertFalse(matches("class Frozen(val width: Int)", "Frozen", "Resizable"))
        assertFalse(matches("class Wide(var width: Long)", "Wide", "Resizable"))
    }

    @Test
    fun `var with private setter does not match`() {
        assertFalse(matches("class Guarded { var width: Int = 0\n private set }", "Guarded", "Resizable"))
    }

    @Test
    fun `non-null type satisfies nullable val`() {
        assertTrue(matches("class Tag(val label: String)", "Tag", "Labeled"))
        assertTrue(matches("class MaybeTag(val label: String?)", "MaybeTag", "Labeled"))
        assertFalse(matches("class NumberTag(val label: Int)", "NumberTag", "Labeled"))
    }
}
```

File: `structural-processor/src/test/kotlin/dev/structural/processor/CandidatesTest.kt`
```kotlin
package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidatesTest {
    @Test
    fun `finds visible non-generic classes in configured packages and subpackages`() {
        val model = kotlin(
            "Model.kt",
            """
            package test.model
            class Plain
            internal class Internal
            private class Private
            class Generic<T>
            annotation class Marker
            enum class Color { RED }
            object Singleton
            class Outer {
                class Nested
                inner class Inner
                private class Hidden
                companion object
            }
            """,
        )
        val other = kotlin("Other.kt", "package test.other\nclass Elsewhere")
        val sub = kotlin("Sub.kt", "package test.model.sub\nclass Deeper")

        val (names, _) = resolve(model, other, sub) { resolver, _ ->
            Candidates.find(resolver.getAllFiles().toList(), Config(listOf("test.model"))).map { it.fqName }.toSet()
        }

        assertEquals(
            setOf(
                "test.model.Plain",
                "test.model.Internal",
                "test.model.Color",
                "test.model.Singleton",
                "test.model.Outer",
                "test.model.Outer.Nested",
                "test.model.sub.Deeper",
            ),
            names,
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*MatcherTest' --tests '*CandidatesTest'`
Expected: compilation FAILS with unresolved `Matcher`, `Candidates`.

- [ ] **Step 3: Implement**

File: `structural-processor/src/main/kotlin/dev/structural/processor/Matcher.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** Decides whether a class has the shape of a @Structural interface, using Kotlin override rules. */
object Matcher {
    fun implementsNominally(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean =
        iface.declaration.asStarProjectedType().isAssignableFrom(candidate.asStarProjectedType())

    fun matchesStructurally(candidate: KSClassDeclaration, iface: StructuralInterface): Boolean {
        if (implementsNominally(candidate, iface)) return false
        val candidateType = candidate.asStarProjectedType()
        val properties = candidate.getAllProperties().associateBy { it.simpleName.asString() }
        return iface.properties.all { required ->
            val property = properties[required.name] ?: return false
            satisfies(property, candidateType, required)
        }
    }

    private fun satisfies(property: KSPropertyDeclaration, owner: KSType, required: RequiredProperty): Boolean {
        if (property.extensionReceiver != null) return false
        if (!property.getVisibility().isUsableFromGeneratedCode()) return false
        val actual = property.asMemberOf(owner)
        if (!required.mutable) return required.type.isAssignableFrom(actual)

        if (!property.isMutable) return false
        val setterModifiers = property.setter?.modifiers.orEmpty()
        if (Modifier.PRIVATE in setterModifiers || Modifier.PROTECTED in setterModifiers) return false
        return required.type.isAssignableFrom(actual) && actual.isAssignableFrom(required.type)
    }
}
```

File: `structural-processor/src/main/kotlin/dev/structural/processor/Candidates.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.Modifier

/** Classes from the configured packages that generated overloads may accept. */
object Candidates {
    fun find(files: List<KSFile>, config: Config): List<KSClassDeclaration> =
        files
            .filter { config.includesPackage(it.packageName.asString()) }
            .flatMap { file -> file.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested) }
            .filter(::isCandidate)

    private fun withNested(declaration: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)

    private fun isCandidate(declaration: KSClassDeclaration): Boolean =
        declaration.classKind != ClassKind.ANNOTATION_CLASS &&
            declaration.classKind != ClassKind.ENUM_ENTRY &&
            !declaration.isCompanionObject &&
            Modifier.INNER !in declaration.modifiers &&
            declaration.typeParameters.isEmpty() &&
            declaration.effectiveVisibility().isUsableFromGeneratedCode()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*MatcherTest' --tests '*CandidatesTest'`
Expected: 9 tests PASS.

---

### Task 5: FunctionCollector (supported, unsupported, groups)

**Files:**
- Create: `structural-processor/src/main/kotlin/dev/structural/processor/FunctionCollector.kt`
- Modify (full replacement): `structural-processor/src/main/kotlin/dev/structural/processor/StructuralProcessor.kt`
- Test: `structural-processor/src/test/kotlin/dev/structural/processor/UnsupportedFunctionsTest.kt`, `FunctionGroupsTest.kt`

**Interfaces:**
- Consumes: `InterfaceRegistry`, `Types.kt` helpers (Task 3).
- Produces: `class StructuralFunction(val declaration: KSFunctionDeclaration, val slot: Int, val iface: StructuralInterface) { val owner: KSClassDeclaration? }`, `class FunctionGroup(val memberScope: Boolean, val functions: List<StructuralFunction>, val declaredTypes: List<KSClassDeclaration>) { val name: String }`, `class FunctionCollector(interfaces: InterfaceRegistry, logger: KSPLogger) { fun collect(files: List<KSFile>): List<FunctionGroup> }`.

- [ ] **Step 1: Write the failing tests**

File: `structural-processor/src/test/kotlin/dev/structural/processor/UnsupportedFunctionsTest.kt`
```kotlin
package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class UnsupportedFunctionsTest {
    private fun messagesFor(code: String): String = compile(
        kotlin(
            "Functions.kt",
            """
            package test
            import dev.structural.Structural
            @Structural interface Sized { val width: Int }
            """ + code,
        ),
    ).messages

    private fun assertSkipped(code: String, expected: String) {
        assertContains(messagesFor(code), "[structural] Skipping $expected")
    }

    @Test
    fun `several structural parameters`() =
        assertSkipped("fun fit(a: Sized, b: Sized) = 0", "test.fit(a: Sized, b: Sized): more than one @Structural parameter")

    @Test
    fun `nullable structural parameter`() =
        assertSkipped("fun size(target: Sized?) = 0", "test.size(target: Sized?): nullable @Structural parameter")

    @Test
    fun `structural extension receiver`() =
        assertSkipped("fun Sized.area() = 0", "test.area(): a @Structural interface as extension receiver")

    @Test
    fun `structural type nested in a collection`() =
        assertSkipped("fun total(items: List<Sized>) = 0", "test.total(items: List<Sized>): a @Structural interface nested in a parameter type")

    @Test
    fun `structural type nested in a lambda`() =
        assertContains(messagesFor("fun each(action: (Sized) -> Unit) = 0"), "): a @Structural interface nested in a parameter type")

    @Test
    fun `default values`() =
        assertSkipped("fun scaled(target: Sized, scale: Int = 1) = 0", "test.scaled(target: Sized, scale: Int): parameters with default values")

    @Test
    fun `vararg parameters`() =
        assertSkipped("fun sizes(vararg targets: Sized) = 0", "test.sizes(targets: Sized): vararg parameters")

    @Test
    fun `type parameters`() =
        assertSkipped("fun <T> tagged(target: Sized, tag: T) = 0", "test.tagged(target: Sized, tag: T): type parameters")

    @Test
    fun `inline functions`() =
        assertSkipped("inline fun measured(target: Sized) = 0", "test.measured(target: Sized): inline function")

    @Test
    fun `private functions`() =
        assertSkipped("private fun hidden(target: Sized) = 0", "test.hidden(target: Sized): private or protected visibility")

    @Test
    fun `companion object members`() =
        assertSkipped(
            "class Owner { companion object { fun size(target: Sized) = 0 } }",
            "test.Owner.Companion.size(target: Sized): companion object member",
        )

    @Test
    fun `members of generic classes`() =
        assertSkipped("class Box<T> { fun put(target: Sized) = 0 }", "test.Box.put(target: Sized): member of a generic or inner class")

    @Test
    fun `supported functions and return types produce no warnings`() {
        val messages = messagesFor(
            """
            fun size(target: Sized) = 0
            class Owner { fun area(target: Sized) = 0 }
            fun String.draw(target: Sized) = 0
            suspend fun later(target: Sized) = 0
            fun make(): Sized = TODO()
            """,
        )
        assertFalse("[structural]" in messages, messages)
    }
}
```

File: `structural-processor/src/test/kotlin/dev/structural/processor/FunctionGroupsTest.kt`
```kotlin
package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionGroupsTest {
    @Test
    fun `groups overloads that differ only in the structural parameter`() {
        val source = kotlin(
            "Functions.kt",
            """
            package test
            import dev.structural.Structural
            @Structural interface Sized { val width: Int }
            @Structural interface Named { val name: String }
            open class Base
            fun describe(target: Sized, prefix: String) = ""
            fun describe(target: Named, prefix: String) = ""
            fun describe(target: Base, prefix: String) = ""
            fun describe(target: Sized, prefix: Int) = ""
            class Owner { fun describe(target: Sized, prefix: String) = "" }
            """,
        )

        val (groups, _) = resolve(source) { resolver, logger ->
            FunctionCollector(InterfaceRegistry(logger), logger).collect(resolver.getAllFiles().toList()).map { group ->
                val scope = if (group.memberScope) "member" else "top"
                val interfaces = group.functions.map { it.iface.name }.sorted()
                val declared = group.declaredTypes.map { it.fqName }.sorted()
                "$scope ${group.name}: $interfaces declared $declared"
            }.sorted()
        }

        assertEquals(
            listOf(
                "member test.Owner.describe: [test.Sized] declared [test.Sized]",
                "top test.describe: [test.Named, test.Sized] declared [test.Base, test.Named, test.Sized]",
                "top test.describe: [test.Sized] declared [test.Sized]",
            ),
            groups,
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*UnsupportedFunctionsTest' --tests '*FunctionGroupsTest'`
Expected: compilation FAILS with unresolved `FunctionCollector`.

- [ ] **Step 3: Implement the collector and call it from the processor**

File: `structural-processor/src/main/kotlin/dev/structural/processor/FunctionCollector.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** A supported function whose parameter at [slot] is the @Structural interface [iface]. */
class StructuralFunction(val declaration: KSFunctionDeclaration, val slot: Int, val iface: StructuralInterface) {
    val owner: KSClassDeclaration? get() = declaration.parentDeclaration as? KSClassDeclaration
}

/** Supported functions that differ only in the structural parameter's type, plus all declared types at that position. */
class FunctionGroup(
    val memberScope: Boolean,
    val functions: List<StructuralFunction>,
    val declaredTypes: List<KSClassDeclaration>,
) {
    val name: String get() = functions.first().declaration.fqName
}

class FunctionCollector(private val interfaces: InterfaceRegistry, private val logger: KSPLogger) {
    private data class GroupKey(val scope: String, val slot: Int, val shape: List<String>)

    fun collect(files: List<KSFile>): List<FunctionGroup> {
        val scopes = mutableMapOf<String, MutableList<KSFunctionDeclaration>>()
        val supported = mutableListOf<StructuralFunction>()
        files.forEach { walk(it.declarations, scopes, supported) }

        return supported
            .groupBy { GroupKey(scopeOf(it.declaration), it.slot, shapeOf(it.declaration, it.slot)) }
            .map { (key, functions) ->
                val declared = scopes.getValue(key.scope)
                    .filter { it.parameters.size > key.slot && shapeOf(it, key.slot) == key.shape }
                    .mapNotNull { it.parameters[key.slot].type.resolve().declaration as? KSClassDeclaration }
                FunctionGroup(memberScope = functions.first().owner != null, functions = functions, declaredTypes = declared)
            }
    }

    private fun walk(
        declarations: Sequence<KSDeclaration>,
        scopes: MutableMap<String, MutableList<KSFunctionDeclaration>>,
        supported: MutableList<StructuralFunction>,
    ) {
        for (declaration in declarations) {
            when (declaration) {
                is KSClassDeclaration -> walk(declaration.declarations, scopes, supported)
                is KSFunctionDeclaration -> if (!declaration.isConstructor()) {
                    scopes.getOrPut(scopeOf(declaration)) { mutableListOf() } += declaration
                    inspect(declaration)?.let(supported::add)
                }
                else -> Unit
            }
        }
    }

    private fun inspect(function: KSFunctionDeclaration): StructuralFunction? {
        val receiver = function.extensionReceiver?.resolve()
        val parameterTypes = function.parameters.map { it.type.resolve() }
        val mentionsStructural = (listOfNotNull(receiver) + parameterTypes).any { it.isStructural() || it.hasStructuralTypeArgument() }
        if (!mentionsStructural) return null

        val reason = unsupportedReason(function, receiver, parameterTypes)
        if (reason != null) {
            logger.warn("[structural] Skipping ${function.signature()}: $reason", function)
            return null
        }
        val slot = parameterTypes.indexOfFirst { it.isStructural() }
        val iface = interfaces.get(parameterTypes[slot].declaration as KSClassDeclaration) ?: return null
        return StructuralFunction(function, slot, iface)
    }

    private fun unsupportedReason(function: KSFunctionDeclaration, receiver: KSType?, parameterTypes: List<KSType>): String? {
        val owner = function.parentDeclaration as? KSClassDeclaration
        val structuralSlots = parameterTypes.indices.filter { parameterTypes[it].isStructural() }
        return when {
            receiver?.isStructural() == true -> "a @Structural interface as extension receiver"
            (listOfNotNull(receiver) + parameterTypes).any { it.hasStructuralTypeArgument() } ->
                "a @Structural interface nested in a parameter type"
            structuralSlots.size > 1 -> "more than one @Structural parameter"
            parameterTypes[structuralSlots.single()].isMarkedNullable -> "nullable @Structural parameter"
            function.parameters.any { it.isVararg } -> "vararg parameters"
            function.parameters.withIndex().any { (index, parameter) -> index !in structuralSlots && parameter.hasDefault } ->
                "parameters with default values"
            function.typeParameters.isNotEmpty() -> "type parameters"
            Modifier.INLINE in function.modifiers -> "inline function"
            function.isExpect || function.isActual -> "expect/actual function"
            owner?.isCompanionObject == true -> "companion object member"
            owner != null && (owner.typeParameters.isNotEmpty() || Modifier.INNER in owner.modifiers) ->
                "member of a generic or inner class"
            !function.effectiveVisibility().isUsableFromGeneratedCode() -> "private or protected visibility"
            else -> null
        }
    }

    private fun scopeOf(function: KSFunctionDeclaration): String =
        (function.parentDeclaration as? KSClassDeclaration)?.let { "member ${it.fqName}" }
            ?: "package ${function.packageName.asString()}"

    /** Name, receiver and parameter types, with the structural position blanked out. */
    private fun shapeOf(function: KSFunctionDeclaration, slot: Int): List<String> =
        listOf(function.simpleName.asString(), function.extensionReceiver?.resolve()?.key() ?: "-") +
            function.parameters.mapIndexed { index, parameter -> if (index == slot) "_" else parameter.type.resolve().key() }

    private fun KSFunctionDeclaration.signature(): String =
        "$fqName(${parameters.joinToString { "${it.name?.asString()}: ${it.type.resolve().display()}" }})"
}
```

File: `structural-processor/src/main/kotlin/dev/structural/processor/StructuralProcessor.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

class StructuralProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = StructuralProcessor(environment)
}

class StructuralProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private var processed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        processed = true

        val config = Config.parse(environment.options)
        if (config == null) {
            logger.error(
                "[structural] Missing KSP option '${Config.PACKAGES_OPTION}': " +
                    "set it to a comma-separated list of packages to scan",
            )
            return emptyList()
        }

        val interfaces = InterfaceRegistry(logger)
        resolver.getSymbolsWithAnnotation(STRUCTURAL_ANNOTATION).filterIsInstance<KSClassDeclaration>().forEach { interfaces.get(it) }
        FunctionCollector(interfaces, logger).collect(resolver.getAllFiles().toList())
        return emptyList()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*UnsupportedFunctionsTest' --tests '*FunctionGroupsTest'`
Expected: 14 tests PASS.

---

### Task 6: Generator and full processor

**Files:**
- Create: `structural-processor/src/main/kotlin/dev/structural/processor/Generator.kt`
- Modify (full replacement): `structural-processor/src/main/kotlin/dev/structural/processor/StructuralProcessor.kt`
- Test: `structural-processor/src/test/kotlin/dev/structural/processor/GenerationTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: `class Generator(codeGenerator: CodeGenerator, dependencies: Dependencies) { fun overload(function: StructuralFunction, candidate: KSClassDeclaration, nominal: Boolean); fun write() }`.

- [ ] **Step 1: Write the failing test**

File: `structural-processor/src/test/kotlin/dev/structural/processor/GenerationTest.kt`
```kotlin
package dev.structural.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerationTest {
    private val sized = """
        package test
        import dev.structural.Structural
        import test.model.*
        import kotlin.coroutines.*
        @Structural interface Sized { val width: Int; val height: Int }
    """

    private fun compileWith(model: String, code: String): Compiled =
        compile(kotlin("Model.kt", "package test.model\n$model"), kotlin("Geometry.kt", sized + code))

    private fun Compiled.runMain(): Any? {
        assertTrue(succeeded, messages)
        return call("test.GeometryKt", "run")
    }

    @Test
    fun `original example`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int, val color: String)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Rectangular(1, 2, "red"))
            """,
        )
        assertEquals(2, compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "public fun size(target: Rectangular): Int")
        assertContains(compiled.generatedFile("Rectangular_AsSized.kt"), "internal class Rectangular_AsSized(")
    }

    @Test
    fun `member function gets an extension overload`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            class Canvas { fun area(target: Sized) = target.width * target.height }
            fun run() = Canvas().area(Rectangular(2, 3))
            """,
        )
        assertEquals(6, compiled.runMain())
        assertContains(compiled.generatedFile("Canvas_Structural.kt"), "public fun Canvas.area(target: Rectangular): Int")
    }

    @Test
    fun `extension function with other parameters`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun String.describe(target: Sized, suffix: String) = this + (target.width * target.height) + suffix
            fun run() = "area=".describe(Rectangular(2, 2), "!")
            """,
        )
        assertEquals("area=4!", compiled.runMain())
    }

    @Test
    fun `suspend function`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            suspend fun measure(target: Sized) = target.width
            fun run(): Int {
                var result = 0
                val block: suspend () -> Int = { measure(Rectangular(7, 1)) }
                block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
                return result
            }
            """,
        )
        assertEquals(7, compiled.runMain())
    }

    @Test
    fun `var properties write through`() {
        val compiled = compile(
            kotlin("Model.kt", "package test.model\nclass Counter(var count: Int)"),
            kotlin(
                "Geometry.kt",
                """
                package test
                import dev.structural.Structural
                import test.model.Counter
                @Structural interface Counting { var count: Int }
                fun increment(target: Counting) { target.count++ }
                fun run(): Int {
                    val counter = Counter(1)
                    increment(counter)
                    return counter.count
                }
                """,
            ),
        )
        assertEquals(2, compiled.runMain())
    }

    @Test
    fun `base class overload covers subclasses and nominal subclass gets a direct overload`() {
        val compiled = compileWith(
            """
            open class Base(val width: Int, val height: Int)
            class Mid : Base(1, 2)
            class Child : Base(3, 4), test.Sized
            """,
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Base(1, 1)) + size(Mid()) * 10 + size(Child()) * 100
            """,
        )
        assertEquals(1221, compiled.runMain())
        val overloads = compiled.generatedFile("Geometry_Structural.kt")
        assertContains(overloads, "fun size(target: Base)")
        assertContains(overloads, "fun size(target: Child)")
        assertFalse("target: Mid" in overloads, overloads)
        assertContains(overloads, "val structuralArgument: Sized = target\n")
    }

    @Test
    fun `class matching several interfaces is reported and skipped`() {
        val compiled = compileWith(
            """
            class Labeled(val width: Int, val height: Int, val name: String)
            class Person(val name: String)
            """,
            """
            @Structural interface Named { val name: String }
            fun describe(target: Sized) = "sized"
            fun describe(target: Named) = "named"
            fun run() = describe(Person("x"))
            """,
        )
        assertEquals("named", compiled.runMain())
        assertContains(
            compiled.messages,
            "[structural] Not generating test.describe for test.model.Labeled: it matches several @Structural interfaces (test.Named, test.Sized)",
        )
    }

    @Test
    fun `internal candidates get internal overloads`() {
        val compiled = compileWith(
            "internal class Secret(val width: Int, val height: Int)",
            """
            fun size(target: Sized) = target.width * target.height
            fun run() = size(Secret(2, 5))
            """,
        )
        assertEquals(10, compiled.runMain())
        assertContains(compiled.generatedFile("Geometry_Structural.kt"), "internal fun size(target: Secret): Int")
    }

    @Test
    fun `hand-written overload is kept`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun size(target: Sized) = target.width * target.height
            fun size(target: Rectangular) = -1
            fun run() = size(Rectangular(2, 2))
            """,
        )
        assertEquals(-1, compiled.runMain())
    }

    @Test
    fun `proxies compare and print through their target`() {
        val compiled = compileWith(
            "data class Point(val width: Int, val height: Int)",
            """
            fun identity(target: Sized): Sized = target
            fun run() = listOf(identity(Point(1, 2)) == identity(Point(1, 2)), identity(Point(1, 2)).toString())
            """,
        )
        assertEquals(listOf(true, "Point(width=1, height=2)"), compiled.runMain())
    }

    @Test
    fun `calling an unsupported function still fails to compile`() {
        val compiled = compileWith(
            "class Rectangular(val width: Int, val height: Int)",
            """
            fun fit(a: Sized, b: Sized) = 0
            fun run() = fit(Rectangular(1, 2), Rectangular(1, 2))
            """,
        )
        assertFalse(compiled.succeeded)
        assertContains(compiled.messages, "[structural] Skipping test.fit")
        assertContains(compiled.messages, "mismatch", ignoreCase = true)
    }

    @Test
    fun `generated names avoid clashes with parameters and properties`() {
        val compiled = compileWith(
            """
            class Rectangular(val width: Int, val height: Int)
            class Arrow(val target: String)
            """,
            """
            @Structural interface Aimed { val target: String }
            fun pick(target: Sized, structuralArgument: Int) = target.width + structuralArgument
            fun aim(aimed: Aimed) = aimed.target
            fun run() = pick(Rectangular(1, 2), 10).toString() + aim(Arrow("x"))
            """,
        )
        assertEquals("11x", compiled.runMain())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test --tests '*GenerationTest'`
Expected: tests FAIL (no files generated; calls fail with type mismatch).

- [ ] **Step 3: Implement the generator and wire the processor**

File: `structural-processor/src/main/kotlin/dev/structural/processor/Generator.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** Collects generated overloads and proxies into files, then writes them all at once. */
class Generator(private val codeGenerator: CodeGenerator, private val dependencies: Dependencies) {
    private val files = linkedMapOf<Pair<String, String>, FileSpec.Builder>()
    private val proxies = mutableMapOf<Pair<String, String>, ClassName>()
    private val proxyNames = mutableSetOf<ClassName>()

    fun overload(function: StructuralFunction, candidate: KSClassDeclaration, nominal: Boolean) {
        val declaration = function.declaration
        val owner = function.owner
        val name = declaration.simpleName.asString()
        val parameterNames = declaration.parameters.map { it.name!!.asString() }
        val structuralName = parameterNames[function.slot]
        val argumentName = uniqueName("structuralArgument", parameterNames)
        val receiver = owner?.toClassName() ?: declaration.extensionReceiver?.toTypeName()
        val internal = declaration.effectiveVisibility() == Visibility.INTERNAL ||
            candidate.effectiveVisibility() == Visibility.INTERNAL

        val builder = FunSpec.builder(name)
            .addModifiers(if (internal) KModifier.INTERNAL else KModifier.PUBLIC)
            .returns(declaration.returnType!!.toTypeName())
        if (Modifier.SUSPEND in declaration.modifiers) builder.addModifiers(KModifier.SUSPEND)
        receiver?.let { builder.receiver(it) }
        declaration.parameters.forEachIndexed { index, parameter ->
            val type = if (index == function.slot) candidate.toClassName() else parameter.type.toTypeName()
            builder.addParameter(parameterNames[index], type)
        }

        val ifaceType = function.iface.declaration.toClassName()
        if (nominal) {
            builder.addStatement("val %N: %T = %N", argumentName, ifaceType, structuralName)
        } else {
            builder.addStatement("val %N: %T = %T(%N)", argumentName, ifaceType, proxyFor(candidate, function.iface), structuralName)
        }
        val arguments = parameterNames
            .map { CodeBlock.of("%N = %N", it, if (it == structuralName) argumentName else it) }
            .joinToCode()
        builder.addStatement(if (receiver != null) "return this.%N(%L)" else "return %N(%L)", name, arguments)

        val packageName = (owner ?: declaration).packageName.asString()
        val fileName = owner?.toClassName()?.simpleNames?.joinToString("_")
            ?: declaration.containingFile!!.fileName.removeSuffix(".kt")
        file(packageName, "${fileName}_Structural").addFunction(builder.build())
    }

    fun write() {
        files.values.forEach { it.build().writeTo(codeGenerator, dependencies) }
    }

    private fun proxyFor(candidate: KSClassDeclaration, iface: StructuralInterface): ClassName =
        proxies.getOrPut(candidate.fqName to iface.name) {
            val packageName = candidate.packageName.asString()
            val baseName = candidate.toClassName().simpleNames.joinToString("_") + "_As" + iface.declaration.simpleName.asString()
            var className = ClassName(packageName, baseName)
            if (className in proxyNames) className = ClassName(packageName, "${baseName}_${Integer.toHexString(iface.name.hashCode())}")
            proxyNames += className
            file(packageName, className.simpleName).addType(proxyType(className, candidate, iface))
            className
        }

    private fun proxyType(className: ClassName, candidate: KSClassDeclaration, iface: StructuralInterface): TypeSpec {
        val targetType = candidate.toClassName()
        val target = uniqueName("target", iface.properties.map { it.name })
        return TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(iface.declaration.toClassName())
            .primaryConstructor(FunSpec.constructorBuilder().addParameter(target, targetType).build())
            .addProperty(PropertySpec.builder(target, targetType).initializer("%N", target).build())
            .addProperties(iface.properties.map { delegatingProperty(it, target) })
            .addFunction(
                FunSpec.builder("equals")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("other", ANY.copy(nullable = true))
                    .returns(BOOLEAN)
                    .addStatement("return other is %T && other.%N == %N", className, target, target)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT)
                    .addStatement("return %N.hashCode()", target).build(),
            )
            .addFunction(
                FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
                    .addStatement("return %N.toString()", target).build(),
            )
            .build()
    }

    private fun delegatingProperty(property: RequiredProperty, target: String): PropertySpec {
        val type = property.type.toTypeName()
        val builder = PropertySpec.builder(property.name, type, KModifier.OVERRIDE)
            .mutable(property.mutable)
            .getter(FunSpec.getterBuilder().addStatement("return %N.%N", target, property.name).build())
        if (property.mutable) {
            builder.setter(
                FunSpec.setterBuilder().addParameter("value", type).addStatement("%N.%N = value", target, property.name).build(),
            )
        }
        return builder.build()
    }

    private fun file(packageName: String, name: String): FileSpec.Builder =
        files.getOrPut(packageName to name) { FileSpec.builder(packageName, name) }
}

private fun uniqueName(base: String, taken: Collection<String>): String =
    generateSequence(base) { "${it}_" }.first { it !in taken }
```

File: `structural-processor/src/main/kotlin/dev/structural/processor/StructuralProcessor.kt`
```kotlin
package dev.structural.processor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

class StructuralProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = StructuralProcessor(environment)
}

class StructuralProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private var processed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (processed) return emptyList()
        processed = true

        val config = Config.parse(environment.options)
        if (config == null) {
            logger.error(
                "[structural] Missing KSP option '${Config.PACKAGES_OPTION}': " +
                    "set it to a comma-separated list of packages to scan",
            )
            return emptyList()
        }

        val interfaces = InterfaceRegistry(logger)
        resolver.getSymbolsWithAnnotation(STRUCTURAL_ANNOTATION).filterIsInstance<KSClassDeclaration>().forEach { interfaces.get(it) }

        val files = resolver.getAllFiles().toList()
        val groups = FunctionCollector(interfaces, logger).collect(files)
        val candidates = Candidates.find(files, config)
        val generator = Generator(environment.codeGenerator, Dependencies(aggregating = true, *files.toTypedArray()))
        groups.forEach { plan(it, candidates, generator) }
        generator.write()
        return emptyList()
    }

    private fun plan(group: FunctionGroup, candidates: List<KSClassDeclaration>, generator: Generator) {
        val functions = group.functions.associateBy { it.iface.name }
        val interfaces = group.functions.map { it.iface }
        val classes = (candidates + group.declaredTypes).associateBy { it.fqName }
        val input = PlanInput(
            declaredTypes = group.declaredTypes.map { it.fqName },
            structuralTypes = functions.keys,
            candidates = candidates.map { candidate ->
                val matches = interfaces.filter { Matcher.matchesStructurally(candidate, it) }.map { it.name }.toSet()
                PlanCandidate(candidate.fqName, matches)
            },
            supertypes = classes.mapValues { it.value.allSupertypeNames() },
            membersFirst = group.memberScope,
        )
        for (decision in OverloadPlanner.plan(input)) {
            when (decision) {
                is GenerateOverload ->
                    generator.overload(functions.getValue(decision.iface), classes.getValue(decision.candidate), decision.nominal)
                is AmbiguousCandidate -> logger.warn(
                    "[structural] Not generating ${group.name} for ${decision.candidate}: " +
                        "it matches several @Structural interfaces (${decision.ifaces.joinToString()})",
                    classes[decision.candidate],
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run all processor tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :structural-processor:test`
Expected: all tests PASS (Tasks 1–6).

---

### Task 7: Sample module

**Files:**
- Modify: `settings.gradle.kts` (include `:sample`)
- Create: `sample/build.gradle.kts`, `sample/src/main/kotlin/com/example/geometry/Shapes.kt`, `sample/src/main/kotlin/com/example/model/Model.kt`
- Test: `sample/src/test/kotlin/com/example/SampleTest.kt`

**Interfaces:**
- Consumes: `:structural-annotations`, `:structural-processor` via `ksp(project(...))`.

- [ ] **Step 1: Module and failing tests**

In `settings.gradle.kts`, replace the include line with:
```kotlin
include(":structural-annotations", ":structural-processor", ":sample")
```

File: `sample/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":structural-annotations"))
    ksp(project(":structural-processor"))
    testImplementation(kotlin("test"))
}

ksp {
    arg("structural.packages", "com.example.model")
}

tasks.test {
    useJUnitPlatform()
}
```

File: `sample/src/main/kotlin/com/example/geometry/Shapes.kt`
```kotlin
package com.example.geometry

import dev.structural.Structural

@Structural
interface Sized {
    val width: Int
    val height: Int
}

@Structural
interface Measurable {
    val length: Number
}

@Structural
interface Counter {
    var count: Int
}

fun size(target: Sized) = target.width * target.height

fun describe(target: Measurable, unit: String) = "${target.length} $unit"

fun increment(target: Counter) {
    target.count++
}

suspend fun sizeLater(target: Sized) = size(target)

fun String.label(target: Sized) = "$this ${target.width}x${target.height}"

class Canvas(val name: String) {
    fun fits(target: Sized) = target.width <= 100 && target.height <= 100
}
```

File: `sample/src/main/kotlin/com/example/model/Model.kt`
```kotlin
package com.example.model

import com.example.geometry.Sized

class Rectangular(val width: Int, val height: Int, val color: String)

class Rope(val length: Int)

class Clicks(var count: Int)

open class Frame(val width: Int, val height: Int)

class PictureFrame(val material: String) : Frame(10, 20)

class Window : Frame(30, 40), Sized
```

File: `sample/src/test/kotlin/com/example/SampleTest.kt`
```kotlin
package com.example

import com.example.geometry.Canvas
import com.example.geometry.describe
import com.example.geometry.fits
import com.example.geometry.increment
import com.example.geometry.label
import com.example.geometry.size
import com.example.geometry.sizeLater
import com.example.model.Clicks
import com.example.model.PictureFrame
import com.example.model.Rectangular
import com.example.model.Rope
import com.example.model.Window
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleTest {
    @Test
    fun `original example`() {
        assertEquals(2, size(Rectangular(1, 2, "red")))
    }

    @Test
    fun `member function through extension overload`() {
        assertTrue(Canvas("main").fits(Rectangular(10, 10, "red")))
    }

    @Test
    fun `extension function`() {
        assertEquals("box 3x4", "box".label(Rectangular(3, 4, "blue")))
    }

    @Test
    fun `Int satisfies Number`() {
        assertEquals("5 m", describe(Rope(5), "m"))
    }

    @Test
    fun `var writes through to the original object`() {
        val clicks = Clicks(1)
        increment(clicks)
        assertEquals(2, clicks.count)
    }

    @Test
    fun `suspend function`() {
        var result = 0
        val block: suspend () -> Int = { sizeLater(Rectangular(2, 5, "green")) }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it.getOrThrow() })
        assertEquals(10, result)
    }

    @Test
    fun `subclass uses the base class overload`() {
        assertEquals(200, size(PictureFrame("oak")))
    }

    @Test
    fun `nominal subclass of a structural match`() {
        assertEquals(1200, size(Window()))
    }
}
```

- [ ] **Step 2: Run the whole build**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew build`
Expected: BUILD SUCCESSFUL; `sample` tests (8) and all processor tests pass; `sample/build/generated/ksp/main/kotlin/com/example/geometry/Shapes_Structural.kt` contains overloads for `Rectangular`, `Frame`, `Window`, but not `PictureFrame`.

- [ ] **Step 3: Verify generated code by eye**

Run: `cat sample/build/generated/ksp/main/kotlin/com/example/geometry/*.kt`
Expected: readable overloads matching the proposal's "What gets generated" section.

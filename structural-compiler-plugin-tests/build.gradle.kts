plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * The compiler plugin's own tests. They compile snippets with the plugin and run the result, which needs the plugin and
 * one Kotlin compiler on the same classpath -- something the DevKit module can't offer, because it builds the plugin
 * against eight compilers at once. The sources are compiled here again, for the compiler this module tests against.
 */
kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

sourceSets.test {
    kotlin.srcDir(rootProject.file("structural-compiler-plugin/src/commonMain/kotlin"))
    kotlin.srcDir(rootProject.file("structural-compiler-plugin/src/post2420Main/kotlin"))
}

// The DevKit's entry points aren't on this classpath; the plugin's own StructuralPluginRegistrar is what the tests use.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    exclude("**/StructuralComponentRegistrar.kt")
    exclude("**/StructuralCommandLineProcessor.kt")
    exclude("**/SourceElements.kt")
}

dependencies {
    testImplementation(project(":structural-annotations"))
    // Matching a dependency class is decided from its bytes, which is the runtime module's job.
    testImplementation(project(":structural-runtime"))
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    // IntelliJ 2026.2 is compiled for Java 25.
    jvmToolchain(25)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    intellijPlatform {
        // -Pstructural.ideaPath=/path/to/IntelliJ IDEA.app uses a local IDE instead of downloading one.
        val localIde = providers.gradleProperty("structural.ideaPath")
        if (localIde.isPresent) local(localIde) else intellijIdea("2026.2")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    // The platform's test fixtures are JUnit 3 based.
    testImplementation("junit:junit:4.13.2")
}

// The IDE provides the Kotlin standard library.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// The compiler plugin sources, compiled again against the Kotlin compiler shipped inside the IDE, so the IDE never runs a
// build made for a different compiler version.
val ideCompilerPlugin: SourceSet = sourceSets.create("ideCompilerPlugin") {
    // The compiler plugin's own sources, compiled against the IDE's Kotlin compiler. The files that depend on the
    // DevKit -- its entry points and the expect/actual shim -- are replaced by this module's own, in
    // src/ideCompilerPlugin/kotlin, because the IDE copy is a plain JVM compilation.
    kotlin.srcDir(rootProject.file("structural-compiler-plugin/src/commonMain/kotlin"))
    compileClasspath += sourceSets.main.get().compileClasspath
}

// The DevKit's entry points and the expect/actual shim can't compile in a plain JVM source set.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIdeCompilerPluginKotlin") {
    exclude("**/StructuralComponentRegistrar.kt")
    exclude("**/StructuralCommandLineProcessor.kt")
    exclude("**/SourceElements.kt")
}

// The highlighting test registers the compiler plugin's own extensions with the IDE's analysis.
dependencies {
    testImplementation(ideCompilerPlugin.output)
}

val ideCompilerPluginJar = tasks.register<Jar>("ideCompilerPluginJar") {
    archiveFileName = "structural-compiler-plugin.jar"
    destinationDirectory = layout.buildDirectory.dir("ide-compiler-plugin")
    from(ideCompilerPlugin.output)
    // The IntelliJ Platform Gradle plugin adds the patched plugin.xml to every source set's resources.
    exclude("META-INF/plugin.xml")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }
    }
}

tasks.named<PrepareSandboxTask>("prepareSandbox") {
    // Outside lib/: the Kotlin plugin loads this jar in its own class loader, it must not be on the plugin's classpath.
    from(ideCompilerPluginJar) {
        into(pluginName.map { "$it/compiler-plugin" })
    }
}

/** A compiled module publishing a @Structural interface, for the test that matches one across a module boundary. */
val fixtureLibrary: Configuration by configurations.creating

dependencies {
    fixtureLibrary(project(":sample-library"))
}

tasks.test {
    useJUnitPlatform()
    // The platform caches one light project per descriptor and reuses it across classes, so a class that configures
    // the project differently would otherwise change what its neighbours analyze.
    forkEvery = 1
    dependsOn(ideCompilerPluginJar, fixtureLibrary)
    doFirst {
        // The highlighting test loads the compiler plugin the way a Gradle project does: from a jar.
        systemProperty("structural.compilerPluginJar", ideCompilerPluginJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("structural.fixtureLibrary", fixtureLibrary.files.joinToString(File.pathSeparator))
    }
}

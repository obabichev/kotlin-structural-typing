import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

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
    }
    testImplementation(kotlin("test"))
}

// The IDE provides the Kotlin standard library.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// The compiler plugin sources, compiled again against the Kotlin compiler shipped inside the IDE, so the IDE never runs a
// build made for a different compiler version.
val ideCompilerPlugin: SourceSet = sourceSets.create("ideCompilerPlugin") {
    kotlin.srcDir(rootProject.file("structural-compiler-plugin/src/main/kotlin"))
    resources.srcDir(rootProject.file("structural-compiler-plugin/src/main/resources"))
    compileClasspath += sourceSets.main.get().compileClasspath
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
        version = "0.1.0"
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

tasks.test {
    useJUnitPlatform()
}

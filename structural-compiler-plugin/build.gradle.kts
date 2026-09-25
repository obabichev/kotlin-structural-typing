plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    // Matching a dependency class is decided from its bytes, by the same code the runtime classloader uses.
    implementation(project(":structural-runtime"))

    testImplementation(project(":structural-annotations"))
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kctfork.core)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
